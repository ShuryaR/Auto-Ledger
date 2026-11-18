package app.onebook.autoledger.core

import java.math.BigDecimal

/**
 * Parse an amount such as `INR 5,12,932.76`, `Rs. 115,000.00`, `Rs 8,000.00`,
 * `USD 100.00` into integer minor units. No floating point: digits and the
 * decimal part are handled as strings.
 */
object Money {
    private val AMOUNT_REGEX = Regex("(\\d[\\d,]*)(?:\\.(\\d*))?")

    fun parseMinor(text: String): Long {
        val m = AMOUNT_REGEX.find(text) ?: throw IllegalArgumentException("parseMinor: no amount in \"$text\"")
        val whole = m.groupValues[1].replace(",", "")
        val frac = (m.groupValues[2] + "00").substring(0, 2)
        return whole.toLong() * 100L + frac.toLong()
    }

    /** Integer minor units to a major-unit string with exact 2 decimals (e.g. "123.45"). */
    fun majorString(minor: Long): String {
        if (minor < 0L) throw PlanError("bad_amount", "bad amount $minor")
        val whole = minor / 100L
        val frac = (minor % 100L).toString().padStart(2, '0')
        return "$whole.$frac"
    }

    /** Exact BigDecimal major unit so JSON serialization avoids binary float drift. */
    fun minorToMajorDecimal(minor: Long): BigDecimal = BigDecimal(majorString(minor))

    fun minorToMajorDouble(minor: Long): Double = majorString(minor).toDouble()

    /** Parse user-entered rupee string (e.g. "8,450.50" -> 845050 minor); null if blank/invalid/non-positive. */
    fun parseUserMinor(raw: String): Long? {
        val trimmed = raw.trim().replace(",", "")
        if (trimmed.isEmpty()) return null
        val m = Regex("^(\\d+)(?:\\.(\\d{1,2}))?$").matchEntire(trimmed) ?: return null
        val whole = m.groupValues[1].toLongOrNull() ?: return null
        val frac = (m.groupValues[2] + "00").substring(0, 2).toLong()
        val total = whole * 100L + frac
        return if (total > 0L) total else null
    }

    fun groupDigits(whole: String, indian: Boolean): String {
        if (!indian) {
            return whole.replace(Regex("\\B(?=(\\d{3})+(?!\\d))"), ",")
        }
        if (whole.length <= 3) return whole
        val head = whole.dropLast(3).replace(Regex("\\B(?=(\\d{2})+(?!\\d))"), ",")
        val tail = whole.takeLast(3)
        return "$head,$tail"
    }

    fun formatMoney(minor: Long, indian: Boolean): String {
        val absMinor = kotlin.math.abs(minor)
        val parts = majorString(absMinor).split(".")
        return "${groupDigits(parts[0], indian)}.${parts[1]}"
    }

    /** Format minor units in INR (e.g. `₹5,12,932.76` or `−₹5,12,932.76`). */
    fun formatInr(minor: Long): String {
        val sign = if (minor < 0L) "\u2212" else ""
        return "${sign}₹${formatMoney(minor, indian = true)}"
    }

    /** Format minor units in any currency (Indian grouping for INR, 3-digit grouping otherwise). */
    fun formatCurrency(minor: Long, currency: String): String {
        if (currency == "INR") return formatInr(minor)
        val sign = if (minor < 0L) "\u2212" else ""
        return "$sign$currency ${formatMoney(minor, indian = false)}"
    }
}
