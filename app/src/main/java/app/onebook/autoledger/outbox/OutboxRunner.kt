package app.onebook.autoledger.outbox

import app.onebook.autoledger.core.ActionPlan
import app.onebook.autoledger.core.CreatedDoc
import app.onebook.autoledger.core.OutboxEntry
import app.onebook.autoledger.core.OutboxProgress
import app.onebook.autoledger.core.OutboxStatus
import app.onebook.autoledger.core.PartyDoctype
import app.onebook.autoledger.core.PartyRef
import app.onebook.autoledger.core.PendingTxn
import app.onebook.autoledger.core.PlanStep
import app.onebook.autoledger.core.ProgressDoc
import app.onebook.autoledger.core.TxnState
import app.onebook.autoledger.data.Repo
import app.onebook.autoledger.erp.ErpAuthError
import app.onebook.autoledger.erp.ErpDocs
import app.onebook.autoledger.erp.ErpNetworkError
import app.onebook.autoledger.erp.ErpOps
import app.onebook.autoledger.erp.ErpReports
import app.onebook.autoledger.erp.ErpValidationError
import app.onebook.autoledger.erp.NotDraftError
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

sealed interface RunResult {
    data class Created(val docs: List<Pair<String, String>>) : RunResult
    data class Failed(val error: Exception, val retryable: Boolean) : RunResult
    data object NeedsLogin : RunResult
}

sealed interface UndoResult {
    data class Undone(val kept: List<Pair<String, String>> = emptyList()) : UndoResult
    data class Refused(val reason: String, val message: String) : UndoResult
    data class Failed(val error: Exception, val retryable: Boolean) : UndoResult
    data object NeedsLogin : UndoResult
}

object OutboxRunner {
    const val PERMISSION_DENIED_MESSAGE =
        "Your ERPNext user is not allowed to create this document; ask your administrator for access, then try again"
    const val SUBMITTED_UNDO_MESSAGE =
        "Your accountant already submitted this entry. Cancel it in ERPNext if it is wrong."
    const val UNDO_FORBIDDEN_MESSAGE =
        "Your ERPNext user is not allowed to delete this entry. Ask your administrator."

    private val REF_FIELD = mapOf("Payment Entry" to "reference_no", "Journal Entry" to "cheque_no")
    private val PARTY_TYPES = setOf("Customer", "Supplier", "Employee")
    private val NAME_FIELD = mapOf(
        "Customer" to "customer_name",
        "Supplier" to "supplier_name",
        "Employee" to "employee_name",
    )

    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withTxnLock(txnId: String, fn: suspend () -> T): T {
        val mutex = locks.getOrPut(txnId) { Mutex() }
        return mutex.withLock { fn() }
    }

    fun referenceOf(step: PlanStep): String? {
        if (step.kind != "create_doc") return null
        val field = REF_FIELD[step.doctype] ?: return null
        val v = step.bodyObject().optString(field, "")
        return v.trim().takeIf { it.isNotEmpty() }
    }

    private fun cyrb53Base36(s: String): String {
        var h1 = 0xdeadbeef.toInt()
        var h2 = 0x41c6ce57
        for (i in s.indices) {
            val ch = s[i].code
            h1 = (h1 xor ch) * 2654435761.toInt()
            h2 = (h2 xor ch) * 1597334677
        }
        h1 = ((h1 xor (h1 ushr 16)) * 2246822507.toInt()) xor ((h2 xor (h2 ushr 13)) * 3266489909.toInt())
        h2 = ((h2 xor (h2 ushr 16)) * 2246822507.toInt()) xor ((h1 xor (h1 ushr 13)) * 3266489909.toInt())
        val combined = 4294967296L * (2097151L and h2.toLong()) + (h1.toLong() and 0xFFFFFFFFL)
        return combined.toString(36)
    }

    fun outboxIdFor(txnId: String, plan: ActionPlan): String {
        val stepsArr = JSONArray().apply {
            plan.steps.forEach { s ->
                put(JSONObject().put("kind", s.kind).put("doctype", s.doctype).put("body", s.bodyObject()))
            }
        }
        return "$txnId#${cyrb53Base36(stepsArr.toString())}"
    }

    private suspend fun writeTxn(
        repo: Repo,
        txnId: String,
        fallback: PendingTxn,
        state: TxnState,
        createdDocs: List<CreatedDoc>,
        error: String?,
    ) {
        val cur = repo.getPending(txnId) ?: fallback
        repo.upsertPending(cur.copy(state = state, createdDocs = createdDocs, error = error))
    }

    private fun checkPlan(plan: ActionPlan): String? {
        if (plan.needsInrAmount) return "Enter the INR amount before creating this entry."
        val refs = HashSet<String>()
        for (s in plan.steps) {
            val body = s.bodyObject()
            if (body.has("docstatus") && !body.isNull("docstatus") && body.optInt("docstatus", -1) != 0) {
                return "AutoLedger only creates drafts. This plan asked ERPNext to submit a document, so nothing was sent."
            }
            when (s.kind) {
                "create_party" -> if (s.doctype !in PARTY_TYPES) return "AutoLedger cannot create a ${s.doctype}."
                "create_doc" -> {
                    if (s.doctype !in REF_FIELD) return "AutoLedger cannot create a ${s.doctype}."
                    val ref = referenceOf(s)
                        ?: return "The ${s.doctype} has no transaction reference, so it cannot be created safely."
                    if (!refs.add(ref)) {
                        return "Two entries in this plan share one reference, so it cannot be created safely."
                    }
                }
                else -> return "This plan contains an unknown step."
            }
        }
        return null
    }

    private fun referencedPartyName(plan: ActionPlan, index: Int): String? {
        val dt = plan.steps[index].doctype
        for (s in plan.steps.drop(index + 1)) {
            if (s.kind != "create_doc") continue
            val body = s.bodyObject()
            if (body.optString("party_type") == dt && body.has("party") && !body.isNull("party")) {
                return body.optString("party")
            }
            val rows = body.optJSONArray("accounts") ?: continue
            for (rIdx in 0 until rows.length()) {
                val r = rows.optJSONObject(rIdx) ?: continue
                if (r.optString("party_type") == dt && r.has("party") && !r.isNull("party")) {
                    return r.optString("party")
                }
            }
        }
        return null
    }

    private fun displayNameOf(step: PlanStep): String? {
        val f = step.bodyObject()
        if (step.doctype == "Employee") {
            val parts = listOf("first_name", "middle_name", "last_name")
                .mapNotNull { f.optString(it, "").trim().takeIf { v -> v.isNotEmpty() } }
            return parts.joinToString(" ").takeIf { it.isNotEmpty() }
        }
        val field = NAME_FIELD[step.doctype] ?: return null
        return f.optString(field, "").trim().takeIf { it.isNotEmpty() }
    }

    private fun norm(s: String): String = s.trim().lowercase()

    private fun applyRenames(body: JSONObject, renames: Map<String, String>): JSONObject {
        val copy = JSONObject(body.toString())
        if (renames.isEmpty()) return copy
        fun sub(row: JSONObject) {
            val pt = row.optString("party_type", "")
            val p = row.optString("party", "")
            if (pt.isNotEmpty() && p.isNotEmpty()) {
                val to = renames["$pt\u0000$p"]
                if (to != null) row.put("party", to)
            }
        }
        sub(copy)
        val accounts = copy.optJSONArray("accounts")
        if (accounts != null) {
            for (i in 0 until accounts.length()) {
                accounts.optJSONObject(i)?.let { sub(it) }
            }
        }
        return copy
    }

    private fun describeFailure(e: ErpValidationError, step: PlanStep): String {
        val what = if (step.kind == "create_party") "the new ${step.doctype}" else "the ${step.doctype}"
        val parts = mutableListOf("Could not create $what.")
        if (!e.missingFields.isNullOrEmpty()) {
            parts.add("ERPNext needs these fields: ${e.missingFields.joinToString(", ")}. Fill them in ERPNext, or complete this entry in ERPNext.")
        }
        if (e.closedPeriod) {
            parts.add("The accounting period is closed for this date. Ask your accountant to reopen it, or complete this entry in ERPNext.")
        }
        if (e.missingFields.isNullOrEmpty() && !e.closedPeriod) {
            parts.add(e.message ?: "Validation failed.")
        }
        return parts.joinToString(" ")
    }

    private fun docKey(doctype: String, name: String) = "$doctype:$name"
    private fun docKey(d: CreatedDoc) = docKey(d.doctype, d.name)
    private fun docKey(d: ErpDocs.UtrHit) = docKey(d.doctype, d.name)
    private fun docKey(d: ProgressDoc) = docKey(d.doctype, d.name)
    private fun claimKey(doctype: String, ref: String) = "$doctype\u0000$ref"

    private fun submittedError(name: String) =
        ErpValidationError("$name was already submitted in ERPNext; cancel it there first.", 0)

    private fun replacedError() =
        ErpValidationError("This action was replaced by a newer decision.", 0)

    private fun withoutDocs(op: OutboxEntry): OutboxProgress {
        val k = op.plan.steps.indexOfFirst { it.kind == "create_doc" }
        val upto = if (k < 0) op.progress.stepIndex else minOf(op.progress.stepIndex, k)
        val att = op.progress.attempting
        val keepAtt = if (att != null && (k < 0 || att < k)) att else null
        return OutboxProgress(stepIndex = upto, created = op.progress.created.take(upto), attempting = keepAtt)
    }

    private data class Claims(val docs: Set<String>, val attempting: Set<String>)

    private suspend fun claimsOfOtherTxns(repo: Repo, txnId: String): Claims {
        val docs = HashSet<String>()
        val attempting = HashSet<String>()
        for (t in repo.listPending()) {
            if (t.id != txnId) {
                t.createdDocs.forEach { docs.add(docKey(it)) }
            }
        }
        for (o in repo.listOpenOutbox()) {
            if (o.txnId == txnId) continue
            o.progress.created.take(o.progress.stepIndex).forEach { docs.add(docKey(it)) }
            val att = o.progress.attempting
            if (att != null && att >= o.progress.stepIndex && att < o.plan.steps.size) {
                val s = o.plan.steps[att]
                val ref = referenceOf(s)
                if (ref != null) attempting.add(claimKey(s.doctype, ref))
            }
        }
        return Claims(docs, attempting)
    }

    private fun renamesOf(o: OutboxEntry): Map<String, String> {
        val renames = HashMap<String, String>()
        o.plan.steps.forEachIndexed { j, s ->
            val c = if (j < o.progress.stepIndex) o.progress.created.getOrNull(j) else null
            if (s.kind != "create_party" || c == null) return@forEachIndexed
            val refName = referencedPartyName(o.plan, j)
            if (refName != null && c.name != refName) {
                renames["${s.doctype}\u0000$refName"] = c.name
            }
        }
        return renames
    }

    private suspend fun sameTransfer(erp: ErpOps, hit: ErpDocs.UtrHit, body: JSONObject): Boolean {
        if (hit.doctype != "Payment Entry" || body.optString("payment_type") != "Internal Transfer") return false
        val bodyAmt = try { ErpReports.toMinor(body.opt("paid_amount")) } catch (_: Exception) { return false }
        val s = try {
            erp.getDraftSummary(hit.doctype, hit.name)
        } catch (e: ErpValidationError) {
            if (e.status == 404) return false
            throw e
        }
        val bodyCompany = body.optString("company", "").takeIf { it.isNotEmpty() }
        return s.paymentType == "Internal Transfer" &&
            s.paidFrom != null && s.paidFrom == body.optString("paid_from") &&
            s.paidTo != null && s.paidTo == body.optString("paid_to") &&
            s.paidAmountMinor != null && s.paidAmountMinor == bodyAmt &&
            (s.company == null || bodyCompany == null || s.company == bodyCompany) &&
            s.party == null
    }

    private suspend fun samePlanned(erp: ErpOps, hit: ErpDocs.UtrHit, doctype: String, body: JSONObject): Boolean {
        if (hit.doctype != doctype) return false
        val s = try {
            erp.getDraftSummary(hit.doctype, hit.name)
        } catch (e: ErpValidationError) {
            if (e.status == 404) return false
            throw e
        }
        val bodyCompany = body.optString("company", "").takeIf { it.isNotEmpty() }
        if (bodyCompany != null && s.company != null && s.company != bodyCompany) return false

        if (doctype == "Payment Entry") {
            val bodyAmt = try { ErpReports.toMinor(body.opt("paid_amount")) } catch (_: Exception) { return false }
            if (s.paymentType == null || s.paymentType != body.optString("payment_type")) return false
            if (s.paidAmountMinor == null || s.paidAmountMinor != bodyAmt) return false
            val paidFrom = body.optString("paid_from", "").takeIf { it.isNotEmpty() }
            val paidTo = body.optString("paid_to", "").takeIf { it.isNotEmpty() }
            if (paidFrom != null && s.paidFrom != paidFrom) return false
            if (paidTo != null && s.paidTo != paidTo) return false
            val party = body.optString("party", "").takeIf { it.isNotEmpty() }
            val partyType = body.optString("party_type", "").takeIf { it.isNotEmpty() }
            if (party != null) {
                if (s.party != party || (partyType != null && s.partyType != partyType)) return false
            } else if (s.party != null) {
                return false
            }
            return true
        }

        val plannedArr = body.optJSONArray("accounts") ?: JSONArray()
        data class Tot(var debit: Long = 0L, var credit: Long = 0L)
        val want = HashMap<String, Tot>()
        for (i in 0 until plannedArr.length()) {
            val r = plannedArr.optJSONObject(i) ?: continue
            val d = try { ErpReports.toMinor(r.opt("debit_in_account_currency")) } catch (_: Exception) { 0L }
            val c = try { ErpReports.toMinor(r.opt("credit_in_account_currency")) } catch (_: Exception) { 0L }
            if (d == 0L && c == 0L) continue
            val acc = r.optString("account", "")
            val t = want.getOrPut(acc) { Tot() }
            t.debit += d
            t.credit += c
        }
        val got = HashMap<String, Tot>()
        for (r in s.accounts) {
            if (r.debitMinor == 0L && r.creditMinor == 0L) continue
            val acc = r.account ?: ""
            val t = got.getOrPut(acc) { Tot() }
            t.debit += r.debitMinor
            t.credit += r.creditMinor
        }
        if (want.isEmpty() || want != got) return false

        for (i in 0 until plannedArr.length()) {
            val r = plannedArr.optJSONObject(i) ?: continue
            val p = r.optString("party", "").takeIf { it.isNotEmpty() } ?: continue
            val acc = r.optString("account", "")
            val pt = r.optString("party_type", "").takeIf { it.isNotEmpty() }
            val matched = s.accounts.any { a ->
                a.account == acc && a.party == p && (pt == null || a.partyType == pt)
            }
            if (!matched) return false
        }
        return true
    }

    private data class Orphans(val items: List<OutboxEntry>, val docs: List<Pair<String, String>>)

    private suspend fun findOrphans(
        repo: Repo,
        erp: ErpOps,
        others: List<OutboxEntry>,
        txn: PendingTxn,
        keep: Set<String>,
    ): Orphans {
        val docs = mutableListOf<Pair<String, String>>()
        val seen = HashSet<String>(keep)
        val items = mutableListOf<OutboxEntry>()
        var foreign: Claims? = null
        val adopted = txn.createdDocs.filter { it.adopted }.mapTo(HashSet()) { docKey(it) }

        suspend fun removable(dt: String, name: String): Boolean {
            val k = docKey(dt, name)
            if (k in adopted) return false
            val f = foreign ?: claimsOfOtherTxns(repo, txn.id).also { foreign = it }
            return k !in f.docs
        }

        for (o in others) {
            val steps = o.plan.steps.mapIndexedNotNull { j, s ->
                if (s.kind == "create_doc" && (j < o.progress.stepIndex || j == o.progress.attempting)) j to s else null
            }
            if (steps.isEmpty()) continue
            items.add(o)
            for ((j, s) in steps) {
                foreign = null
                val ref = referenceOf(s) ?: continue
                val hits = erp.findDraftsByUtr(ref).filter { it.doctype == s.doctype && docKey(it) !in seen }
                val rec = if (j < o.progress.stepIndex) o.progress.created.getOrNull(j) else null
                var hit: ErpDocs.UtrHit? = null
                if (rec != null) {
                    if (rec.adopted) adopted.add(docKey(rec))
                    hit = hits.firstOrNull { it.name == rec.name }
                } else {
                    val body = applyRenames(s.bodyObject(), renamesOf(o))
                    for (h in hits) {
                        if (removable(h.doctype, h.name) && samePlanned(erp, h, s.doctype, body)) {
                            hit = h
                            break
                        }
                    }
                }
                if (hit == null) continue
                seen.add(docKey(hit))
                if (!removable(hit.doctype, hit.name)) continue
                if (hit.docstatus != 0) throw submittedError(hit.name)
                docs.add(hit.doctype to hit.name)
            }
        }

        foreign = null
        val recorded = txn.createdDocs.filter { docKey(it) !in seen && removable(it.doctype, it.name) }
        if (recorded.isNotEmpty()) {
            val st = erp.getDocStatuses(recorded.map { it.doctype to it.name })
            for (d in recorded) {
                val ds = st[docKey(d)]
                if (ds == null || ds == 2) continue
                if (ds != 0) throw submittedError(d.name)
                seen.add(docKey(d))
                docs.add(d.doctype to d.name)
            }
        }
        return Orphans(items, docs)
    }

    private suspend fun removeOrphans(repo: Repo, erp: ErpOps, orphans: Orphans) {
        for ((dt, name) in orphans.docs) {
            try {
                erp.deleteDraft(dt, name)
            } catch (e: NotDraftError) {
                if (e.docstatus == 2) continue
                throw submittedError(name)
            } catch (e: ErpValidationError) {
                if (e.status == 404) continue
                throw e
            }
        }
        for (it in orphans.items) {
            repo.markOutbox(it.id, progress = withoutDocs(it))
        }
    }

    suspend fun runPlan(
        txnId: String,
        plan: ActionPlan,
        repo: Repo,
        erp: ErpOps,
        userAction: Boolean = false,
    ): RunResult = withTxnLock(txnId) {
        runInternal(txnId, plan, repo, erp, userAction)
    }

    suspend fun parkForPermission(txnId: String, itemId: String, repo: Repo): Boolean = withTxnLock(txnId) {
        val item = repo.getOutbox(itemId) ?: return@withTxnLock false
        if (item.txnId != txnId || item.status != OutboxStatus.PENDING) return@withTxnLock false
        val txn = repo.getPending(txnId) ?: return@withTxnLock false
        if (txn.state != TxnState.NEW) return@withTxnLock false
        repo.markOutbox(itemId, status = OutboxStatus.FAILED)
        repo.upsertPending(txn.copy(error = PERMISSION_DENIED_MESSAGE))
        true
    }

    private suspend fun runInternal(
        txnId: String,
        plan: ActionPlan,
        repo: Repo,
        erp: ErpOps,
        user: Boolean,
    ): RunResult {
        var txn = repo.getPending(txnId) ?: throw IllegalStateException("Transaction $txnId not found")
        val id = outboxIdFor(txnId, plan)
        val stored = repo.getOutbox(id)

        if (!user && (stored == null || stored.status != OutboxStatus.PENDING)) {
            return RunResult.Failed(replacedError(), retryable = false)
        }

        if (txn.state == TxnState.CREATED || txn.state == TxnState.SUBMITTED) {
            val mine = stored != null &&
                plan.steps.isNotEmpty() &&
                stored.progress.stepIndex == plan.steps.size &&
                txn.createdDocs.all { d -> stored.progress.created.any { it.doctype == d.doctype && it.name == d.name } }
            if (mine) {
                repo.finishOutbox(id, stored.progress)
                return RunResult.Created(txn.createdDocs.map { it.doctype to it.name })
            }
            if (stored != null && stored.status == OutboxStatus.PENDING) {
                repo.markOutbox(id, status = OutboxStatus.FAILED)
            }
            val names = txn.createdDocs.joinToString(", ") { it.name }
            return RunResult.Failed(
                ErpValidationError("This transaction was already processed ($names); use Undo in Activity to change it.", 0),
                retryable = false,
            )
        }

        if (txn.state != TxnState.NEW) {
            if (txn.state == TxnState.IGNORED && plan.steps.isEmpty() && !plan.needsInrAmount) {
                if (stored != null) repo.finishOutbox(id, stored.progress)
                return RunResult.Created(emptyList())
            }
            repo.markOutbox(id, status = OutboxStatus.SUPERSEDED)
            val msg = "This transaction is already ${if (txn.state == TxnState.IGNORED) "ignored" else "handled"}, so the queued entry was not created."
            return RunResult.Failed(ErpValidationError(msg, 0), retryable = false)
        }

        if (user && stored == null) {
            repo.enqueue(
                OutboxEntry(
                    id = id,
                    txnId = txnId,
                    plan = plan,
                    progress = OutboxProgress(),
                    attempts = 0,
                    status = OutboxStatus.PENDING,
                )
            )
        } else if (user && stored != null && stored.status != OutboxStatus.PENDING) {
            repo.reopenOutbox(id)
        }

        val initialProgress = stored?.progress ?: OutboxProgress()
        val initialAttempts = stored?.attempts ?: 0

        val bad = checkPlan(plan)
        if (bad != null) {
            repo.markOutbox(id, progress = initialProgress, attempts = initialAttempts + 1, status = OutboxStatus.FAILED)
            writeTxn(repo, txnId, txn, TxnState.NEW, txn.createdDocs, bad)
            return RunResult.Failed(ErpValidationError(bad, 0), retryable = false)
        }

        try {
            val keep = initialProgress.created.mapTo(HashSet()) { docKey(it) }
            val others = repo.listOutbox(txnId).filter { it.id != id && it.status != OutboxStatus.DONE }
            val orphans = findOrphans(repo, erp, others, txn, keep)
            removeOrphans(repo, erp, orphans)
            val keptDocs = txn.createdDocs.filter { docKey(it) in keep }
            if (keptDocs.size != txn.createdDocs.size) {
                writeTxn(repo, txnId, txn, txn.state, keptDocs, txn.error)
                txn = txn.copy(createdDocs = keptDocs)
            }
        } catch (e: ErpAuthError) {
            return RunResult.NeedsLogin
        } catch (e: ErpValidationError) {
            repo.markOutbox(id, status = OutboxStatus.FAILED)
            return RunResult.Failed(e, retryable = false)
        } catch (e: Throwable) {
            val err = e as? ErpNetworkError ?: ErpNetworkError("Something went wrong while creating the entry. It will be retried.")
            repo.markOutbox(id, attempts = initialAttempts + 1)
            return RunResult.Failed(err, retryable = true)
        }

        if (plan.steps.isEmpty()) {
            writeTxn(repo, txnId, txn, TxnState.IGNORED, txn.createdDocs, null)
            repo.finishOutbox(id, OutboxProgress())
            return RunResult.Created(emptyList())
        }

        val createdList = ArrayList<ProgressDoc>()
        val docs = ArrayList<CreatedDoc>()
        val madeHere = ArrayList<CreatedDoc>()
        val renames = HashMap<String, String>()
        var progress = OutboxProgress(stepIndex = 0, created = emptyList(), attempting = initialProgress.attempting)

        fun markerAfter(idx: Int): Int? =
            if (initialProgress.attempting != null && initialProgress.attempting > idx) initialProgress.attempting else null

        suspend fun attempt(stepIdx: Int) {
            progress = progress.copy(attempting = stepIdx)
            repo.markOutbox(id, progress = progress, attempts = initialAttempts)
        }

        suspend fun <T> definitive(block: suspend () -> T): T {
            try {
                return block()
            } catch (e: Throwable) {
                if (e is ErpValidationError || e is ErpAuthError) {
                    progress = progress.copy(attempting = null)
                }
                throw e
            }
        }

        data class HitChoice(val hit: ErpDocs.UtrHit, val shared: Boolean = false, val adopted: Boolean = false, val block: Boolean = false)

        suspend fun chooseHit(hits: List<ErpDocs.UtrHit>, step: PlanStep, at: Int): HitChoice? {
            val rec = if (at < initialProgress.stepIndex) initialProgress.created.getOrNull(at) else null
            val mine = rec?.let { r -> hits.firstOrNull { it.doctype == r.doctype && it.name == r.name } }
            if (mine != null) return HitChoice(hit = mine)
            if (hits.isEmpty()) return null
            val ownMarker = initialProgress.attempting != null && initialProgress.attempting >= at
            val ref = referenceOf(step) ?: return null
            val body = applyRenames(step.bodyObject(), renames)
            val foreign = claimsOfOtherTxns(repo, txnId)
            var blocker: ErpDocs.UtrHit? = null
            for (h in hits) {
                if (docKey(h) in foreign.docs) {
                    if (h.doctype == step.doctype && sameTransfer(erp, h, body)) {
                        return HitChoice(hit = h, shared = true)
                    }
                    continue
                }
                val contested = claimKey(h.doctype, ref) in foreign.attempting
                if (samePlanned(erp, h, step.doctype, body)) {
                    return HitChoice(hit = h, adopted = contested || !ownMarker)
                }
                if (!contested && blocker == null) blocker = h
            }
            return blocker?.let { HitChoice(hit = it, block = true) }
        }

        var stepIdx = 0
        try {
            while (stepIdx < plan.steps.size) {
                val step = plan.steps[stepIdx]
                if (step.kind == "create_party") {
                    val dt = PartyDoctype.fromWire(step.doctype)
                    val refName = referencedPartyName(plan, stepIdx)
                    val fromProg = if (stepIdx < initialProgress.stepIndex) initialProgress.created.getOrNull(stepIdx) else null
                    var partyRef: PartyRef? = if (fromProg != null && fromProg.doctype == step.doctype) PartyRef(dt, fromProg.name) else null
                    val q = displayNameOf(step) ?: refName
                    if (partyRef == null && q != null && initialProgress.attempting != null && initialProgress.attempting >= stepIdx) {
                        val hits = erp.searchParties(dt, q, exact = true)
                            .filter { it.ref.doctype == dt && norm(it.displayName) == norm(q) }
                        if (hits.size > 1) {
                            throw ErpValidationError("More than one ${step.doctype} is named $q; pick the right one in ERPNext.", 0)
                        }
                        if (hits.size == 1) partyRef = hits[0].ref
                    }
                    if (partyRef == null) {
                        attempt(stepIdx)
                        partyRef = definitive { erp.createParty(dt, step.bodyObject()) }
                    }
                    if (refName != null && partyRef.name != refName) {
                        renames["${dt.wire}\u0000$refName"] = partyRef.name
                    }
                    createdList.add(ProgressDoc(partyRef.doctype.wire, partyRef.name))
                } else {
                    val ref = referenceOf(step)!!
                    val hits = erp.findDraftsByUtr(ref)
                    val choice = chooseHit(hits, step, stepIdx)
                    val doc: CreatedDoc
                    if (choice != null) {
                        if (choice.block) {
                            throw ErpValidationError(
                                "An entry ${choice.hit.name} (${choice.hit.doctype}) already exists for this reference. Open it in ERPNext and check it, or ask your accountant.",
                                0,
                            )
                        }
                        val rec = if (stepIdx < initialProgress.stepIndex) initialProgress.created.getOrNull(stepIdx) else null
                        val ours = !choice.shared && !choice.adopted && (
                            if (rec != null && rec.doctype == choice.hit.doctype && rec.name == choice.hit.name) !rec.adopted
                            else initialProgress.attempting != null && initialProgress.attempting >= stepIdx
                        )
                        doc = CreatedDoc(choice.hit.doctype, choice.hit.name, choice.hit.docstatus, adopted = !ours)
                    } else {
                        val body = applyRenames(step.bodyObject(), renames)
                        body.remove("docstatus")
                        attempt(stepIdx)
                        val name = definitive { erp.createDraft(step.doctype, body) }
                        doc = CreatedDoc(step.doctype, name, docstatus = 0, adopted = false)
                        madeHere.add(doc)
                    }
                    docs.add(doc)
                    createdList.add(ProgressDoc(doc.doctype, doc.name, adopted = doc.adopted))
                }
                progress = OutboxProgress(
                    stepIndex = stepIdx + 1,
                    created = createdList.toList(),
                    attempting = markerAfter(stepIdx),
                )
                repo.markOutbox(id, progress = progress, attempts = initialAttempts)
                stepIdx++
            }
        } catch (e: Throwable) {
            val best = if (progress.stepIndex >= initialProgress.stepIndex) progress else initialProgress
            if (e is ErpAuthError) {
                repo.markOutbox(id, progress = best, attempts = initialAttempts)
                if (docs.isNotEmpty()) writeTxn(repo, txnId, txn, TxnState.NEW, docs, txn.error)
                return RunResult.NeedsLogin
            }
            if (e is ErpValidationError) {
                val notes = mutableListOf(describeFailure(e, plan.steps[stepIdx]))
                var keep = progress.stepIndex
                for (d in madeHere.reversed()) {
                    try {
                        erp.deleteDraft(d.doctype, d.name)
                        docs.remove(d)
                        val at = createdList.indexOfFirst { it.doctype == d.doctype && it.name == d.name }
                        if (at >= 0) keep = minOf(keep, at)
                    } catch (e2: Throwable) {
                        val why = if (e2 is ErpAuthError || e2 is ErpValidationError || e2 is ErpNetworkError || e2 is NotDraftError) {
                            e2.message ?: "It could not be removed."
                        } else {
                            "It could not be removed."
                        }
                        notes.add("The draft ${d.doctype} ${d.name} was not removed: $why Delete or cancel it in ERPNext.")
                    }
                }
                val kept = if (madeHere.isNotEmpty()) {
                    OutboxProgress(keep, createdList.take(keep), progress.attempting)
                } else {
                    best
                }
                repo.markOutbox(id, progress = kept, attempts = initialAttempts + 1, status = OutboxStatus.FAILED)
                writeTxn(repo, txnId, txn, TxnState.NEW, docs, notes.joinToString(" "))
                return RunResult.Failed(e, retryable = false)
            }
            val netErr = e as? ErpNetworkError
                ?: ErpNetworkError("Something went wrong while creating the entry. It will be retried.")
            repo.markOutbox(id, progress = best, attempts = initialAttempts + 1)
            if (docs.isNotEmpty()) writeTxn(repo, txnId, txn, TxnState.NEW, docs, txn.error)
            return RunResult.Failed(netErr, retryable = true)
        }

        writeTxn(repo, txnId, txn, TxnState.CREATED, docs, null)
        repo.finishOutbox(id, progress)
        return RunResult.Created(docs.map { it.doctype to it.name })
    }

    suspend fun splitRemovable(
        repo: Repo,
        txn: PendingTxn,
        docs: List<CreatedDoc> = txn.createdDocs,
    ): Pair<List<CreatedDoc>, List<CreatedDoc>> {
        val elsewhere = HashSet<String>()
        for (t in repo.listPending()) {
            if (t.id != txn.id) {
                t.createdDocs.forEach { elsewhere.add(docKey(it)) }
            }
        }
        val remove = mutableListOf<CreatedDoc>()
        val keep = mutableListOf<CreatedDoc>()
        for (d in docs) {
            if (d.adopted || docKey(d) in elsewhere) keep.add(d) else remove.add(d)
        }
        return remove to keep
    }

    suspend fun undoTxn(txnId: String, repo: Repo, erp: ErpOps): UndoResult = withTxnLock(txnId) {
        val txn = repo.getPending(txnId)
            ?: return@withTxnLock UndoResult.Refused("not_found", "This entry was not found on this phone.")
        if (txn.state != TxnState.CREATED) {
            return@withTxnLock UndoResult.Refused(
                "not_created",
                "This entry is not waiting for your accountant, so there is nothing to undo.",
            )
        }

        suspend fun refusedSubmitted(docs: List<CreatedDoc>): UndoResult {
            writeTxn(repo, txnId, txn, TxnState.SUBMITTED, docs, null)
            return UndoResult.Refused("submitted", SUBMITTED_UNDO_MESSAGE)
        }

        fun failure(e: Throwable): UndoResult {
            if (e is ErpAuthError) {
                if (e.status == 403) {
                    return UndoResult.Failed(ErpValidationError(UNDO_FORBIDDEN_MESSAGE, 403), retryable = false)
                }
                return UndoResult.NeedsLogin
            }
            if (e is ErpValidationError) return UndoResult.Failed(e, retryable = false)
            val net = e as? ErpNetworkError
                ?: ErpNetworkError("Something went wrong while removing the draft. Nothing was changed.")
            return UndoResult.Failed(net, retryable = true)
        }

        var docs = txn.createdDocs
        if (docs.isNotEmpty()) {
            val st = try {
                erp.getDocStatuses(docs.map { it.doctype to it.name })
            } catch (e: Throwable) {
                return@withTxnLock failure(e)
            }
            docs = docs.map { d -> d.copy(docstatus = st[docKey(d)] ?: -1) }
        }
        if (docs.any { it.docstatus == 1 }) return@withTxnLock refusedSubmitted(docs)

        val present = docs.filter { it.docstatus == 0 }
        val (remove, keep) = splitRemovable(repo, txn, present)
        var remaining = remove
        for (d in remove.reversed()) {
            try {
                erp.deleteDraft(d.doctype, d.name)
            } catch (e: NotDraftError) {
                if (e.docstatus != 2) {
                    remaining = remaining.map { if (it === d) it.copy(docstatus = e.docstatus) else it }
                    return@withTxnLock refusedSubmitted(remaining)
                }
            } catch (e: Throwable) {
                if (e is ErpValidationError && e.status == 404) {
                    // already deleted
                } else {
                    if (e is ErpValidationError) {
                        try {
                            val now = erp.getDocStatuses(listOf(d.doctype to d.name))[docKey(d)]
                            if (now == 1) {
                                return@withTxnLock refusedSubmitted(
                                    remaining.map { if (it === d) it.copy(docstatus = 1) else it }
                                )
                            }
                        } catch (_: Throwable) {
                            // fall through to failure(e)
                        }
                    }
                    writeTxn(repo, txnId, txn, TxnState.CREATED, remaining, txn.error)
                    return@withTxnLock failure(e)
                }
            }
            remaining = remaining.filter { it !== d }
        }

        for (it in repo.listOutbox(txnId)) {
            if (it.status != OutboxStatus.SUPERSEDED) {
                repo.markOutbox(it.id, progress = withoutDocs(it), status = OutboxStatus.SUPERSEDED)
            }
        }
        writeTxn(repo, txnId, txn, TxnState.NEW, emptyList(), null)
        UndoResult.Undone(keep.map { it.doctype to it.name })
    }
}
