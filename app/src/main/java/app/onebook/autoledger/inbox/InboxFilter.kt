package app.onebook.autoledger.inbox

import app.onebook.autoledger.core.Direction
import app.onebook.autoledger.core.PendingTxn

enum class ActivityPeriod {
    THIS_MONTH,
    LAST_MONTH,
    THIS_FY,
    ALL,
}

enum class InboxKind {
    BANK,
    UPI,
    CARD,
}

data class InboxFilter(
    val query: String = "",
    val period: ActivityPeriod = ActivityPeriod.ALL,
    val direction: Direction? = null,
    val accountLast4: String? = null,
    val kind: InboxKind? = null,
)

object InboxFilters {
    private fun pad2(n: Int): String = n.toString().padStart(2, '0')

    fun periodRange(period: ActivityPeriod, today: String): Pair<String, String>? {
        if (period == ActivityPeriod.ALL) return null
        val y = today.substring(0, 4).toInt()
        val m = today.substring(5, 7).toInt()
        return when (period) {
            ActivityPeriod.THIS_MONTH -> {
                val end = if (m == 12) "${y + 1}-01-01" else "$y-${pad2(m + 1)}-01"
                "$y-${pad2(m)}-01" to end
            }
            ActivityPeriod.LAST_MONTH -> {
                val (py, pm) = if (m == 1) (y - 1) to 12 else y to (m - 1)
                "$py-${pad2(pm)}-01" to "$y-${pad2(m)}-01"
            }
            ActivityPeriod.THIS_FY -> {
                val fy = if (m >= 4) y else y - 1
                "$fy-04-01" to "${fy + 1}-04-01"
            }
            ActivityPeriod.ALL -> null
        }
    }

    fun searchText(t: PendingTxn): String = buildList {
        t.counterparty?.takeIf { it.isNotBlank() }?.let(::add)
        t.merchant?.takeIf { it.isNotBlank() }?.let(::add)
        add(t.sender)
        t.utr?.takeIf { it.isNotBlank() }?.let(::add)
        t.createdDocs.forEach { add(it.name) }
    }.joinToString("\n").lowercase()

    fun filterPending(items: List<PendingTxn>, f: InboxFilter, today: String): List<PendingTxn> {
        val q = f.query.trim().lowercase()
        val range = periodRange(f.period, today)
        if (q.isEmpty() && range == null && f.direction == null && f.accountLast4 == null && f.kind == null) {
            return items
        }
        return items.filter { t ->
            if (range != null && (t.occurredOn < range.first || t.occurredOn >= range.second)) return@filter false
            if (f.direction != null && t.direction != f.direction) return@filter false
            if (f.accountLast4 != null && t.accountLast4 != f.accountLast4) return@filter false
            if (f.kind != null && InboxModelBuilder.kindIconOf(t) != f.kind) return@filter false
            q.isEmpty() || searchText(t).contains(q)
        }
    }

    fun activeInboxFilterCount(f: InboxFilter): Int =
        (if (f.direction != null) 1 else 0) +
            (if (f.accountLast4 != null) 1 else 0) +
            (if (f.kind != null) 1 else 0)
}
