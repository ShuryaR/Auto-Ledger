package app.onebook.autoledger.sms

import java.util.regex.Pattern

class SenderFilter(patterns: List<String>) {
    private val pats = patterns.map { it.trim() }.filter { it.isNotEmpty() }

    val hasPatterns: Boolean get() = pats.isNotEmpty()

    fun matches(sender: String): Boolean = pats.any { sender.contains(it, ignoreCase = true) }
}

data class SmsPart(val address: String?, val body: String?, val timestampMillis: Long)
data class GroupedSms(val sender: String, val body: String, val receivedAt: Long)

object MultipartGrouper {
    fun group(parts: List<SmsPart>): List<GroupedSms> {
        val groups = LinkedHashMap<String, Pair<Long, StringBuilder>>()
        for (p in parts) {
            val addr = p.address ?: ""
            val g = groups.getOrPut(addr) { p.timestampMillis to StringBuilder() }
            g.second.append(p.body ?: "")
        }
        return groups.map { (addr, g) -> GroupedSms(addr, g.second.toString(), g.first) }
    }
}

sealed class Decision {
    data class Drop(val reason: String) : Decision()
    data class Store(val kind: String, val parse: NativeParse?) : Decision()
}

object FallbackOtp {
    private const val KW = "(?:otp|one[- ]?time\\s+password)(?:\\s*\\(otp\\))?"
    private const val WINDOW = "(?:[^.\\n]|\\.\\d|\\.[a-z]|\\brs\\.\\s){0,80}"
    private val RX = Pattern.compile(
        "\\b\\d{4,8}\\s+(?:is|as)\\s+(?:(?:your|the)\\s+)?$KW" +
            "|\\b$KW(?:\\s+is)?\\s*[:\\-]?\\s*\\d{4,8}\\b" +
            "|\\b$KW\\s+(?:for|to)\\b$WINDOW\\bis\\s*:?\\s*\\d{4,8}\\b",
        Pattern.CASE_INSENSITIVE,
    )

    fun matches(body: String): Boolean = try {
        RX.matcher(TemplateEngine.normalise(body)).find()
    } catch (_: Throwable) {
        false
    }
}

class MessageClassifier(
    private val filter: SenderFilter,
    private val engine: TemplateEngine?,
    private val engineUsable: Boolean,
) {
    fun classify(sender: String, body: String): Decision {
        if (!filter.matches(sender)) return Decision.Drop("sender")
        if (engine == null || !engineUsable) {
            if (engine?.isOtp(body) == true || FallbackOtp.matches(body)) return Decision.Drop("otp")
            return Decision.Store(UNRECOGNISED, null)
        }
        if (engine.isOtp(body)) return Decision.Drop("otp")
        engine.parse(sender, body)?.let { return Decision.Store(it.kind, it) }
        return if (engine.looksLikeMoney(sender, body)) Decision.Store(UNRECOGNISED, null) else Decision.Drop("not_money")
    }

    fun keep(sender: String, body: String): Boolean = classify(sender, body) is Decision.Store

    companion object {
        const val UNRECOGNISED = "unrecognised"
    }
}

object NotificationText {
    const val PUBLIC_TITLE = "AutoLedger"
    const val PUBLIC_TEXT = "New bank transaction"
    const val PRIVATE_TEXT = "Tap to classify"

    fun title(p: NativeParse): String {
        val amt = amount(p.amountMinor, p.currency)
        val tail = p.accountLast4?.takeIf { it.isNotEmpty() }?.let { " …$it" } ?: ""
        val phrase = when (p.kind) {
            "ca_credit" -> "credited to IDFC First"
            "ca_debit" -> "debited from IDFC First"
            "card_spend" -> "spent on IDFC card"
            "card_si_paid" -> "paid by standing instruction on IDFC card"
            "neft_confirm" -> "sent by NEFT from IDFC First"
            else -> if (p.direction == "in") "received in IDFC First" else "paid from IDFC First"
        }
        return "$amt $phrase$tail"
    }

    fun amount(minor: Long, currency: String): String {
        val neg = minor < 0
        val abs = if (neg) -minor else minor
        val whole = (abs / 100).toString()
        val frac = (abs % 100).toString().padStart(2, '0')
        val inr = currency.equals("INR", ignoreCase = true)
        val grouped = if (inr) groupIndian(whole) else groupThousands(whole)
        val sign = if (neg) "\u2212" else ""
        return if (inr) "$sign₹$grouped.$frac" else "$sign${currency.uppercase()} $grouped.$frac"
    }

    fun notificationId(seq: Long): Int = (Math.floorMod(seq - 1, Int.MAX_VALUE.toLong()) + 1).toInt()

    private fun groupThousands(d: String): String = d.reversed().chunked(3).joinToString(",").reversed()

    private fun groupIndian(d: String): String {
        if (d.length <= 3) return d
        val head = d.substring(0, d.length - 3)
        return head.reversed().chunked(2).joinToString(",").reversed() + "," + d.takeLast(3)
    }
}
