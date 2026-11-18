package app.onebook.autoledger.ingest

import app.onebook.autoledger.core.ActionChoice
import app.onebook.autoledger.core.ActionType
import app.onebook.autoledger.core.ClassifyEngine
import app.onebook.autoledger.core.PendingTxn
import app.onebook.autoledger.core.Rule
import app.onebook.autoledger.core.SimilarEngine
import app.onebook.autoledger.core.SuggestEngine
import app.onebook.autoledger.core.TxnState
import app.onebook.autoledger.data.Repo
import app.onebook.autoledger.inbox.Actions
import app.onebook.autoledger.inbox.BulkApply
import app.onebook.autoledger.inbox.Formatters
import app.onebook.autoledger.inbox.InboxErp
import app.onebook.autoledger.inbox.InboxModelBuilder
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

data class AutoDoneMark(
    val at: Long,
    val docs: List<String>,
    val queued: Boolean = false,
) {
    fun toJson(): String = JSONObject()
        .put("at", at)
        .put("docs", JSONArray(docs))
        .apply { if (queued) put("queued", true) }
        .toString()

    companion object {
        fun parse(v: String?): AutoDoneMark? {
            if (v.isNullOrBlank()) return null
            return try {
                val o = JSONObject(v)
                if (!o.has("at") || o.isNull("at")) return null
                val at = o.getLong("at")
                val arr = o.optJSONArray("docs") ?: JSONArray()
                val docs = (0 until arr.length()).mapNotNull { i ->
                    val s = arr.opt(i)
                    if (s is String) s else null
                }
                AutoDoneMark(at = at, docs = docs, queued = o.optBoolean("queued", false))
            } catch (_: Throwable) {
                null
            }
        }
    }
}

data class AutoApplyResult(
    val done: List<String> = emptyList(),
    val failed: List<String> = emptyList(),
    val queued: List<String> = emptyList(),
)

object AutoApply {
    const val AUTO_LIMIT = 25
    const val AUTO_DONE_PREFIX = "auto_done:"
    const val AUTO_FAILED_PREFIX = "auto_failed:"
    const val AUTO_SKIP_PREFIX = "auto_skip:"
    const val AUTO_HELD_PREFIX = "auto_held:"
    const val AUTO_SINCE_PREFIX = "auto_since:"
    const val AUTO_NEW_COUNT_KEY = "auto_new_count"
    private const val DRAWINGS_SETTING = "drawingsAccount"

    fun autoDoneKey(id: String) = "$AUTO_DONE_PREFIX$id"
    fun autoFailedKey(id: String) = "$AUTO_FAILED_PREFIX$id"
    fun autoHeldKey(id: String) = "$AUTO_HELD_PREFIX$id"
    fun autoSinceKey(matchKey: String) = "$AUTO_SINCE_PREFIX$matchKey"

    suspend fun switchAutomatic(repo: Repo, rule: Rule, on: Boolean, now: Long) {
        if (on) repo.setSetting(autoSinceKey(rule.matchKey), now.toString())
        else repo.deleteSetting(autoSinceKey(rule.matchKey))
        repo.saveRule(rule.copy(auto = on))
    }

    suspend fun forgetRule(repo: Repo, matchKey: String) {
        repo.deleteSetting(autoSinceKey(matchKey))
        repo.deleteRule(matchKey)
    }

    private fun ruleToActionChoice(rule: Rule): ActionChoice? {
        val c = SuggestEngine.ruleChoice(rule) ?: return null
        return when (c.type) {
            ActionType.EXPENSE -> ActionChoice.Expense(c.expenseAccount!!, c.party)
            ActionType.OTHER_INCOME -> ActionChoice.OtherIncome(c.incomeAccount!!)
            ActionType.SUPPLIER_PAYMENT -> ActionChoice.SupplierPayment(c.party!!)
            ActionType.PERSONAL -> ActionChoice.Personal
            ActionType.IGNORE -> ActionChoice.Ignore
            else -> null
        }
    }

    private fun lookAlikes(all: List<PendingTxn>): (PendingTxn) -> PendingTxn? {
        data class Entry(val t: PendingTxn, val day: Long?)
        val buckets = HashMap<String, MutableList<Entry>>()
        fun keyOf(t: PendingTxn): String? {
            val name = SuggestEngine.nameKey(t) ?: return null
            return "$name\u0000${t.currency}\u0000${t.amountMinor}"
        }
        for (t in all) {
            val k = keyOf(t) ?: continue
            buckets.getOrPut(k) { mutableListOf() }.add(Entry(t, SimilarEngine.dayNumber(t.occurredOn)))
        }
        return { t ->
            val k = keyOf(t)
            val day = SimilarEngine.dayNumber(t.occurredOn)
            var match: PendingTxn? = null
            if (k != null) {
                for (o in buckets[k].orEmpty()) {
                    if (o.t.id == t.id) continue
                    if (day == null || o.day == null || abs(day - o.day) <= SimilarEngine.SIMILAR_DAYS) {
                        match = o.t
                        break
                    }
                }
            }
            match
        }
    }

    private suspend fun countUse(repo: Repo, txn: PendingTxn, choice: ActionChoice) {
        try {
            val rules = repo.listRules()
            val key = SuggestEngine.nameKey(txn)
            val stored = rules.firstOrNull { it.matchKey == key } ?: return
            if (!InboxModelBuilder.ruleHasAnswer(stored, choice)) return
            for (r in SuggestEngine.learnRule(txn, choice, rules, raiseMax = false)) {
                if (rules.none { it == r }) repo.saveRule(r)
            }
        } catch (_: Throwable) {
            // Best effort
        }
    }

    private suspend fun bump(repo: Repo, n: Int) {
        if (n <= 0) return
        try {
            val cur = repo.getSetting(AUTO_NEW_COUNT_KEY)?.toIntOrNull() ?: 0
            repo.setSetting(AUTO_NEW_COUNT_KEY, (cur + n).toString())
        } catch (_: Throwable) {
            // Best effort
        }
    }

    suspend fun runAutoApply(
        repo: Repo,
        erp: InboxErp,
        now: () -> Long = System::currentTimeMillis,
    ): AutoApplyResult {
        val doneList = mutableListOf<String>()
        val failedList = mutableListOf<String>()
        val queuedList = mutableListOf<String>()
        try {
            val all = repo.listPending()
            val queued = repo.listQueuedTxnIds().toHashSet()
            val failedRows = repo.listSettings(AUTO_FAILED_PREFIX)
            val doneRows = repo.listSettings(AUTO_DONE_PREFIX)
            val skipRows = repo.listSettings(AUTO_SKIP_PREFIX)
            val heldRows = repo.listSettings(AUTO_HELD_PREFIX)

            val pending = all.filter { it.state == TxnState.NEW }
            val waiting = pending.mapTo(HashSet()) { it.id }

            val failed = HashSet<String>()
            for ((k, _) in failedRows) {
                val id = k.removePrefix(AUTO_FAILED_PREFIX)
                if (id !in waiting) repo.deleteSetting(autoFailedKey(id)) else failed.add(id)
            }
            val held = HashMap<String, String>()
            for ((k, v) in heldRows) {
                val id = k.removePrefix(AUTO_HELD_PREFIX)
                if (id !in waiting) repo.deleteSetting(autoHeldKey(id)) else held[id] = v
            }
            val skip = skipRows.mapTo(HashSet()) { it.first.removePrefix(AUTO_SKIP_PREFIX) }
            val done = HashSet<String>()
            for ((k, _) in doneRows) {
                val id = k.removePrefix(AUTO_DONE_PREFIX)
                if (id in waiting && id !in queued) {
                    repo.setSetting("$AUTO_SKIP_PREFIX$id", "1")
                    repo.deleteSetting(autoDoneKey(id))
                    skip.add(id)
                } else {
                    done.add(id)
                }
            }

            if (!repo.getSetting(OutboxDrainer.NEEDS_LOGIN_SINCE_KEY).isNullOrEmpty()) {
                return AutoApplyResult()
            }
            val autoRules = repo.listRules().filter { it.auto }
            if (autoRules.isEmpty()) return AutoApplyResult()
            val dups = repo.listSettings(DupCheck.DUP_KEY_PREFIX).mapTo(HashSet()) {
                it.first.removePrefix(DupCheck.DUP_KEY_PREFIX)
            }
            val since = HashMap<String, Long>()
            for ((k, v) in repo.listSettings(AUTO_SINCE_PREFIX)) {
                since[k.removePrefix(AUTO_SINCE_PREFIX)] = v.toLongOrNull() ?: 0L
            }
            val checkedFrom = repo.getSetting(DupCheck.CHECKED_FROM_KEY)?.takeIf { it.isNotBlank() }

            fun fits(t: PendingTxn, rule: Rule): Boolean {
                val from = since[rule.matchKey] ?: 0L
                return from > 0L &&
                    t.receivedAt > from &&
                    (checkedFrom == null || t.occurredOn >= checkedFrom) &&
                    Actions.actionsFor(t.direction, t.accountType).any { it.type == rule.action }
            }

            val lookAlike = lookAlikes(all)
            val hold = mutableListOf<Pair<PendingTxn, PendingTxn>>()
            val candidates = pending
                .sortedWith(compareBy<PendingTxn> { it.receivedAt }.thenBy { it.id })
                .filter { t ->
                    if (t.id in queued || t.id in failed || t.id in skip || t.id in done) return@filter false
                    val rule = autoRules.firstOrNull { it.matchKey == SuggestEngine.nameKey(t) }
                    if (rule == null || !fits(t, rule) || !ClassifyEngine.canAutoApply(t, rule, t.id in dups)) {
                        return@filter false
                    }
                    val other = lookAlike(t)
                    if (other != null) {
                        hold.add(t to other)
                        false
                    } else {
                        true
                    }
                }

            for ((t, other) in hold) {
                if (held[t.id] != other.id) repo.setSetting(autoHeldKey(t.id), other.id)
            }
            if (candidates.isEmpty()) return AutoApplyResult()

            val mappings = repo.listMappings()
            val company = repo.getSetting(DupCheck.COMPANY_SETTING).orEmpty()
            val drawings = repo.getSetting(DRAWINGS_SETTING).orEmpty()
            val planner = InboxModelBuilder.planChoice(
                mappings = mappings,
                company = company,
                drawingsAccount = drawings,
                today = Formatters.istToday(now()),
            )

            var tried = 0
            for (t in candidates) {
                if (tried >= AUTO_LIMIT) break
                if (!repo.getSetting(OutboxDrainer.NEEDS_LOGIN_SINCE_KEY).isNullOrEmpty()) break
                val rule = repo.listRules().firstOrNull { it.matchKey == SuggestEngine.nameKey(t) }
                val fresh = repo.getPending(t.id)
                if (rule == null || fresh == null) continue
                val startedAt = repo.getSetting(autoSinceKey(rule.matchKey))?.toLongOrNull() ?: 0L
                if (startedAt <= 0L || fresh.receivedAt <= startedAt) continue
                val hasDup = repo.getSetting(DupCheck.dupKey(t.id)) != null
                if (!ClassifyEngine.canAutoApply(fresh, rule, hasDup) || !fits(fresh, rule)) continue
                if (fresh.createdDocs.isNotEmpty() || repo.listOutbox(t.id).isNotEmpty()) {
                    repo.setSetting("$AUTO_SKIP_PREFIX${t.id}", "1")
                    continue
                }
                val choice = ruleToActionChoice(rule) ?: continue
                tried++
                val r = BulkApply.applyToGroup(
                    repo = repo,
                    erp = erp,
                    plan = planner,
                    items = listOf(fresh),
                    choice = choice,
                    remember = false,
                )
                if (r.done.isNotEmpty()) {
                    val after = repo.getPending(t.id) ?: fresh
                    repo.setSetting(
                        autoDoneKey(t.id),
                        AutoDoneMark(now(), after.createdDocs.map { it.name }).toJson(),
                    )
                    doneList.add(t.id)
                    countUse(repo, after, choice)
                    continue
                }
                if (r.queued.isNotEmpty()) {
                    repo.setSetting(
                        autoDoneKey(t.id),
                        AutoDoneMark(now(), emptyList(), queued = true).toJson(),
                    )
                    queuedList.add(t.id)
                    break
                }
                val f = r.failed.firstOrNull() ?: continue
                if (f.message == BulkApply.BULK_SIGNED_OUT) break
                if (f.message == BulkApply.BULK_BUSY) continue
                repo.setSetting(autoFailedKey(t.id), f.message)
                failedList.add(t.id)
            }
        } catch (_: Throwable) {
            // Best effort
        }
        bump(repo, doneList.size)
        return AutoApplyResult(done = doneList, failed = failedList, queued = queuedList)
    }
}
