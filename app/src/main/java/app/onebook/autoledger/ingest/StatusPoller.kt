package app.onebook.autoledger.ingest

import app.onebook.autoledger.core.TxnState
import app.onebook.autoledger.data.Repo
import app.onebook.autoledger.erp.ErpAuthError
import app.onebook.autoledger.erp.ErpOps
import app.onebook.autoledger.outbox.OutboxRunner

data class PollResult(
    val checked: Int = 0,
    val submitted: Int = 0,
    val cancelled: Int = 0,
    val missing: Int = 0,
    val needsLogin: Boolean = false,
)

object StatusPoller {
    const val DOC_MISSING = -1

    private fun key(doctype: String, name: String) = "$doctype:$name"

    suspend fun pollStatuses(
        repo: Repo,
        erp: ErpOps,
        now: () -> Long = System::currentTimeMillis,
    ): PollResult {
        var checked = 0
        var submitted = 0
        var cancelled = 0
        var missing = 0
        var needsLogin = false
        try {
            val waiting = repo.listPending(listOf(TxnState.CREATED)).filter { it.createdDocs.isNotEmpty() }
            if (waiting.isEmpty()) return PollResult()
            val refs = waiting.flatMap { t -> t.createdDocs.map { it.doctype to it.name } }
            val statuses = erp.getDocStatuses(refs)
            for (t in waiting) {
                try {
                    val asked = t.createdDocs.mapTo(HashSet()) { key(it.doctype, it.name) }
                    val applied = OutboxRunner.withTxnLock(t.id) { apply(repo, t.id, statuses, asked) }
                        ?: continue
                    checked += 1
                    if (applied.submitted) submitted += 1
                    cancelled += applied.cancelled
                    missing += applied.missing
                } catch (_: Throwable) {
                    // Local write failure on one txn must not block the rest.
                }
            }
        } catch (e: Throwable) {
            if (e is ErpAuthError && e.status == 401) {
                needsLogin = true
                try {
                    if (repo.getSetting(OutboxDrainer.NEEDS_LOGIN_SINCE_KEY).isNullOrEmpty()) {
                        repo.setSetting(OutboxDrainer.NEEDS_LOGIN_SINCE_KEY, now().toString())
                    }
                } catch (_: Throwable) {
                    // Best effort
                }
            }
        }
        return PollResult(checked, submitted, cancelled, missing, needsLogin)
    }

    private data class Applied(val submitted: Boolean, val cancelled: Int, val missing: Int)

    private suspend fun apply(
        repo: Repo,
        id: String,
        statuses: Map<String, Int>,
        asked: Set<String>,
    ): Applied? {
        val cur = repo.getPending(id) ?: return null
        if (cur.state != TxnState.CREATED || cur.createdDocs.isEmpty()) return null
        if (cur.createdDocs.size != asked.size || cur.createdDocs.any { key(it.doctype, it.name) !in asked }) {
            return null
        }
        var cancelled = 0
        var missing = 0
        var changed = false
        val docs = cur.createdDocs.map { d ->
            val ds = statuses[key(d.doctype, d.name)] ?: DOC_MISSING
            if (ds == 2 && d.docstatus != 2) cancelled += 1
            if (ds == DOC_MISSING && d.docstatus != DOC_MISSING) missing += 1
            if (ds != d.docstatus) {
                changed = true
                d.copy(docstatus = ds)
            } else {
                d
            }
        }
        val allSubmitted = docs.all { it.docstatus == 1 }
        if (allSubmitted || changed) {
            repo.upsertPending(
                cur.copy(
                    createdDocs = docs,
                    state = if (allSubmitted) TxnState.SUBMITTED else cur.state,
                )
            )
        }
        return Applied(allSubmitted, cancelled, missing)
    }

    suspend fun bringBack(txnId: String, repo: Repo): Boolean = OutboxRunner.withTxnLock(txnId) {
        val cur = repo.getPending(txnId) ?: return@withTxnLock false
        if (cur.state != TxnState.IGNORED) return@withTxnLock false
        repo.upsertPending(cur.copy(state = TxnState.NEW, error = null))
        true
    }
}
