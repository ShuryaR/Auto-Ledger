package app.onebook.autoledger.inbox

import app.onebook.autoledger.core.AccountMapping
import app.onebook.autoledger.core.Direction
import app.onebook.autoledger.core.Money
import app.onebook.autoledger.core.PendingTxn
import app.onebook.autoledger.core.TxnKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

object Formatters {
    const val MINUS = "\u2212"
    private const val IST_OFFSET_MS = 330L * 60_000L
    private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** Today's date in Asia/Kolkata (no DST) as YYYY-MM-DD. */
    fun istToday(nowMs: Long): String {
        val d = Instant.ofEpochMilli(nowMs + IST_OFFSET_MS).atOffset(ZoneOffset.UTC).toLocalDate()
        return d.toString()
    }

    private fun dayDiff(a: String, b: String): Long = try {
        val da = LocalDate.parse(a)
        val db = LocalDate.parse(b)
        ChronoUnit.DAYS.between(da, db)
    } catch (_: Exception) {
        0L
    }

    fun formatInr(minor: Long): String {
        val neg = minor < 0L
        val absVal = abs(minor)
        val formatted = Money.formatMoney(absVal, indian = true)
        return "${if (neg) MINUS else ""}\u20B9$formatted"
    }

    fun formatMoney(minor: Long, currency: String?): String {
        val cur = (currency ?: "").trim().uppercase()
        if (cur.isEmpty() || cur == "INR") return formatInr(minor)
        val neg = minor < 0L
        val absVal = abs(minor)
        return "${if (neg) MINUS else ""}$cur ${Money.formatMoney(absVal, indian = false)}"
    }

    fun signedMoney(minor: Long, currency: String?, direction: Direction): String {
        val sign = if (direction == Direction.IN) "+" else MINUS
        return "$sign${formatMoney(abs(minor), currency)}"
    }

    fun westernMoney(minor: Long): String {
        val absVal = abs(minor)
        return Money.formatMoney(absVal, indian = false)
    }

    fun plainAmount(txn: PendingTxn): String = plainAmount(txn.amountMinor, txn.currency)

    fun plainAmount(amountMinor: Long, currency: String): String =
        if (currency == "INR") formatInr(amountMinor) else "$currency ${westernMoney(amountMinor)}"

    fun signedAmount(txn: PendingTxn): String {
        val sign = if (txn.direction == Direction.IN) "+" else MINUS
        return "$sign${plainAmount(txn)}"
    }

    fun amountA11y(txn: PendingTxn): String =
        "${plainAmount(txn)} ${if (txn.direction == Direction.IN) "received" else "paid"}"

    fun shortAccount(name: String): String {
        val i = name.lastIndexOf(" - ")
        return if (i > 0) name.substring(0, i) else name
    }

    fun accountLabel(txn: PendingTxn, mapping: AccountMapping?): String {
        if (mapping == null) return "Account not set"
        val name = shortAccount(mapping.bankAccount)
        return if (!txn.accountLast4.isNullOrBlank()) "$name \u2026${txn.accountLast4}" else name
    }

    fun kindLabel(k: TxnKind): String = when (k) {
        TxnKind.CA_CREDIT -> "Credit"
        TxnKind.CA_DEBIT -> "Debit"
        TxnKind.NEFT_CONFIRM -> "NEFT"
        TxnKind.CARD_SPEND -> "Card spend"
        TxnKind.CARD_SI_PAID -> "Standing instruction"
    }

    fun titleOf(txn: PendingTxn): String =
        txn.counterparty ?: txn.merchant ?: if (txn.direction == Direction.IN) "Money received" else "Money paid"

    private fun clock(hhmm: String): String {
        val parts = hhmm.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$h12:${m.toString().padStart(2, '0')} ${if (h < 12) "am" else "pm"}"
    }

    private fun dateText(iso: String, withYear: Boolean): String {
        val parts = iso.split("-")
        val y = parts.getOrNull(0)?.toIntOrNull() ?: 1970
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 1
        val d = parts.getOrNull(2)?.toIntOrNull() ?: 1
        val mon = MONTHS.getOrElse(m - 1) { "" }
        return if (withYear) "$d $mon $y" else "$d $mon"
    }

    fun whenLabel(txn: PendingTxn, today: String): String {
        val diff = dayDiff(txn.occurredOn, today)
        val t = txn.occurredTime?.takeIf { it.isNotBlank() }?.let { clock(it) }
        if (diff == 0L) return t ?: "today"
        if (diff == 1L) return if (t != null) "yesterday $t" else "yesterday"
        val sameYear = txn.occurredOn.take(4) == today.take(4)
        val date = dateText(txn.occurredOn, !sameYear)
        return if (t != null) "$date, $t" else date
    }

    fun dateTimeLabel(txn: PendingTxn): String {
        val date = dateText(txn.occurredOn, true)
        val t = txn.occurredTime?.takeIf { it.isNotBlank() }?.let { clock(it) }
        return if (t != null) "$date, $t" else date
    }

    fun longDate(iso: String): String = dateText(iso, true)

    fun relativeAgo(now: Long, then: Long): String {
        val s = maxOf(0L, ((now - then) / 1000.0).roundToLong())
        if (s < 60L) return "just now"
        val min = floor(s / 60.0).toLong()
        if (min < 60L) return "$min min ago"
        val h = floor(min / 60.0).toLong()
        if (h < 24L) return "$h h ago"
        val d = floor(h / 24.0).toLong()
        return "$d day${if (d == 1L) "" else "s"} ago"
    }

    fun friendlyDoctype(doctype: String): String {
        val t = doctype.trim()
        return if (t.isNotEmpty()) t.first().uppercaseChar() + t.drop(1).lowercase() else t
    }
}
