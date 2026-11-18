package app.onebook.autoledger.inbox

import app.onebook.autoledger.core.PlanStep
import app.onebook.autoledger.core.SimilarEngine
import app.onebook.autoledger.ingest.DupFlag
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

data class DetailRow(
    val label: String,
    val value: String,
    val strong: Boolean = false,
    val mono: Boolean = false,
)

data class DuplicateWarning(
    val title: String,
    val message: String,
    val doctype: String,
    val name: String,
)

object Describe {
    const val DUPLICATE_TITLE = "This may already be in ERPNext"
    const val DUPLICATE_REFERENCE = "An entry with the same reference number as this payment is already there."
    const val DUPLICATE_STRONG = "An entry for the same amount, on the same day, with the same name is already there."
    const val DUPLICATE_WEAK = "An entry for the same amount, within 3 days of this one, is already there."
    const val UNCHECKED_NOTE = "We could not check older entries in ERPNext for this one."

    private val NAME_FIELDS = listOf("customer_name", "supplier_name", "first_name", "employee_name")

    private fun optStr(b: JSONObject, key: String): String {
        if (!b.has(key) || b.isNull(key)) return ""
        return b.opt(key)?.toString() ?: ""
    }

    private fun money(major: Any?): String {
        val num = when (major) {
            is Number -> major.toDouble()
            is String -> major.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
        return Formatters.formatInr((num * 100.0).roundToLong())
    }

    private fun humanize(f: String): String =
        (f.take(1).uppercase() + f.drop(1)).replace('_', ' ')

    fun stepTitle(s: PlanStep): String =
        if (s.kind == "create_party") "New ${s.doctype}" else s.doctype

    fun describeStep(s: PlanStep, labels: Map<String, String> = emptyMap()): List<DetailRow> {
        val b = s.bodyObject()
        if (s.kind == "create_party") {
            val nameKey = NAME_FIELDS.firstOrNull { optStr(b, it).isNotEmpty() }
            val rows = mutableListOf(DetailRow(label = "Name", value = if (nameKey != null) optStr(b, nameKey) else ""))
            val keys = b.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k != nameKey) {
                    rows.add(DetailRow(label = labels[k] ?: humanize(k), value = optStr(b, k)))
                }
            }
            return rows
        }

        val rows = mutableListOf<DetailRow>()
        if (s.doctype == "Payment Entry") {
            val partyStr = optStr(b, "party")
            val party = if (partyStr.isNotEmpty()) "$partyStr (${optStr(b, "party_type")})" else ""
            val allRefsArr = b.optJSONArray("references") ?: JSONArray()
            val allRefs = (0 until allRefsArr.length()).mapNotNull { allRefsArr.optJSONObject(it) }
            val orders = allRefs.filter { optStr(it, "reference_doctype") == "Sales Order" }
            val pt = optStr(b, "payment_type")
            when (pt) {
                "Receive" -> {
                    rows.add(DetailRow("Money in from", party))
                    rows.add(DetailRow("Amount", money(b.opt("paid_amount")), strong = true))
                    if (orders.isNotEmpty()) {
                        val ordText = orders.joinToString(", ") {
                            "order ${optStr(it, "reference_name")} (${money(it.opt("allocated_amount"))})"
                        }
                        rows.add(DetailRow("Advance for", ordText))
                    }
                    rows.add(DetailRow("Into account", optStr(b, "paid_to")))
                }
                "Pay" -> {
                    rows.add(DetailRow("Paid to", party))
                    rows.add(DetailRow("Amount", money(b.opt("paid_amount")), strong = true))
                    rows.add(DetailRow("From account", optStr(b, "paid_from")))
                }
                else -> {
                    rows.add(DetailRow("From account", optStr(b, "paid_from")))
                    rows.add(DetailRow("To account", optStr(b, "paid_to")))
                    rows.add(DetailRow("Amount", money(b.opt("paid_amount")), strong = true))
                }
            }
            val refs = allRefs.filter { optStr(it, "reference_doctype") != "Sales Order" }
            if (refs.isNotEmpty()) {
                val refText = refs.joinToString(", ") {
                    "${optStr(it, "reference_name")} (${money(it.opt("allocated_amount"))})"
                }
                rows.add(DetailRow("Against", refText))
            }
            rows.add(DetailRow("Mode of payment", optStr(b, "mode_of_payment")))
            rows.add(DetailRow("Date", Formatters.longDate(optStr(b, "posting_date"))))
            rows.add(DetailRow("Reference", optStr(b, "reference_no"), mono = true))
            return rows
        }

        val lines = b.optJSONArray("accounts") ?: JSONArray()
        var amount: Any? = 0
        for (i in 0 until lines.length()) {
            val l = lines.optJSONObject(i) ?: continue
            val p = optStr(l, "party")
            val acc = if (p.isNotEmpty()) "${optStr(l, "account")} ($p)" else optStr(l, "account")
            if (l.has("debit_in_account_currency") && !l.isNull("debit_in_account_currency")) {
                rows.add(DetailRow("Debit", acc))
                amount = l.opt("debit_in_account_currency")
            } else {
                rows.add(DetailRow("Credit", acc))
            }
        }
        rows.add(DetailRow("Amount", money(amount), strong = true))
        rows.add(DetailRow("Date", Formatters.longDate(optStr(b, "posting_date"))))
        rows.add(DetailRow("Reference", optStr(b, "cheque_no"), mono = true))
        return rows
    }

    fun duplicateWarning(flag: DupFlag?): DuplicateWarning? {
        if (flag == null) return null
        val message = when {
            flag.why == SimilarEngine.MatchWhy.REFERENCE -> DUPLICATE_REFERENCE
            flag.strength == SimilarEngine.MatchStrength.STRONG -> DUPLICATE_STRONG
            else -> DUPLICATE_WEAK
        }
        return DuplicateWarning(
            title = DUPLICATE_TITLE,
            message = message,
            doctype = flag.doctype,
            name = flag.name,
        )
    }

    fun uncheckedNote(occurredOn: String, checkedFrom: String?): String? {
        if (checkedFrom.isNullOrBlank()) return null
        val day = SimilarEngine.dayNumber(occurredOn) ?: return null
        val from = SimilarEngine.dayNumber(checkedFrom) ?: return null
        return if (day - SimilarEngine.SIMILAR_DAYS <= from) UNCHECKED_NOTE else null
    }
}
