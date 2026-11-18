package app.onebook.autoledger.ingest

import app.onebook.autoledger.core.PendingTxn
import app.onebook.autoledger.core.SimilarEngine
import app.onebook.autoledger.core.TxnState
import app.onebook.autoledger.data.Repo
import app.onebook.autoledger.erp.ErpClient
import app.onebook.autoledger.erp.ErpDocs
import org.json.JSONObject
import java.time.LocalDate

data class DupFlag(
    val doctype: String,
    val name: String,
    val strength: SimilarEngine.MatchStrength,
    val why: SimilarEngine.MatchWhy,
) {
    fun toJson(): String = JSONObject()
        .put("doctype", doctype)
        .put("name", name)
        .put("strength", if (strength == SimilarEngine.MatchStrength.STRONG) "strong" else "weak")
        .put("why", if (why == SimilarEngine.MatchWhy.REFERENCE) "reference" else "details")
        .toString()

    companion object {
        fun parse(v: String?): DupFlag? {
            if (v.isNullOrBlank()) return null
            return try {
                val o = JSONObject(v)
                val doctype = o.optString("doctype", "")
                val name = o.optString("name", "")
                if (doctype.isEmpty() || name.isEmpty()) return null
                val strength = when (o.optString("strength", "")) {
                    "strong" -> SimilarEngine.MatchStrength.STRONG
                    "weak" -> SimilarEngine.MatchStrength.WEAK
                    else -> return null
                }
                val why = when (if (o.has("why")) o.optString("why", "") else "details") {
                    "reference" -> SimilarEngine.MatchWhy.REFERENCE
                    "details" -> SimilarEngine.MatchWhy.DETAILS
                    else -> return null
                }
                DupFlag(doctype, name, strength, why)
            } catch (_: Throwable) {
                null
            }
        }
    }
}

object DupCheck {
    const val DUP_KEY_PREFIX = "possible_duplicate:"
    const val COMPANY_SETTING = "company"
    const val CHECKED_FROM_KEY = "possible_duplicate_checked_from"

    fun dupKey(txnId: String) = "$DUP_KEY_PREFIX$txnId"
    private fun docKey(doctype: String, name: String) = "$doctype\u0000$name"
    private fun isoOfDay(day: Long): String = LocalDate.ofEpochDay(day).toString()

    suspend fun runDupCheck(repo: Repo, client: ErpClient): Boolean = runDupCheck(repo) { company, from, to ->
        ErpDocs.readBookedEntries(client, company, from, to)
    }

    suspend fun runDupCheck(
        repo: Repo,
        readBooked: suspend (company: String, from: String, to: String) -> ErpDocs.ReadBookedResult,
    ): Boolean {
        return try {
            val queued = repo.listQueuedTxnIds().toHashSet()
            val all = repo.listPending()
            val pending = all.filter { it.state == TxnState.NEW && it.id !in queued }
            val ids = pending.mapTo(HashSet()) { it.id }
            val stored = HashMap<String, String>()
            for ((k, v) in repo.listSettings(DUP_KEY_PREFIX)) {
                val id = k.removePrefix(DUP_KEY_PREFIX)
                if (id in ids) stored[id] = v else repo.deleteSetting(k)
            }
            if (pending.isEmpty()) return true
            val company = repo.getSetting(COMPANY_SETTING)
            if (company.isNullOrBlank()) return false

            val days = pending.mapNotNull { SimilarEngine.dayNumber(it.occurredOn) }
            if (days.isEmpty()) return false
            val from = isoOfDay(days.min() - SimilarEngine.SIMILAR_DAYS)
            val to = isoOfDay(days.max() + SimilarEngine.SIMILAR_DAYS)
            val read = readBooked(company, from, to)
            try {
                if (read.checkedFrom != null) repo.setSetting(CHECKED_FROM_KEY, read.checkedFrom)
                else repo.deleteSetting(CHECKED_FROM_KEY)
            } catch (_: Throwable) {
                // Best effort
            }

            val recordedBy = HashMap<String, MutableList<PendingTxn>>()
            for (t in all) {
                for (d in t.createdDocs) {
                    recordedBy.getOrPut(docKey(d.doctype, d.name)) { mutableListOf() }.add(t)
                }
            }

            val index = SimilarEngine.indexBooked(read.entries)
            var ok = true
            for (t in pending) {
                val otherHalf: (SimilarEngine.BookedEntry) -> Boolean = { e ->
                    recordedBy[docKey(e.doctype, e.name)].orEmpty().any { r ->
                        r.id != t.id && r.direction != t.direction && r.amountMinor == t.amountMinor
                    }
                }
                val hit = SimilarEngine.findSimilarIn(t, index, otherHalf)
                val key = dupKey(t.id)
                try {
                    if (hit == null) {
                        if (t.id in stored) repo.deleteSetting(key)
                        continue
                    }
                    val value = DupFlag(hit.entry.doctype, hit.entry.name, hit.strength, hit.why).toJson()
                    if (stored[t.id] != value) repo.setSetting(key, value)
                } catch (_: Throwable) {
                    ok = false
                }
            }
            ok
        } catch (_: Throwable) {
            false
        }
    }
}
