package app.onebook.autoledger.data

import androidx.room.withTransaction
import app.onebook.autoledger.core.AccountMapping
import app.onebook.autoledger.core.AccountType
import app.onebook.autoledger.core.ActionPlan
import app.onebook.autoledger.core.ActionType
import app.onebook.autoledger.core.CreatedDoc
import app.onebook.autoledger.core.Direction
import app.onebook.autoledger.core.OutboxEntry
import app.onebook.autoledger.core.OutboxProgress
import app.onebook.autoledger.core.OutboxStatus
import app.onebook.autoledger.core.PartyDoctype
import app.onebook.autoledger.core.PartyRef
import app.onebook.autoledger.core.PendingTxn
import app.onebook.autoledger.core.PlanStep
import app.onebook.autoledger.core.ProgressDoc
import app.onebook.autoledger.core.Rule
import app.onebook.autoledger.core.TxnKind
import app.onebook.autoledger.core.TxnPatch
import app.onebook.autoledger.core.TxnState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

sealed interface MergeResult {
    data class Merged(val txn: PendingTxn) : MergeResult
    data object Already : MergeResult
    data object Missing : MergeResult
}

data class NewSourceInfo(
    val sender: String,
    val accountType: AccountType,
    val accountLast4: String?,
)

class Repo(private val db: AutoLedgerDatabase) {
    private val dao = db.dao()
    private val mutex = Mutex()

    suspend fun listPending(states: List<TxnState>? = null): List<PendingTxn> = mutex.withLock {
        val entities = when {
            states == null -> dao.listAllPending()
            states.isEmpty() -> return emptyList()
            else -> dao.listPendingByStates(states.map { it.wire }.distinct())
        }
        entities.map { it.toDomain() }
    }

    suspend fun countPending(state: TxnState): Int = mutex.withLock {
        dao.countPending(state.wire)
    }

    suspend fun listNewSources(): List<NewSourceInfo> = mutex.withLock {
        dao.listNewSources().map {
            NewSourceInfo(
                sender = it.sender,
                accountType = AccountType.fromWire(it.accountType),
                accountLast4 = it.accountLast4,
            )
        }
    }

    suspend fun upsertPending(t: PendingTxn) = mutex.withLock {
        dao.upsertPendingSafe(t.toEntity())
    }

    suspend fun getPending(id: String): PendingTxn? = mutex.withLock {
        dao.getPendingById(id)?.toDomain()
    }

    suspend fun mergeInto(targetId: String, patch: TxnPatch, sourceRawHash: String): MergeResult = mutex.withLock {
        db.withTransaction {
            val seen = dao.getSetting(MERGED_PREFIX + sourceRawHash)
            if (seen != null) return@withTransaction MergeResult.Already

            val curEntity = dao.getPendingById(targetId) ?: return@withTransaction MergeResult.Missing
            val cur = curEntity.toDomain()
            val next = if (cur.state == TxnState.NEW) {
                cur.copy(
                    kind = patch.kind ?: cur.kind,
                    occurredOn = patch.occurredOn ?: cur.occurredOn,
                    occurredTime = if (patch.hasOccurredTime) patch.occurredTime else cur.occurredTime,
                    accountLast4 = if (patch.hasAccountLast4) patch.accountLast4 else cur.accountLast4,
                    counterparty = if (patch.hasCounterparty) patch.counterparty else cur.counterparty,
                    utr = if (patch.hasUtr) patch.utr else cur.utr,
                    balanceMinor = if (patch.hasBalanceMinor) patch.balanceMinor else cur.balanceMinor,
                    mergedCount = cur.mergedCount + 1,
                )
            } else {
                cur.copy(
                    utr = if (patch.hasUtr) patch.utr else cur.utr,
                    counterparty = if (patch.hasCounterparty) patch.counterparty else cur.counterparty,
                    mergedCount = cur.mergedCount + 1,
                )
            }
            dao.replacePending(next.toEntity())
            dao.setSetting(SettingEntity(MERGED_PREFIX + sourceRawHash, targetId))
            MergeResult.Merged(next)
        }
    }

    suspend fun isMergedSms(rawHash: String): Boolean = mutex.withLock {
        dao.getSetting(MERGED_PREFIX + rawHash) != null
    }

    suspend fun listMergedSmsHashes(): Set<String> = mutex.withLock {
        dao.listSettings(MERGED_PREFIX.length, MERGED_PREFIX)
            .mapTo(HashSet()) { it.key.removePrefix(MERGED_PREFIX) }
    }

    suspend fun saveMapping(m: AccountMapping) = mutex.withLock {
        dao.saveMapping(m.toEntity())
    }

    suspend fun listMappings(): List<AccountMapping> = mutex.withLock {
        dao.listMappings().map { it.toDomain() }
    }

    suspend fun saveRule(r: Rule) = mutex.withLock {
        dao.saveRule(r.toEntity())
    }

    suspend fun deleteRule(matchKey: String) = mutex.withLock {
        dao.deleteRule(matchKey)
    }

    suspend fun listRules(): List<Rule> = mutex.withLock {
        dao.listRules().map { it.toDomain() }
    }

    suspend fun enqueue(op: OutboxEntry) = mutex.withLock {
        db.withTransaction {
            dao.insertOutbox(op.toEntity())
            dao.supersedeOtherPendingOutbox(op.txnId, op.id)
        }
    }

    suspend fun reopenOutbox(id: String) = mutex.withLock {
        db.withTransaction {
            val row = dao.getOutbox(id) ?: return@withTransaction
            dao.supersedeOtherPendingOutbox(row.txnId, id)
            dao.updateOutboxStatus(id, OutboxStatus.PENDING.wire)
        }
    }

    suspend fun listOutbox(txnId: String): List<OutboxEntry> = mutex.withLock {
        dao.listOutboxForTxn(txnId).mapNotNull { it.toDomainOrNull() }
    }

    suspend fun listOpenOutbox(): List<OutboxEntry> = mutex.withLock {
        dao.listOpenOutbox().mapNotNull { it.toDomainOrNull() }
    }

    suspend fun getOutbox(id: String): OutboxEntry? = mutex.withLock {
        dao.getOutbox(id)?.toDomainOrNull()
    }

    suspend fun nextOutbox(): OutboxEntry? = mutex.withLock {
        while (true) {
            val row = dao.nextPendingOutbox() ?: return@withLock null
            val parsed = row.toDomainOrNull()
            if (parsed == null) {
                dao.updateOutboxStatus(row.id, OutboxStatus.FAILED.wire)
                continue
            }
            return@withLock parsed
        }
        null
    }

    suspend fun supersedePending(txnId: String, exceptId: String) = mutex.withLock {
        dao.supersedeOtherPendingOutbox(txnId, exceptId)
    }

    suspend fun finishOutbox(id: String, progress: OutboxProgress) = mutex.withLock {
        dao.finishOutbox(id, serializeProgress(progress))
    }

    suspend fun markOutbox(
        id: String,
        progress: OutboxProgress? = null,
        attempts: Int? = null,
        status: OutboxStatus? = null,
    ) = mutex.withLock {
        db.withTransaction {
            if (progress != null) dao.updateOutboxProgress(id, serializeProgress(progress))
            if (attempts != null) dao.updateOutboxAttempts(id, attempts)
            if (status != null) dao.updateOutboxStatus(id, status.wire)
        }
    }

    suspend fun resetForNewSite() = mutex.withLock {
        dao.resetForNewSite()
    }

    suspend fun getSetting(key: String): String? = mutex.withLock {
        dao.getSetting(key)
    }

    suspend fun setSetting(key: String, value: String) = mutex.withLock {
        dao.setSetting(SettingEntity(key, value))
    }

    suspend fun listSettings(prefix: String): List<Pair<String, String>> = mutex.withLock {
        dao.listSettings(prefix.length, prefix).map { it.key to it.value }
    }

    suspend fun deleteSetting(key: String) = mutex.withLock {
        dao.deleteSetting(key)
    }

    suspend fun listActiveOutbox(): List<OutboxEntry> = mutex.withLock {
        dao.listActiveOutbox().mapNotNull { it.toDomainOrNull() }
    }

    suspend fun listQueuedTxnIds(): List<String> = mutex.withLock {
        dao.listQueuedTxnIds()
    }

    companion object {
        private const val MERGED_PREFIX = "merged_sms:"

        fun serializeCreatedDocs(docs: List<CreatedDoc>): String {
            val arr = JSONArray()
            for (d in docs) {
                arr.put(
                    JSONObject()
                        .put("doctype", d.doctype)
                        .put("name", d.name)
                        .apply {
                            if (d.docstatus != null) put("docstatus", d.docstatus)
                            if (d.adopted) put("adopted", true)
                        }
                )
            }
            return arr.toString()
        }

        fun parseCreatedDocs(raw: String): List<CreatedDoc> = try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val dt = obj.optString("doctype", "")
                val name = obj.optString("name", "")
                if (dt.isEmpty() || name.isEmpty()) return@mapNotNull null
                val ds = if (obj.has("docstatus") && !obj.isNull("docstatus")) obj.getInt("docstatus") else null
                val adopted = obj.optBoolean("adopted", false)
                CreatedDoc(dt, name, ds, adopted)
            }
        } catch (_: Exception) {
            emptyList()
        }

        fun serializeProgress(progress: OutboxProgress): String {
            val arr = JSONArray()
            for (c in progress.created) {
                arr.put(
                    JSONObject()
                        .put("doctype", c.doctype)
                        .put("name", c.name)
                        .apply { if (c.adopted) put("adopted", true) }
                )
            }
            return JSONObject()
                .put("stepIndex", progress.stepIndex)
                .put("created", arr)
                .apply {
                    if (progress.attempting != null) put("attempting", progress.attempting)
                }
                .toString()
        }

        fun parseProgress(raw: String): OutboxProgress? = try {
            val obj = JSONObject(raw)
            val stepIndex = obj.getInt("stepIndex")
            val arr = obj.getJSONArray("created")
            val created = (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                ProgressDoc(
                    doctype = c.getString("doctype"),
                    name = c.getString("name"),
                    adopted = c.optBoolean("adopted", false),
                )
            }
            val attempting = if (obj.has("attempting") && !obj.isNull("attempting")) obj.getInt("attempting") else null
            OutboxProgress(stepIndex = stepIndex, created = created, attempting = attempting)
        } catch (_: Exception) {
            null
        }

        fun serializePlan(plan: ActionPlan): String {
            val stepsArr = JSONArray()
            for (s in plan.steps) {
                stepsArr.put(
                    JSONObject()
                        .put("kind", s.kind)
                        .put("doctype", s.doctype)
                        .put("body", JSONObject(s.bodyJson))
                )
            }
            val summaryArr = JSONArray()
            plan.summary.forEach { summaryArr.put(it) }
            return JSONObject()
                .put("steps", stepsArr)
                .put("summary", summaryArr)
                .put("needsInrAmount", plan.needsInrAmount)
                .toString()
        }

        fun parsePlan(raw: String): ActionPlan? = try {
            val obj = JSONObject(raw)
            val stepsArr = obj.getJSONArray("steps")
            val steps = (0 until stepsArr.length()).map { i ->
                val s = stepsArr.getJSONObject(i)
                PlanStep(
                    kind = s.getString("kind"),
                    doctype = s.getString("doctype"),
                    bodyJson = s.getJSONObject("body").toString(),
                )
            }
            val summaryArr = obj.optJSONArray("summary") ?: JSONArray()
            val summary = (0 until summaryArr.length()).map { summaryArr.getString(it) }
            ActionPlan(
                steps = steps,
                summary = summary,
                needsInrAmount = obj.optBoolean("needsInrAmount", false),
            )
        } catch (_: Exception) {
            null
        }
    }
}

private fun PendingTxnEntity.toDomain(): PendingTxn = PendingTxn(
    id = id,
    kind = TxnKind.fromWire(kind),
    direction = Direction.fromWire(direction),
    amountMinor = amountMinor,
    currency = currency,
    occurredOn = occurredOn,
    occurredTime = occurredTime,
    accountType = AccountType.fromWire(accountType),
    accountLast4 = accountLast4,
    counterparty = counterparty,
    utr = utr,
    merchant = merchant,
    balanceMinor = balanceMinor,
    sender = sender,
    receivedAt = receivedAt,
    rawHash = rawHash,
    state = TxnState.fromWire(state),
    mergedCount = mergedCount,
    createdDocs = Repo.parseCreatedDocs(createdDocs),
    error = error,
)

private fun PendingTxn.toEntity(): PendingTxnEntity = PendingTxnEntity(
    id = id,
    rawHash = rawHash,
    state = state.wire,
    mergedCount = mergedCount,
    createdDocs = Repo.serializeCreatedDocs(createdDocs),
    error = error,
    kind = kind.wire,
    direction = direction.wire,
    amountMinor = amountMinor,
    currency = currency,
    occurredOn = occurredOn,
    occurredTime = occurredTime,
    accountType = accountType.wire,
    accountLast4 = accountLast4,
    counterparty = counterparty,
    utr = utr,
    merchant = merchant,
    balanceMinor = balanceMinor,
    sender = sender,
    receivedAt = receivedAt,
)

private fun AccountMappingEntity.toDomain(): AccountMapping = AccountMapping(
    bankAccount = bankAccount,
    glAccount = glAccount,
    kind = AccountType.fromWire(kind),
    senderIds = parseStringList(senderIds),
    last4 = parseStringList(last4),
    modeOfPayment = modeOfPayment,
)

private fun AccountMapping.toEntity(): AccountMappingEntity = AccountMappingEntity(
    bankAccount = bankAccount,
    glAccount = glAccount,
    kind = kind.wire,
    senderIds = JSONArray(senderIds).toString(),
    last4 = JSONArray(last4).toString(),
    modeOfPayment = modeOfPayment,
)

private fun RuleEntity.toDomain(): Rule {
    val parsedParty = party?.let {
        try {
            val o = JSONObject(it)
            PartyRef(PartyDoctype.fromWire(o.getString("doctype")), o.getString("name"))
        } catch (_: Exception) {
            null
        }
    }
    return Rule(
        matchKey = matchKey,
        action = ActionType.fromWire(action),
        party = parsedParty,
        account = account,
        hits = hits,
        lastUsed = lastUsed,
        auto = auto == 1,
        maxAmountMinor = maxAmountMinor,
        currency = currency,
    )
}

private fun Rule.toEntity(): RuleEntity = RuleEntity(
    matchKey = matchKey,
    action = action.wire,
    party = party?.let {
        JSONObject().put("doctype", it.doctype.wire).put("name", it.name).toString()
    },
    account = account,
    hits = hits,
    lastUsed = lastUsed,
    auto = if (auto) 1 else 0,
    maxAmountMinor = maxAmountMinor,
    currency = currency,
)

private fun OutboxEntity.toDomainOrNull(): OutboxEntry? {
    val parsedPlan = Repo.parsePlan(plan) ?: return null
    val parsedProgress = Repo.parseProgress(progress) ?: return null
    return OutboxEntry(
        seq = seq,
        id = id,
        txnId = txnId,
        plan = parsedPlan,
        progress = parsedProgress,
        attempts = attempts,
        status = OutboxStatus.fromWire(status),
    )
}

private fun OutboxEntry.toEntity(): OutboxEntity = OutboxEntity(
    seq = seq,
    id = id,
    txnId = txnId,
    plan = Repo.serializePlan(plan),
    progress = Repo.serializeProgress(progress),
    attempts = attempts,
    status = status.wire,
)

private fun parseStringList(raw: String): List<String> = try {
    val arr = JSONArray(raw)
    (0 until arr.length()).map { arr.getString(it) }
} catch (_: Exception) {
    emptyList()
}
