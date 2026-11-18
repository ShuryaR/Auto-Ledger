package app.onebook.autoledger.erp

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class ExposureKind(val reportName: String, val label: String) {
    AR("Accounts Receivable Summary", "Accounts Receivable"),
    AP("Accounts Payable Summary", "Accounts Payable"),
}

enum class AgeingBucket(val label: String, val shortLabel: String) {
    B0_30("0\u201330 days", "0-30d"),
    B31_60("31\u201360 days", "31-60d"),
    B61_90("61\u201390 days", "61-90d"),
    B91_120("91\u2013120 days", "91-120d"),
    B120_PLUS("120+ days", "120+d"),
}

data class AgeingBuckets(
    val b0To30: Long = 0L,
    val b31To60: Long = 0L,
    val b61To90: Long = 0L,
    val b91To120: Long = 0L,
    val b120Plus: Long = 0L,
) {
    val total: Long get() = b0To30 + b31To60 + b61To90 + b91To120 + b120Plus
    val overdueMinor: Long get() = b31To60 + b61To90 + b91To120 + b120Plus

    fun amountFor(bucket: AgeingBucket): Long = when (bucket) {
        AgeingBucket.B0_30 -> b0To30
        AgeingBucket.B31_60 -> b31To60
        AgeingBucket.B61_90 -> b61To90
        AgeingBucket.B91_120 -> b91To120
        AgeingBucket.B120_PLUS -> b120Plus
    }

    fun list(): List<Long> = listOf(b0To30, b31To60, b61To90, b91To120, b120Plus)
}

data class PartyExposureRow(
    val party: String,
    val partyType: String?,
    val invoicedMinor: Long,
    val paidMinor: Long,
    val advanceMinor: Long,
    val creditNoteMinor: Long,
    val outstandingMinor: Long,
    val totalDueMinor: Long,
    val buckets: AgeingBuckets,
)

data class PartyExposureReport(
    val kind: ExposureKind,
    val reportDate: String,
    val totalOutstandingMinor: Long,
    val totalInvoicedMinor: Long,
    val totalPaidMinor: Long,
    val totalAdvanceMinor: Long,
    val totalCreditNoteMinor: Long,
    val buckets: AgeingBuckets,
    val rows: List<PartyExposureRow>,
)

data class FiscalYearInfo(
    val name: String,
    val startDate: String,
    val endDate: String,
)

enum class PnlRootType { INCOME, EXPENSE }

data class MonthPerformance(
    val ym: String,
    val label: String,
    val incomeMinor: Long,
    val expenseMinor: Long,
) {
    val netMinor: Long get() = incomeMinor - expenseMinor
}

data class PnlLineItem(
    val account: String,
    val label: String,
    val rootType: PnlRootType,
    val indent: Int,
    val isGroup: Boolean,
    val mtdMinor: Long,
    val fyMinor: Long,
)

data class ProfitAndLossData(
    val fiscalYear: FiscalYearInfo,
    val asOfDate: String,
    val months: List<MonthPerformance>,
    val mtdIncomeMinor: Long,
    val mtdExpenseMinor: Long,
    val fyIncomeMinor: Long,
    val fyExpenseMinor: Long,
    val lines: List<PnlLineItem>,
    val fromFallback: Boolean = false,
) {
    val mtdNetMinor: Long get() = mtdIncomeMinor - mtdExpenseMinor
    val fyNetMinor: Long get() = fyIncomeMinor - fyExpenseMinor
}

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

    private fun safeMinor(x: Any?): Long = try {
        toMinor(x)
    } catch (_: Exception) {
        0L
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

    internal fun parseExposureResponse(
        kind: ExposureKind,
        rep: JSONObject,
        reportDate: String,
    ): PartyExposureReport {
        val result = rep.optJSONArray("result") ?: throw ErpHttp.unexpectedResponse()
        val columns = rep.optJSONArray("columns") ?: throw ErpHttp.unexpectedResponse()
        if (result.length() == 0) {
            return PartyExposureReport(
                kind = kind,
                reportDate = reportDate,
                totalOutstandingMinor = 0L,
                totalInvoicedMinor = 0L,
                totalPaidMinor = 0L,
                totalAdvanceMinor = 0L,
                totalCreditNoteMinor = 0L,
                buckets = AgeingBuckets(),
                rows = emptyList(),
            )
        }

        val colIndex = HashMap<String, Int>()
        val rangeCols = mutableListOf<Pair<String, Int>>()
        val rangeRegex = Regex("^range(\\d+)$")
        for (i in 0 until columns.length()) {
            val col = columns.optJSONObject(i) ?: continue
            val fn = col.optString("fieldname", "")
            if (fn.isNotEmpty()) {
                colIndex[fn] = i
                val m = rangeRegex.matchEntire(fn)
                if (m != null) {
                    rangeCols.add(fn to i)
                }
            }
        }

        val rows = mutableListOf<PartyExposureRow>()
        var totalArr: JSONArray? = null

        for (i in 0 until result.length()) {
            val item = result.opt(i)
            when (item) {
                is JSONArray -> if (totalArr == null) totalArr = item
                is JSONObject -> {
                    val party = item.optString("party", "").trim()
                    if (party.isEmpty()) continue
                    val buckets = AgeingBuckets(
                        b0To30 = rangeCols.getOrNull(0)?.let { safeMinor(item.opt(it.first)) } ?: 0L,
                        b31To60 = rangeCols.getOrNull(1)?.let { safeMinor(item.opt(it.first)) } ?: 0L,
                        b61To90 = rangeCols.getOrNull(2)?.let { safeMinor(item.opt(it.first)) } ?: 0L,
                        b91To120 = rangeCols.getOrNull(3)?.let { safeMinor(item.opt(it.first)) } ?: 0L,
                        b120Plus = rangeCols.getOrNull(4)?.let { safeMinor(item.opt(it.first)) } ?: 0L,
                    )
                    val partyType = if (item.has("party_type") && !item.isNull("party_type")) {
                        item.optString("party_type", "").takeIf { it.isNotBlank() }
                    } else null
                    val outMinor = safeMinor(item.opt("outstanding"))
                    val totalDue = if (item.has("total_due") && !item.isNull("total_due")) {
                        safeMinor(item.opt("total_due"))
                    } else outMinor
                    rows.add(
                        PartyExposureRow(
                            party = party,
                            partyType = partyType,
                            invoicedMinor = safeMinor(item.opt("invoiced")),
                            paidMinor = safeMinor(item.opt("paid")),
                            advanceMinor = safeMinor(item.opt("advance")),
                            creditNoteMinor = safeMinor(item.opt("credit_note")),
                            outstandingMinor = outMinor,
                            totalDueMinor = totalDue,
                            buckets = buckets,
                        )
                    )
                }
            }
        }

        fun arrVal(fieldname: String): Long? {
            val idx = colIndex[fieldname] ?: return null
            val arr = totalArr ?: return null
            if (idx !in 0 until arr.length()) return null
            return safeMinor(arr.opt(idx))
        }

        val totalBuckets = if (totalArr != null && rangeCols.isNotEmpty()) {
            AgeingBuckets(
                b0To30 = rangeCols.getOrNull(0)?.let { safeMinor(totalArr.opt(it.second)) } ?: 0L,
                b31To60 = rangeCols.getOrNull(1)?.let { safeMinor(totalArr.opt(it.second)) } ?: 0L,
                b61To90 = rangeCols.getOrNull(2)?.let { safeMinor(totalArr.opt(it.second)) } ?: 0L,
                b91To120 = rangeCols.getOrNull(3)?.let { safeMinor(totalArr.opt(it.second)) } ?: 0L,
                b120Plus = rangeCols.getOrNull(4)?.let { safeMinor(totalArr.opt(it.second)) } ?: 0L,
            )
        } else {
            AgeingBuckets(
                b0To30 = rows.sumOf { it.buckets.b0To30 },
                b31To60 = rows.sumOf { it.buckets.b31To60 },
                b61To90 = rows.sumOf { it.buckets.b61To90 },
                b91To120 = rows.sumOf { it.buckets.b91To120 },
                b120Plus = rows.sumOf { it.buckets.b120Plus },
            )
        }

        return PartyExposureReport(
            kind = kind,
            reportDate = reportDate,
            totalOutstandingMinor = arrVal("outstanding") ?: rows.sumOf { it.outstandingMinor },
            totalInvoicedMinor = arrVal("invoiced") ?: rows.sumOf { it.invoicedMinor },
            totalPaidMinor = arrVal("paid") ?: rows.sumOf { it.paidMinor },
            totalAdvanceMinor = arrVal("advance") ?: rows.sumOf { it.advanceMinor },
            totalCreditNoteMinor = arrVal("credit_note") ?: rows.sumOf { it.creditNoteMinor },
            buckets = totalBuckets,
            rows = rows.sortedByDescending { it.outstandingMinor },
        )
    }

    suspend fun getExposureReport(
        c: ErpClient,
        company: String,
        kind: ExposureKind,
        reportDate: String = localIsoDate(),
    ): PartyExposureReport {
        val args = JSONObject()
            .put("report_name", kind.reportName)
            .put(
                "filters",
                JSONObject()
                    .put("company", company)
                    .put("report_date", reportDate)
                    .put("ageing_based_on", "Due Date")
                    .put("range", "30, 60, 90, 120"),
            )
            .put("ignore_prepared_report", true)
        val rep = c.callAny("frappe.desk.query_report.run", args) as? JSONObject
            ?: throw ErpHttp.unexpectedResponse()
        return parseExposureResponse(kind, rep, reportDate)
    }

    fun indianFiscalYearFallback(today: LocalDate = LocalDate.now()): FiscalYearInfo {
        val startYear = if (today.monthValue >= 4) today.year else today.year - 1
        val start = LocalDate.of(startYear, 4, 1)
        val end = LocalDate.of(startYear + 1, 3, 31)
        return FiscalYearInfo(
            name = "$startYear-${startYear + 1}",
            startDate = start.toString(),
            endDate = end.toString(),
        )
    }

    suspend fun resolveCurrentFiscalYear(c: ErpClient, today: LocalDate = LocalDate.now()): FiscalYearInfo {
        val todayStr = today.toString()
        return try {
            val r = c.getObject(
                "/api/resource/Fiscal%20Year",
                mapOf(
                    "fields" to JSONArray().put("name").put("year_start_date").put("year_end_date"),
                    "filters" to JSONArray().put(JSONArray().put("disabled").put("=").put(0)),
                    "limit_page_length" to 0,
                ),
            )
            val arr = r.optJSONArray("data") ?: return indianFiscalYearFallback(today)
            val years = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name", "").trim()
                val start = o.optString("year_start_date", "").trim()
                val end = o.optString("year_end_date", "").trim()
                if (name.isEmpty() || start.length < 10 || end.length < 10) return@mapNotNull null
                FiscalYearInfo(name, start.take(10), end.take(10))
            }
            years.firstOrNull { todayStr >= it.startDate && todayStr <= it.endDate }
                ?: years.maxByOrNull { it.endDate }
                ?: indianFiscalYearFallback(today)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ErpAuthError) {
            if (e.status == 401) throw e
            indianFiscalYearFallback(today)
        } catch (_: Throwable) {
            indianFiscalYearFallback(today)
        }
    }

    private val MONTH_ABBR_MAP = mapOf(
        "jan" to 1, "january" to 1,
        "feb" to 2, "february" to 2,
        "mar" to 3, "march" to 3,
        "apr" to 4, "april" to 4,
        "may" to 5,
        "jun" to 6, "june" to 6,
        "jul" to 7, "july" to 7,
        "aug" to 8, "august" to 8,
        "sep" to 9, "sept" to 9, "september" to 9,
        "oct" to 10, "october" to 10,
        "nov" to 11, "november" to 11,
        "dec" to 12, "december" to 12,
    )

    internal fun parseColumnYearMonth(fieldname: String, label: String): YearMonth? {
        val candidates = listOf(label.trim(), fieldname.trim())
        val isoYm = Regex("(\\d{4})[-_](\\d{1,2})")
        val textYm = Regex("([A-Za-z]{3,9})[\\s_-]+(\\d{2,4})")
        for (raw in candidates) {
            if (raw.isEmpty()) continue
            val mIso = isoYm.find(raw)
            if (mIso != null) {
                val yr = mIso.groupValues[1].toIntOrNull()
                val mo = mIso.groupValues[2].toIntOrNull()
                if (yr != null && mo != null && mo in 1..12) {
                    return YearMonth.of(yr, mo)
                }
            }
            val mText = textYm.find(raw)
            if (mText != null) {
                val mo = MONTH_ABBR_MAP[mText.groupValues[1].lowercase(Locale.US)]
                val yrRaw = mText.groupValues[2].toIntOrNull()
                if (mo != null && yrRaw != null) {
                    val yr = if (yrRaw < 100) 2000 + yrRaw else yrRaw
                    return YearMonth.of(yr, mo)
                }
            }
        }
        return null
    }

    private data class PnlMonthCol(
        val fieldname: String,
        val ym: YearMonth,
        val label: String,
    )

    internal fun parseProfitAndLossResponse(
        rep: JSONObject,
        fy: FiscalYearInfo,
        today: LocalDate,
    ): ProfitAndLossData {
        val columns = rep.optJSONArray("columns") ?: throw ErpHttp.unexpectedResponse()
        val result = rep.optJSONArray("result") ?: throw ErpHttp.unexpectedResponse()

        val monthFormatter = DateTimeFormatter.ofPattern("MMM yy", Locale.US)
        val monthCols = mutableListOf<PnlMonthCol>()
        for (i in 0 until columns.length()) {
            val col = columns.optJSONObject(i) ?: continue
            val fn = col.optString("fieldname", "")
            val lbl = col.optString("label", fn)
            if (fn.isEmpty() || fn in setOf("account", "account_name", "parent_account", "currency", "total")) continue
            val ym = parseColumnYearMonth(fn, lbl) ?: continue
            monthCols.add(PnlMonthCol(fn, ym, ym.format(monthFormatter)))
        }
        val sortedMonthCols = monthCols.distinctBy { it.ym }.sortedBy { it.ym }

        val fyStartYm = YearMonth.from(LocalDate.parse(fy.startDate))
        val fyEndYm = YearMonth.from(LocalDate.parse(fy.endDate))
        val currentYm = YearMonth.from(today)

        val fyMonthCols = sortedMonthCols.filter { it.ym >= fyStartYm && it.ym <= fyEndYm }
        val mtdCol = sortedMonthCols.firstOrNull { it.ym == currentYm }

        var currentSection: PnlRootType? = null
        val monthlyIncome = sortedMonthCols.associate { it.ym to 0L }.toMutableMap()
        val monthlyExpense = sortedMonthCols.associate { it.ym to 0L }.toMutableMap()
        var foundIncomeSummaryRow = false
        var foundExpenseSummaryRow = false
        val leafIncomeMonthly = sortedMonthCols.associate { it.ym to 0L }.toMutableMap()
        val leafExpenseMonthly = sortedMonthCols.associate { it.ym to 0L }.toMutableMap()
        val lines = mutableListOf<PnlLineItem>()

        for (i in 0 until result.length()) {
            val row = result.optJSONObject(i) ?: continue
            if (row.length() == 0) continue

            val account = row.optString("account", "").trim()
            val accountName = row.optString("account_name", account).trim().ifEmpty { account }
            if (account.isEmpty() && accountName.isEmpty()) continue

            val rawNameLower = accountName.lowercase(Locale.US)
            val rawAccLower = account.lowercase(Locale.US)
            val indent = row.optInt("indent", 0)
            val isGroup = row.optInt("is_group", 0) != 0 || row.optBoolean("is_group", false)

            // Detect ERPNext summary/header rows
            val isTotalIncomeRow = rawNameLower.contains("total income") || rawAccLower.contains("total income")
            val isTotalExpenseRow = rawNameLower.contains("total expense") || rawAccLower.contains("total expense")
            val isProfitRow = rawNameLower.contains("profit for the year") ||
                rawNameLower.contains("net profit") ||
                rawNameLower.contains("provisional profit") ||
                rawAccLower.contains("profit for the year")

            if (isProfitRow) continue

            if (isTotalIncomeRow) {
                foundIncomeSummaryRow = true
                sortedMonthCols.forEach { col ->
                    monthlyIncome[col.ym] = safeMinor(row.opt(col.fieldname))
                }
                continue
            }
            if (isTotalExpenseRow) {
                foundExpenseSummaryRow = true
                sortedMonthCols.forEach { col ->
                    monthlyExpense[col.ym] = safeMinor(row.opt(col.fieldname))
                }
                continue
            }

            val rootStr = row.optString("root_type", "").lowercase(Locale.US)
            val rowRoot = when {
                rootStr == "income" -> PnlRootType.INCOME
                rootStr == "expense" -> PnlRootType.EXPENSE
                indent == 0 && rawNameLower == "income" -> PnlRootType.INCOME
                indent == 0 && rawNameLower == "expenses" -> PnlRootType.EXPENSE
                indent == 0 && rawNameLower == "expense" -> PnlRootType.EXPENSE
                else -> currentSection
            }
            if (rowRoot != null) currentSection = rowRoot

            val effectiveRoot = rowRoot ?: continue

            val mtdMinor = mtdCol?.let { safeMinor(row.opt(it.fieldname)) } ?: 0L
            val fyMinor = fyMonthCols.sumOf { safeMinor(row.opt(it.fieldname)) }

            if (!isGroup && indent > 0) {
                sortedMonthCols.forEach { col ->
                    val amt = safeMinor(row.opt(col.fieldname))
                    if (effectiveRoot == PnlRootType.INCOME) {
                        leafIncomeMonthly[col.ym] = (leafIncomeMonthly[col.ym] ?: 0L) + amt
                    } else {
                        leafExpenseMonthly[col.ym] = (leafExpenseMonthly[col.ym] ?: 0L) + amt
                    }
                }
            }

            // Skip top-level root headers when building account lines if they duplicate totals, keep indented groups/leaves
            if (indent > 0 || !isGroup) {
                if (mtdMinor != 0L || fyMinor != 0L) {
                    lines.add(
                        PnlLineItem(
                            account = account.ifEmpty { accountName },
                            label = accountName,
                            rootType = effectiveRoot,
                            indent = indent,
                            isGroup = isGroup,
                            mtdMinor = mtdMinor,
                            fyMinor = fyMinor,
                        )
                    )
                }
            }
        }

        val finalIncomeMonthly = if (foundIncomeSummaryRow) monthlyIncome else leafIncomeMonthly
        val finalExpenseMonthly = if (foundExpenseSummaryRow) monthlyExpense else leafExpenseMonthly

        val months = sortedMonthCols.map { col ->
            MonthPerformance(
                ym = col.ym.toString(),
                label = col.label,
                incomeMinor = finalIncomeMonthly[col.ym] ?: 0L,
                expenseMinor = finalExpenseMonthly[col.ym] ?: 0L,
            )
        }

        val mtdIncome = months.firstOrNull { it.ym == currentYm.toString() }?.incomeMinor ?: 0L
        val mtdExpense = months.firstOrNull { it.ym == currentYm.toString() }?.expenseMinor ?: 0L
        val fyMonths = months.filter {
            val ym = YearMonth.parse(it.ym)
            ym >= fyStartYm && ym <= fyEndYm
        }
        val fyIncome = fyMonths.sumOf { it.incomeMinor }
        val fyExpense = fyMonths.sumOf { it.expenseMinor }

        return ProfitAndLossData(
            fiscalYear = fy,
            asOfDate = today.toString(),
            months = months,
            mtdIncomeMinor = mtdIncome,
            mtdExpenseMinor = mtdExpense,
            fyIncomeMinor = fyIncome,
            fyExpenseMinor = fyExpense,
            lines = lines,
            fromFallback = false,
        )
    }

    private suspend fun getProfitAndLossFromGl(
        c: ErpClient,
        company: String,
        fy: FiscalYearInfo,
        windowStart: LocalDate,
        today: LocalDate,
    ): ProfitAndLossData {
        val incomeAccounts = ErpDocs.listLedgerAccounts(c, company, "Income").mapTo(HashSet()) { it.name }
        val expenseAccounts = ErpDocs.listLedgerAccounts(c, company, "Expense").mapTo(HashSet()) { it.name }
        val allAccounts = incomeAccounts + expenseAccounts

        val startYm = YearMonth.from(windowStart)
        val endYm = YearMonth.from(today)
        val fyStartYm = YearMonth.from(LocalDate.parse(fy.startDate))
        val fyEndYm = YearMonth.from(LocalDate.parse(fy.endDate))
        val currentYm = YearMonth.from(today)

        val monthList = generateSequence(startYm) { cur ->
            if (cur < endYm) cur.plusMonths(1) else null
        }.toList()

        val monthIncome = monthList.associateWith { 0L }.toMutableMap()
        val monthExpense = monthList.associateWith { 0L }.toMutableMap()
        val accMtd = HashMap<String, Long>()
        val accFy = HashMap<String, Long>()

        if (allAccounts.isNotEmpty()) {
            val accArr = JSONArray().apply { allAccounts.forEach { put(it) } }
            val fields = JSONArray().put("posting_date").put("account").put("debit").put("credit")
            val filters = JSONArray()
                .put(JSONArray().put("company").put("=").put(company))
                .put(JSONArray().put("is_cancelled").put("=").put(0))
                .put(JSONArray().put("posting_date").put(">=").put(windowStart.toString()))
                .put(JSONArray().put("posting_date").put("<=").put(today.toString()))
                .put(JSONArray().put("account").put("in").put(accArr))

            var page = 0
            val pageSize = 500
            while (page < 6) {
                val r = c.getObject(
                    "/api/resource/GL%20Entry",
                    mapOf(
                        "fields" to fields,
                        "filters" to filters,
                        "limit_start" to page * pageSize,
                        "limit_page_length" to pageSize,
                    ),
                )
                val rows = r.optJSONArray("data") ?: break
                for (i in 0 until rows.length()) {
                    val o = rows.optJSONObject(i) ?: continue
                    val pd = o.optString("posting_date", "").take(10)
                    if (pd.length < 7) continue
                    val ym = try {
                        YearMonth.parse(pd.substring(0, 7))
                    } catch (_: Exception) {
                        continue
                    }
                    val acc = o.optString("account", "")
                    val debit = safeMinor(o.opt("debit"))
                    val credit = safeMinor(o.opt("credit"))
                    when (acc) {
                        in incomeAccounts -> {
                            val net = credit - debit
                            monthIncome[ym] = (monthIncome[ym] ?: 0L) + net
                            if (ym == currentYm) accMtd[acc] = (accMtd[acc] ?: 0L) + net
                            if (ym >= fyStartYm && ym <= fyEndYm) accFy[acc] = (accFy[acc] ?: 0L) + net
                        }
                        in expenseAccounts -> {
                            val net = debit - credit
                            monthExpense[ym] = (monthExpense[ym] ?: 0L) + net
                            if (ym == currentYm) accMtd[acc] = (accMtd[acc] ?: 0L) + net
                            if (ym >= fyStartYm && ym <= fyEndYm) accFy[acc] = (accFy[acc] ?: 0L) + net
                        }
                    }
                }
                if (rows.length() < pageSize) break
                page++
            }
        }

        val monthFormatter = DateTimeFormatter.ofPattern("MMM yy", Locale.US)
        val months = monthList.map { ym ->
            MonthPerformance(
                ym = ym.toString(),
                label = ym.format(monthFormatter),
                incomeMinor = monthIncome[ym] ?: 0L,
                expenseMinor = monthExpense[ym] ?: 0L,
            )
        }
        val lines = (incomeAccounts.map { it to PnlRootType.INCOME } + expenseAccounts.map { it to PnlRootType.EXPENSE })
            .mapNotNull { (acc, root) ->
                val mtd = accMtd[acc] ?: 0L
                val fyVal = accFy[acc] ?: 0L
                if (mtd == 0L && fyVal == 0L) null
                else PnlLineItem(
                    account = acc,
                    label = acc.substringBeforeLast(" - ").ifBlank { acc },
                    rootType = root,
                    indent = 1,
                    isGroup = false,
                    mtdMinor = mtd,
                    fyMinor = fyVal,
                )
            }
            .sortedByDescending { kotlin.math.abs(it.fyMinor) }

        val mtdIncome = monthIncome[currentYm] ?: 0L
        val mtdExpense = monthExpense[currentYm] ?: 0L
        val fyMonths = months.filter {
            val ym = YearMonth.parse(it.ym)
            ym >= fyStartYm && ym <= fyEndYm
        }

        return ProfitAndLossData(
            fiscalYear = fy,
            asOfDate = today.toString(),
            months = months,
            mtdIncomeMinor = mtdIncome,
            mtdExpenseMinor = mtdExpense,
            fyIncomeMinor = fyMonths.sumOf { it.incomeMinor },
            fyExpenseMinor = fyMonths.sumOf { it.expenseMinor },
            lines = lines,
            fromFallback = true,
        )
    }

    suspend fun getProfitAndLoss(
        c: ErpClient,
        company: String,
        today: LocalDate = LocalDate.now(),
    ): ProfitAndLossData {
        val fy = resolveCurrentFiscalYear(c, today)
        val fyStart = try {
            LocalDate.parse(fy.startDate)
        } catch (_: Exception) {
            LocalDate.parse(indianFiscalYearFallback(today).startDate)
        }
        val threeMonthsBackStart = today.withDayOfMonth(1).minusMonths(3)
        val windowStart = if (threeMonthsBackStart.isBefore(fyStart)) threeMonthsBackStart else fyStart

        return try {
            val args = JSONObject()
                .put("report_name", "Profit and Loss Statement")
                .put(
                    "filters",
                    JSONObject()
                        .put("company", company)
                        .put("filter_based_on", "Date Range")
                        .put("period_start_date", windowStart.toString())
                        .put("period_end_date", today.toString())
                        .put("periodicity", "Monthly"),
                )
                .put("ignore_prepared_report", true)
            val rep = c.callAny("frappe.desk.query_report.run", args) as? JSONObject
                ?: throw ErpHttp.unexpectedResponse()
            parseProfitAndLossResponse(rep, fy, today)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ErpAuthError) {
            if (e.status != 403) throw e
            getProfitAndLossFromGl(c, company, fy, windowStart, today)
        } catch (e: ErpValidationError) {
            // Fallback to GL Entry aggregation if script report filter shape differs on customized site
            try {
                getProfitAndLossFromGl(c, company, fy, windowStart, today)
            } catch (_: Throwable) {
                throw e
            }
        }
    }
}
