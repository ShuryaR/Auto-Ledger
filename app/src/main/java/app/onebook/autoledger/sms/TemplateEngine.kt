package app.onebook.autoledger.sms

import app.onebook.autoledger.core.AccountType
import app.onebook.autoledger.core.Direction
import app.onebook.autoledger.core.Money
import app.onebook.autoledger.core.ParsedTxn
import app.onebook.autoledger.core.TxnKind
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.util.regex.Pattern

data class NativeParse(
    val kind: String,
    val direction: String,
    val amountMinor: Long,
    val currency: String,
    val accountLast4: String?,
)

sealed interface ParseResult {
    data class Ok(val txn: ParsedTxn) : ParseResult
    data class Drop(val reason: String) : ParseResult // "otp" | "not_money" | "si_notice" | "unknown_template"
}

class TemplateEngine private constructor(
    private val otp: Pattern,
    private val moneyVerb: Pattern,
    private val amountRx: Pattern,
    private val banks: List<Bank>,
    val version: Int,
) {
    private class Template(
        val id: String,
        val kind: String,
        val ignoreReason: String?,
        val direction: String,
        val accountType: String,
        val regex: Pattern,
    )

    private class Bank(
        val senderPattern: String,
        val templates: List<Template>,
    )

    fun parse(sender: String, body: String): NativeParse? = try {
        when (val r = parseFullUnsafe(sender, normalise(body), 0L, computeDate = false)) {
            is ParseResult.Ok -> NativeParse(
                kind = r.txn.kind.wire,
                direction = r.txn.direction.wire,
                amountMinor = r.txn.amountMinor,
                currency = r.txn.currency,
                accountLast4 = r.txn.accountLast4,
            )
            is ParseResult.Drop -> null
        }
    } catch (_: Throwable) {
        null
    }

    fun parseFull(sender: String, body: String, receivedAt: Long): ParseResult = try {
        parseFullUnsafe(sender, normalise(body), receivedAt, computeDate = true)
    } catch (_: Throwable) {
        ParseResult.Drop("unknown_template")
    }

    fun isOtp(body: String): Boolean = try {
        otp.matcher(normalise(body)).find()
    } catch (_: Throwable) {
        false
    }

    fun looksLikeMoney(sender: String, body: String): Boolean = try {
        val b = normalise(body)
        when {
            otp.matcher(b).find() -> false
            firstMatchIsIgnore(sender, b) -> false
            else -> moneyVerb.matcher(b).find() && amountRx.matcher(b).find()
        }
    } catch (_: Throwable) {
        false
    }

    private fun firstMatchIsIgnore(sender: String, body: String): Boolean {
        val bank = banks.firstOrNull { sender.contains(it.senderPattern) } ?: return false
        val tpl = bank.templates.firstOrNull { it.regex.matcher(body).find() } ?: return false
        return tpl.kind == "ignore"
    }

    private fun parseFullUnsafe(
        sender: String,
        body: String,
        receivedAt: Long,
        computeDate: Boolean,
    ): ParseResult {
        if (otp.matcher(body).find()) return ParseResult.Drop("otp")
        val bank = banks.firstOrNull { sender.contains(it.senderPattern) }
            ?: return ParseResult.Drop("unknown_template")

        for (tpl in bank.templates) {
            val m = tpl.regex.matcher(body)
            if (!m.find()) continue
            if (tpl.kind == "ignore") {
                return ParseResult.Drop(tpl.ignoreReason ?: "not_money")
            }

            fun g(name: String): String? = try {
                m.group(name)
            } catch (_: IllegalArgumentException) {
                null
            }

            val day = g("day")
            val month = g("month")
            val year = g("year")
            val occurredOn: String = if (!day.isNullOrEmpty() && !month.isNullOrEmpty() && !year.isNullOrEmpty()) {
                val y = if (year.length == 2) 2000 + year.toInt() else year.toInt()
                val mo = monthNumber(month)
                val dd = day.toInt()
                require(mo in 1..12 && dd in 1..31) { "bad date" }
                "%04d-%02d-%02d".format(y, mo, dd)
            } else if (computeDate) {
                istDate(receivedAt)
            } else {
                "1970-01-01"
            }

            val occurredTime = g("time")?.takeIf { it.isNotEmpty() }?.let { normTime(it) }
            val balanceMinor = g("balance")?.takeIf { it.isNotEmpty() }?.let { parseMinor(it) }
            val amountMinor = parseMinor(g("amount") ?: return ParseResult.Drop("unknown_template"))
            val currency = normCurrency(g("currency"))

            val txn = ParsedTxn(
                kind = TxnKind.fromWire(tpl.kind),
                direction = Direction.fromWire(tpl.direction),
                amountMinor = amountMinor,
                currency = currency,
                occurredOn = occurredOn,
                occurredTime = occurredTime,
                accountType = AccountType.fromWire(tpl.accountType),
                accountLast4 = g("last4")?.takeIf { it.isNotEmpty() },
                counterparty = g("counterparty")?.trim()?.takeIf { it.isNotEmpty() },
                utr = g("utr")?.takeIf { it.isNotEmpty() },
                merchant = g("merchant")?.trim()?.takeIf { it.isNotEmpty() },
                balanceMinor = balanceMinor,
                sender = sender,
                receivedAt = receivedAt,
                rawHash = rawHash(sender, body),
            )
            return ParseResult.Ok(txn)
        }

        val reason = if (moneyVerb.matcher(body).find() && amountRx.matcher(body).find()) {
            "unknown_template"
        } else {
            "not_money"
        }
        return ParseResult.Drop(reason)
    }

    companion object {
        private const val FLAGS = Pattern.CASE_INSENSITIVE
        private val UNICODE_SPACES = Regex("[\\p{Zs}\\uFEFF\\u2028\\u2029]")
        private val WHITESPACE = Regex("\\s+")
        private val MONEY = Regex("(\\d[\\d,]*)(?:\\.(\\d*))?")
        private val TIME = Regex("^(\\d{1,2}):(\\d{2})\\s*([AP]M)?$", RegexOption.IGNORE_CASE)
        private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        private val IST_OFFSET = ZoneOffset.ofHoursMinutes(5, 30)

        fun normalise(body: String): String = UNICODE_SPACES.replace(body, " ")

        fun istDate(ms: Long): String =
            Instant.ofEpochMilli(ms).atOffset(IST_OFFSET).toLocalDate().toString()

        /** cyrb53 hash matching src/core/parser/engine.ts byte-for-byte. */
        fun cyrb53(str: String): String {
            var h1 = 0xdeadbeef.toInt()
            var h2 = 0x41c6ce57
            for (i in str.indices) {
                val ch = str[i].code
                h1 = (h1 xor ch) * 2654435761.toInt()
                h2 = (h2 xor ch) * 1597334677
            }
            h1 = ((h1 xor (h1 ushr 16)) * 2246822507.toInt()) xor ((h2 xor (h2 ushr 13)) * 3266489909.toInt())
            h2 = ((h2 xor (h2 ushr 16)) * 2246822507.toInt()) xor ((h1 xor (h1 ushr 13)) * 3266489909.toInt())
            val combined = 4294967296L * (2097151L and h2.toLong()) + (h1.toLong() and 0xFFFFFFFFL)
            return combined.toString()
        }

        fun rawHash(sender: String, body: String): String {
            val cleaned = normalise(body).replace(WHITESPACE, " ").trim()
            return cyrb53("$sender\n$cleaned")
        }

        fun fromJson(json: String): TemplateEngine? {
            return try {
                val root = JSONObject(json)
                val otp = compileRequired(root, "otpPattern")
                val moneyVerb = compileRequired(root, "moneyVerbPattern")
                val amountRx = compileRequired(root, "amountPattern")
                val banksJson = root.optJSONArray("banks") ?: return null
                if (banksJson.length() == 0) return null
                val banks = ArrayList<Bank>()
                for (i in 0 until banksJson.length()) {
                    val b = banksJson.getJSONObject(i)
                    val sp = b.getString("senderPattern")
                    if (sp.isEmpty()) return null
                    val tj = b.getJSONArray("templates")
                    val tpls = ArrayList<Template>()
                    for (j in 0 until tj.length()) {
                        val t = tj.getJSONObject(j)
                        val id = t.optString("id", "tpl-$j")
                        val kind = t.getString("kind")
                        if (kind.isEmpty()) return null
                        val rx = t.getString("regex")
                        if (rx.isEmpty()) return null
                        val ignoreReason = if (t.has("ignoreReason") && !t.isNull("ignoreReason")) t.getString("ignoreReason") else null
                        val direction = if (t.has("direction") && !t.isNull("direction")) t.getString("direction") else "out"
                        val accountType = if (t.has("accountType") && !t.isNull("accountType")) t.getString("accountType") else "bank"
                        tpls.add(Template(id, kind, ignoreReason, direction, accountType, Pattern.compile(rx, FLAGS)))
                    }
                    banks.add(Bank(sp, tpls))
                }
                val version = parseVersion(root) ?: return null
                TemplateEngine(otp, moneyVerb, amountRx, banks, version)
            } catch (_: Throwable) {
                null
            }
        }

        fun selfTest(): Boolean = try {
            val m = Pattern.compile("(?<probe>\\d+)", FLAGS).matcher("a12")
            m.find() && m.group("probe") == "12"
        } catch (_: Throwable) {
            false
        }

        /** Smoke-checks a newly downloaded template set before trusting it (matching src/ingest/templates.ts). */
        fun smokeCheck(engine: TemplateEngine): Boolean {
            val samples = listOf(
                Triple(
                    "AD-IDFCFB-S",
                    "Your A/C XXXXX123456 is debited by INR 1,000.00 on 08/11/26 10:15. New bal: INR 50,000.00",
                    true,
                ),
                Triple(
                    "AD-IDFCFB-S",
                    "123456 is your OTP for transaction of INR 1000",
                    false,
                ),
                Triple(
                    "AD-IDFCFB-S",
                    "INR 500.00 spent on your IDFC FIRST Bank Credit Card ending XX1234 at SWIGGY on 08/11/26 at 10:15 AM Avbl Limit INR 50,000",
                    true,
                ),
                Triple(
                    "AD-IDFCFB-S",
                    "Thank you for payment of INR 10,000.00 towards your IDFC FIRST Bank Credit Card",
                    false,
                ),
            )
            for ((sender, body, shouldMatch) in samples) {
                val res = engine.parseFull(sender, body, 1_700_000_000_000L)
                if (shouldMatch && res !is ParseResult.Ok) return false
                if (!shouldMatch && res is ParseResult.Ok) return false
            }
            return true
        }

        private fun parseVersion(root: JSONObject): Int? {
            if (!root.has("version")) return 0
            val v = root.get("version")
            if (v !is Number) return null
            val d = v.toDouble()
            if (d.isNaN() || d.isInfinite() || d != kotlin.math.floor(d) || d < 0 || d > Int.MAX_VALUE) return null
            return d.toInt()
        }

        private fun compileRequired(root: JSONObject, key: String): Pattern {
            val s = root.getString(key)
            require(s.isNotBlank()) { "$key is empty" }
            val p = Pattern.compile(s, FLAGS)
            require(!p.matcher("").find()) { "$key matches the empty string" }
            return p
        }

        private fun monthNumber(m: String): Int {
            if (m.all { it in '0'..'9' }) return m.toInt()
            val i = MONTHS.indexOf(m.take(3).lowercase())
            require(i >= 0) { "bad month $m" }
            return i + 1
        }

        private fun normTime(t: String): String {
            val m = requireNotNull(TIME.find(t.trim())) { "bad time $t" }
            var h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            require(h <= 23 && min <= 59) { "bad time $t" }
            val ampm = m.groupValues[3]
            if (ampm.isNotEmpty()) {
                val pm = ampm.equals("PM", ignoreCase = true)
                h = (h % 12) + (if (pm) 12 else 0)
            }
            return "%02d:%02d".format(h, min)
        }

        internal fun parseMinor(text: String): Long = Money.parseMinor(text)

        private fun normCurrency(c: String?): String {
            if (c.isNullOrEmpty()) return "INR"
            return if (c.startsWith("rs", ignoreCase = true)) "INR" else c.uppercase()
        }
    }
}
