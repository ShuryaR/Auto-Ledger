package app.onebook.autoledger.erp

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

object ErpReports {
    fun toMinor(x: Any?): Long {
        if (x == null || x == JSONObject.NULL) return 0L
        if (x is String && x.trim().isEmpty()) return 0L
        val bd = when (x) {
            is BigDecimal -> x
            is Number -> {
                val d = x.toDouble()
                if (!d.isFinite()) throw IllegalArgumentException("Not a valid amount: $x")
                BigDecimal.valueOf(d)
            }
            is String -> try {
                BigDecimal(x.trim())
            } catch (_: Exception) {
                throw IllegalArgumentException("Not a valid amount: $x")
            }
            else -> throw IllegalArgumentException("Not a valid amount: $x")
        }
        return bd.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
    }

    private fun localIsoDate(): String = LocalDate.now().toString()

    suspend fun getBankBalance(c: ErpClient, glAccount: String, company: String): Long {
        val fields = JSONArray()
            .put(JSONObject().put("SUM", "debit").put("as", "debit"))
            .put(JSONObject().put("SUM", "credit").put("as", "credit"))
        val filters = JSONArray()
            .put(JSONArray().put("account").put("=").put(glAccount))
            .put(JSONArray().put("company").put("=").put(company))
            .put(JSONArray().put("is_cancelled").put("=").put(0))
            .put(JSONArray().put("posting_date").put("<=").put(localIsoDate()))

        val r = c.getObject(
            "/api/method/frappe.client.get_list",
            mapOf(
                "doctype" to "GL Entry",
                "fields" to fields,
                "filters" to filters,
            ),
        )
        val msg = r.optJSONArray("message") ?: throw ErpHttp.unexpectedResponse()
        if (msg.length() == 0) return 0L
        val row = msg.optJSONObject(0) ?: return 0L
        return toMinor(row.opt("debit")) - toMinor(row.opt("credit"))
    }

    private suspend fun reportOutstandingTotal(c: ErpClient, reportName: String, company: String): Long {
        val args = JSONObject()
            .put("report_name", reportName)
            .put(
                "filters",
                JSONObject()
                    .put("company", company)
                    .put("report_date", localIsoDate())
                    .put("ageing_based_on", "Due Date")
                    .put("range", "30, 60, 90, 120"),
            )
            .put("ignore_prepared_report", true)

        val rep = c.callAny("frappe.desk.query_report.run", args) as? JSONObject
            ?: throw ErpHttp.unexpectedResponse()
        val result = rep.optJSONArray("result") ?: throw ErpHttp.unexpectedResponse()
        val columns = rep.optJSONArray("columns") ?: throw ErpHttp.unexpectedResponse()
        if (result.length() == 0) return 0L

        var totalRow: JSONArray? = null
        for (i in 0 until result.length()) {
            val row = result.opt(i)
            if (row is JSONArray) {
                totalRow = row
                break
            }
        }
        var outIdx = -1
        for (i in 0 until columns.length()) {
            val col = columns.optJSONObject(i)
            if (col?.optString("fieldname") == "outstanding") {
                outIdx = i
                break
            }
        }
        if (totalRow == null || outIdx < 0) throw ErpHttp.unexpectedResponse()
        return toMinor(totalRow.opt(outIdx))
    }

    data class ReceivablesPayables(val receivableMinor: Long, val payableMinor: Long)

    suspend fun getReceivablesPayables(c: ErpClient, company: String): ReceivablesPayables {
        val receivable = reportOutstandingTotal(c, "Accounts Receivable Summary", company)
        val payable = reportOutstandingTotal(c, "Accounts Payable Summary", company)
        return ReceivablesPayables(receivable, payable)
    }
}
