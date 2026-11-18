package app.onebook.autoledger.sms

object BackfillQuery {
    const val MAX_DAYS = 3650
    const val CAP = 5000
    private const val DAY_MS = 86_400_000L

    data class Bounds(val selection: String?, val args: Array<String>?)

    fun reads(days: Int): Boolean = days >= 0

    fun since(days: Int, now: Long): Long? =
        if (days <= 0) null else now - days.coerceAtMost(MAX_DAYS).toLong() * DAY_MS

    fun bounds(days: Int, now: Long, dateColumn: String): Bounds {
        val s = since(days, now) ?: return Bounds(null, null)
        return Bounds("$dateColumn >= ?", arrayOf(s.toString()))
    }
}
