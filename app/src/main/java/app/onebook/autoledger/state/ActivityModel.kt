package app.onebook.autoledger.state

import app.onebook.autoledger.core.CreatedDoc
import app.onebook.autoledger.core.Direction
import app.onebook.autoledger.core.PendingTxn
import app.onebook.autoledger.core.TxnState
import app.onebook.autoledger.erp.ErpHttp
import app.onebook.autoledger.inbox.ActivityPeriod
import app.onebook.autoledger.inbox.InboxFilters
import app.onebook.autoledger.ingest.AutoDoneMark
import app.onebook.autoledger.ingest.StatusPoller
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class StatusTone {
    OK,
    INFO,
    WARN,
    ERROR,
    NEUTRAL,
}

data class RowStatus(
    val label: String,
    val tone: StatusTone,
    val hint: String? = null,
)

enum class ActivitySort {
    NEWEST,
    OLDEST,
    AMOUNT_HIGH,
    AMOUNT_LOW,
    NAME_AZ,
}

enum class ActivityStatus(val state: TxnState) {
    WAITING(TxnState.CREATED),
    SUBMITTED(TxnState.SUBMITTED),
    IGNORED(TxnState.IGNORED),
}

data class ActivityFilter(
    val query: String = "",
    val period: ActivityPeriod = ActivityPeriod.THIS_MONTH,
    val direction: Direction? = null,
    val accountLast4: String? = null,
    val status: ActivityStatus? = null,
)

data class RemovalPlan(
    val remove: List<CreatedDoc>,
    val keep: List<CreatedDoc>,
)

data class ConfirmDialogText(
    val title: String,
    val message: String,
)

data class ActivityTotals(
    val inMinor: Long,
    val outMinor: Long,
    val netMinor: Long,
    val foreignCount: Int,
)

enum class RecencyTitle(val label: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    THIS_WEEK("This week"),
    EARLIER("Earlier"),
}

data class RecencyGroup(
    val title: RecencyTitle,
    val rows: List<PendingTxn>,
)

object ActivityModel {
    const val PAGE_SIZE = 100
    const val AUTO_DONE_LABEL = "Done automatically"
    const val UNDO_CONFIRM_TITLE = "Undo this entry?"
    const val UNDO_CONFIRM_MESSAGE = "Remove the draft from ERPNext and put this message back in your Inbox?"
    const val KEEP_ADOPTED_MESSAGE =
        "This entry was already in ERPNext before this app recorded it. It stays there; only the link to this message is removed."
    const val KEEP_SHARED_MESSAGE =
        "This entry is also linked to another message. It stays in ERPNext; only the link to this message is removed."

    fun docUrl(siteUrl: String, doctype: String, name: String): String {
        val base = siteUrl.trimEnd('/')
        val slug = doctype.trim().lowercase().replace(Regex("\\s+"), "-")
        return "$base/app/$slug/${ErpHttp.encodeSegment(name)}"
    }

    fun statusOf(t: PendingTxn): RowStatus {
        if (t.state == TxnState.IGNORED) return RowStatus("Ignored", StatusTone.NEUTRAL)
        if (t.state == TxnState.SUBMITTED) return RowStatus("Submitted", StatusTone.OK)
        val ds = t.createdDocs.map { it.docstatus }
        if (1 in ds) {
            return RowStatus(
                "Partly submitted",
                StatusTone.INFO,
                "Your accountant submitted some of these entries. Undo is no longer possible.",
            )
        }
        if (2 in ds) {
            return RowStatus(
                "Cancelled in ERPNext",
                StatusTone.ERROR,
                "The draft was cancelled in ERPNext. Put this message back in your Inbox to answer it again.",
            )
        }
        if (StatusPoller.DOC_MISSING in ds) {
            return RowStatus(
                "Not found in ERPNext",
                StatusTone.WARN,
                "ERPNext no longer shows this entry. It may have been deleted, or your user may not be allowed to see it.",
            )
        }
        return RowStatus("Draft", StatusTone.INFO)
    }

    fun autoChip(t: PendingTxn, marks: Map<String, AutoDoneMark>): RowStatus? {
        if (t.state != TxnState.CREATED && t.state != TxnState.SUBMITTED && t.state != TxnState.IGNORED) return null
        val m = marks[t.id] ?: return null
        val mine = when {
            m.docs.isNotEmpty() -> t.createdDocs.any { it.name in m.docs }
            m.queued -> t.state != TxnState.IGNORED
            else -> t.state == TxnState.IGNORED
        }
        return if (mine) RowStatus(AUTO_DONE_LABEL, StatusTone.NEUTRAL) else null
    }

    fun canUndo(t: PendingTxn): Boolean =
        t.state == TxnState.CREATED && t.createdDocs.isNotEmpty() && t.createdDocs.all { it.docstatus == 0 }

    fun canPutBack(t: PendingTxn): Boolean =
        t.state == TxnState.CREATED &&
            t.createdDocs.any { it.docstatus == 2 || it.docstatus == StatusPoller.DOC_MISSING } &&
            t.createdDocs.none { it.docstatus == 1 }

    fun titleOf(t: PendingTxn): String =
        t.counterparty?.trim()?.takeIf { it.isNotEmpty() }
            ?: t.merchant?.trim()?.takeIf { it.isNotEmpty() }
            ?: t.sender

    fun removalPlan(t: PendingTxn, all: List<PendingTxn>): RemovalPlan {
        val elsewhere = all.asSequence()
            .filter { it.id != t.id }
            .flatMap { it.createdDocs.asSequence() }
            .mapTo(HashSet()) { "${it.doctype}:${it.name}" }
        val drafts = t.createdDocs.filter { it.docstatus == 0 }
        val isKept: (CreatedDoc) -> Boolean = { d -> d.adopted || "${d.doctype}:${d.name}" in elsewhere }
        return RemovalPlan(
            remove = drafts.filter { !isKept(it) },
            keep = drafts.filter { isKept(it) },
        )
    }

    fun confirmText(plan: RemovalPlan): ConfirmDialogText? {
        if (plan.remove.isEmpty() && plan.keep.isEmpty()) return null
        val keepMsg = if (plan.keep.any { it.adopted }) KEEP_ADOPTED_MESSAGE else KEEP_SHARED_MESSAGE
        if (plan.keep.isEmpty()) return ConfirmDialogText(UNDO_CONFIRM_TITLE, UNDO_CONFIRM_MESSAGE)
        if (plan.remove.isEmpty()) return ConfirmDialogText("Put this message back in your Inbox?", keepMsg)
        val names = plan.keep.joinToString(", ") { it.name }
        return ConfirmDialogText(UNDO_CONFIRM_TITLE, "$UNDO_CONFIRM_MESSAGE $names: $keepMsg")
    }

    fun filterActivity(items: List<PendingTxn>, f: ActivityFilter, today: String): List<PendingTxn> {
        val q = f.query.trim().lowercase()
        val range = InboxFilters.periodRange(f.period, today)
        return items.filter { t ->
            if (range != null && (t.occurredOn < range.first || t.occurredOn >= range.second)) return@filter false
            if (f.direction != null && t.direction != f.direction) return@filter false
            if (f.status != null && t.state != f.status.state) return@filter false
            if (f.accountLast4 != null && t.accountLast4 != f.accountLast4) return@filter false
            q.isEmpty() || InboxFilters.searchText(t).contains(q)
        }
    }

    fun waitingCount(items: List<PendingTxn>, f: ActivityFilter, today: String): Int =
        filterActivity(items, f.copy(status = null), today).count { t ->
            t.state == TxnState.CREATED && t.createdDocs.none { it.docstatus == 2 || it.docstatus == StatusPoller.DOC_MISSING }
        }

    fun totalsOf(items: List<PendingTxn>): ActivityTotals {
        var inMinor = 0L
        var outMinor = 0L
        var foreignCount = 0
        for (t in items) {
            if (t.state == TxnState.IGNORED || t.createdDocs.any { it.docstatus == 2 || it.docstatus == StatusPoller.DOC_MISSING }) {
                continue
            }
            val cur = t.currency.ifBlank { "INR" }.uppercase()
            if (cur != "INR") {
                foreignCount++
            } else if (t.direction == Direction.IN) {
                inMinor += t.amountMinor
            } else {
                outMinor += t.amountMinor
            }
        }
        return ActivityTotals(inMinor, outMinor, inMinor - outMinor, foreignCount)
    }

    private fun epochDay(iso: String): Long = try {
        LocalDate.parse(iso).toEpochDay()
    } catch (_: Exception) {
        0L
    }

    fun groupByRecency(items: List<PendingTxn>, today: String): List<RecencyGroup> {
        val t0 = epochDay(today)
        val buckets = RecencyTitle.entries.associateWith { mutableListOf<PendingTxn>() }
        for (t in items) {
            val age = t0 - epochDay(t.occurredOn)
            val title = when {
                age <= 0L -> RecencyTitle.TODAY
                age == 1L -> RecencyTitle.YESTERDAY
                age <= 6L -> RecencyTitle.THIS_WEEK
                else -> RecencyTitle.EARLIER
            }
            buckets[title]!!.add(t)
        }
        return RecencyTitle.entries.mapNotNull { k ->
            val rows = buckets[k]!!
            if (rows.isEmpty()) null else RecencyGroup(k, rows)
        }
    }

    private val newestCmp = Comparator<PendingTxn> { a, b ->
        val dateCmp = b.occurredOn.compareTo(a.occurredOn)
        if (dateCmp != 0) return@Comparator dateCmp
        val recCmp = b.receivedAt.compareTo(a.receivedAt)
        if (recCmp != 0) return@Comparator recCmp
        if (a.id < b.id) 1 else if (a.id > b.id) -1 else 0
    }

    fun sortActivity(items: List<PendingTxn>, by: ActivitySort): List<PendingTxn> = when (by) {
        ActivitySort.NEWEST -> items.sortedWith(newestCmp)
        ActivitySort.OLDEST -> items.sortedWith(newestCmp.reversed())
        ActivitySort.AMOUNT_HIGH -> items.sortedWith { a, b ->
            val cmp = b.amountMinor.compareTo(a.amountMinor)
            if (cmp != 0) cmp else newestCmp.compare(a, b)
        }
        ActivitySort.AMOUNT_LOW -> items.sortedWith { a, b ->
            val cmp = a.amountMinor.compareTo(b.amountMinor)
            if (cmp != 0) cmp else newestCmp.compare(a, b)
        }
        ActivitySort.NAME_AZ -> items.sortedWith { a, b ->
            val cmp = titleOf(a).lowercase().compareTo(titleOf(b).lowercase())
            if (cmp != 0) cmp else newestCmp.compare(a, b)
        }
    }

    fun isoToday(nowMs: Long): String =
        Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate().toString()
}
