package app.onebook.autoledger.reports

import app.onebook.autoledger.core.AccountMapping
import app.onebook.autoledger.core.AccountType
import app.onebook.autoledger.data.Repo
import app.onebook.autoledger.erp.AgeingBucket
import app.onebook.autoledger.erp.AgeingBuckets
import app.onebook.autoledger.erp.ErpAuthError
import app.onebook.autoledger.erp.ErpClient
import app.onebook.autoledger.erp.ErpDocs
import app.onebook.autoledger.erp.ErpReports
import app.onebook.autoledger.erp.ExposureKind
import app.onebook.autoledger.erp.FiscalYearInfo
import app.onebook.autoledger.erp.MonthPerformance
import app.onebook.autoledger.erp.PartyExposureReport
import app.onebook.autoledger.erp.PartyExposureRow
import app.onebook.autoledger.erp.PnlLineItem
import app.onebook.autoledger.erp.PnlRootType
import app.onebook.autoledger.erp.ProfitAndLossData
import app.onebook.autoledger.state.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class ReportSection(val label: String) {
    MIS("MIS"),
    PNL("P&L"),
    AR("AR"),
    AP("AP"),
}

enum class PnlPeriod(val label: String) {
    MTD("This Month (MTD)"),
    FY("Current FY"),
}

enum class ExposureSort(val label: String) {
    HIGHEST_DUE("Highest due"),
    MOST_OVERDUE("Most overdue"),
    NAME_AZ("Name A\u2013Z"),
}

data class BankBalanceItem(
    val name: String,
    val glAccount: String,
    val kind: AccountType,
    val balanceMinor: Long,
)

data class RunwayAnalysis(
    val liquidCashMinor: Long,
    val cardBalanceMinor: Long,
    val threeMonthIncomeMinor: Long,
    val threeMonthExpenseMinor: Long,
    val avgMonthlyNetBurnMinor: Long,
    val avgMonthlyNetSurplusMinor: Long,
    val runwayMonths: Double?,
    val projectedZeroDate: String?,
    val burnMonthsUsed: List<MonthPerformance>,
    val isCashFlowPositive: Boolean,
)

data class ReportsSnapshot(
    val company: String,
    val fetchedAt: Long,
    val stale: Boolean = false,
    val liquidCashMinor: Long,
    val cardBalanceMinor: Long,
    val accountBalances: List<BankBalanceItem>,
    val arReport: PartyExposureReport?,
    val apReport: PartyExposureReport?,
    val pnlData: ProfitAndLossData?,
    val runway: RunwayAnalysis?,
    val arError: String? = null,
    val apError: String? = null,
    val pnlError: String? = null,
    val cashError: String? = null,
    val authExpired: Boolean = false,
) {
    val workingCapitalGapMinor: Long?
        get() = if (arReport != null && apReport != null) {
            arReport.totalOutstandingMinor - apReport.totalOutstandingMinor
        } else null
}

enum class ReportsLoadStatus { LOADING, READY, ERROR }

data class ReportsUiState(
    val status: ReportsLoadStatus = ReportsLoadStatus.LOADING,
    val snapshot: ReportsSnapshot? = null,
    val errorMessage: String? = null,
    val refreshing: Boolean = false,
)

object ReportsLogic {
    const val REPORTS_CACHE_KEY = "reports_cache_v1"

    fun marginPct(incomeMinor: Long, expenseMinor: Long): Int? {
        if (incomeMinor <= 0L) return null
        val net = incomeMinor - expenseMinor
        return ((net.toDouble() / incomeMinor.toDouble()) * 100.0).roundToInt()
    }

    fun selectTrailingBurnMonths(
        months: List<MonthPerformance>,
        today: LocalDate = LocalDate.now(),
    ): List<MonthPerformance> {
        if (months.isEmpty()) return emptyList()
        val currentYm = YearMonth.from(today).toString()
        val completed = months.filter { it.ym < currentYm }.sortedBy { it.ym }
        val nonZeroCompleted = completed.filter { it.incomeMinor != 0L || it.expenseMinor != 0L }
        return when {
            completed.size >= 3 && nonZeroCompleted.isNotEmpty() -> completed.takeLast(3)
            completed.isNotEmpty() && nonZeroCompleted.isNotEmpty() -> completed.takeLast(3)
            else -> months.sortedBy { it.ym }.takeLast(3)
        }
    }

    fun computeRunway(
        liquidCashMinor: Long,
        cardBalanceMinor: Long,
        months: List<MonthPerformance>,
        today: LocalDate = LocalDate.now(),
    ): RunwayAnalysis {
        val burnMonths = selectTrailingBurnMonths(months, today)
        if (burnMonths.isEmpty()) {
            return RunwayAnalysis(
                liquidCashMinor = liquidCashMinor,
                cardBalanceMinor = cardBalanceMinor,
                threeMonthIncomeMinor = 0L,
                threeMonthExpenseMinor = 0L,
                avgMonthlyNetBurnMinor = 0L,
                avgMonthlyNetSurplusMinor = 0L,
                runwayMonths = null,
                projectedZeroDate = null,
                burnMonthsUsed = emptyList(),
                isCashFlowPositive = false,
            )
        }
        val count = burnMonths.size.toLong()
        val totalIn = burnMonths.sumOf { it.incomeMinor }
        val totalOut = burnMonths.sumOf { it.expenseMinor }
        val netDelta = totalOut - totalIn // positive means net burn
        val isPositive = netDelta <= 0L
        val avgBurn = if (netDelta > 0L) (netDelta.toDouble() / count).roundToLong() else 0L
        val avgSurplus = if (netDelta <= 0L) ((-netDelta).toDouble() / count).roundToLong() else 0L

        val runwayMonths = if (!isPositive && avgBurn > 0L) {
            max(0L, liquidCashMinor).toDouble() / avgBurn.toDouble()
        } else {
            null
        }
        val projectedZeroDate = runwayMonths?.let { m ->
            today.plusDays((m * 30.4375).roundToLong()).toString()
        }

        return RunwayAnalysis(
            liquidCashMinor = liquidCashMinor,
            cardBalanceMinor = cardBalanceMinor,
            threeMonthIncomeMinor = totalIn,
            threeMonthExpenseMinor = totalOut,
            avgMonthlyNetBurnMinor = avgBurn,
            avgMonthlyNetSurplusMinor = avgSurplus,
            runwayMonths = runwayMonths,
            projectedZeroDate = projectedZeroDate,
            burnMonthsUsed = burnMonths,
            isCashFlowPositive = isPositive,
        )
    }

    fun filterAndSortExposure(
        rows: List<PartyExposureRow>,
        query: String,
        bucket: AgeingBucket?,
        sort: ExposureSort,
    ): List<PartyExposureRow> {
        val q = query.trim().lowercase()
        val filtered = rows.filter { row ->
            val matchesQuery = q.isEmpty() || row.party.lowercase().contains(q)
            val matchesBucket = bucket == null || row.buckets.amountFor(bucket) > 0L
            matchesQuery && matchesBucket
        }
        return when (sort) {
            ExposureSort.HIGHEST_DUE -> filtered.sortedByDescending {
                bucket?.let { b -> it.buckets.amountFor(b) } ?: it.outstandingMinor
            }
            ExposureSort.MOST_OVERDUE -> filtered.sortedByDescending { it.buckets.overdueMinor }
            ExposureSort.NAME_AZ -> filtered.sortedBy { it.party.lowercase() }
        }
    }

    fun formatRunwayHeadline(runway: RunwayAnalysis?): String {
        if (runway == null || runway.burnMonthsUsed.isEmpty()) return "Runway unavailable"
        if (runway.isCashFlowPositive) return "Cash-flow positive"
        val m = runway.runwayMonths ?: return "Runway unavailable"
        return if (m >= 24.0) "24+ mo runway" else String.format(java.util.Locale.US, "%.1f mo runway", m)
    }

    private fun friendlyReportError(t: Throwable, label: String): String = when (t) {
        is ErpAuthError -> if (t.status == 401) {
            "Session expired. Sign in again to refresh $label."
        } else {
            "Your ERPNext user does not have permission to read $label."
        }
        else -> "Could not load $label from ERPNext."
    }

    suspend fun loadReportsSnapshot(
        repo: Repo,
        client: ErpClient?,
        siteUrl: String?,
        now: () -> Long = System::currentTimeMillis,
        today: () -> LocalDate = LocalDate::now,
    ): ReportsUiState {
        if (siteUrl == null) return ReportsUiState(status = ReportsLoadStatus.LOADING)
        val company = repo.getSetting(AppContainer.COMPANY_KEY)
        if (company.isNullOrBlank()) {
            return ReportsUiState(
                status = ReportsLoadStatus.ERROR,
                errorMessage = "Complete company setup first to view financial reports.",
            )
        }
        val cached = parseReportsCache(repo.getSetting(REPORTS_CACHE_KEY), siteUrl, company)
        if (client == null) {
            return if (cached != null) {
                ReportsUiState(status = ReportsLoadStatus.READY, snapshot = cached.copy(stale = true))
            } else {
                ReportsUiState(status = ReportsLoadStatus.ERROR, errorMessage = "Sign in to load ERPNext reports.")
            }
        }

        val mappings = repo.listMappings()
        val todayDate = today()
        val todayStr = todayDate.toString()

        return try {
            val cashResult: Result<List<BankBalanceItem>>
            val arResult: Result<PartyExposureReport>
            val apResult: Result<PartyExposureReport>
            val pnlResult: Result<ProfitAndLossData>

            coroutineScope {
                val cashDeferred = async {
                    runCatching {
                        val effectiveMappings: List<AccountMapping> = if (mappings.isNotEmpty()) {
                            mappings
                        } else {
                            ErpDocs.listBankAccounts(client, company)
                                .filter { it.glAccount.isNotBlank() }
                                .map { b ->
                                    AccountMapping(
                                        bankAccount = b.name,
                                        glAccount = b.glAccount,
                                        kind = if (b.name.contains("card", ignoreCase = true)) AccountType.CARD else AccountType.BANK,
                                        senderIds = emptyList(),
                                        last4 = emptyList(),
                                        modeOfPayment = "",
                                    )
                                }
                        }
                        val balList = effectiveMappings.map { m ->
                            async {
                                BankBalanceItem(
                                    name = m.bankAccount,
                                    glAccount = m.glAccount,
                                    kind = m.kind,
                                    balanceMinor = ErpReports.getBankBalance(client, m.glAccount, company),
                                )
                            }
                        }.awaitAll()
                        balList
                    }
                }
                val arDeferred = async {
                    runCatching { ErpReports.getExposureReport(client, company, ExposureKind.AR, todayStr) }
                }
                val apDeferred = async {
                    runCatching { ErpReports.getExposureReport(client, company, ExposureKind.AP, todayStr) }
                }
                val pnlDeferred = async {
                    runCatching { ErpReports.getProfitAndLoss(client, company, todayDate) }
                }

                cashResult = cashDeferred.await()
                arResult = arDeferred.await()
                apResult = apDeferred.await()
                pnlResult = pnlDeferred.await()
            }

            listOf(cashResult, arResult, apResult, pnlResult).forEach { r ->
                val ex = r.exceptionOrNull()
                if (ex is CancellationException) throw ex
            }

            val allFailed = cashResult.isFailure && arResult.isFailure && apResult.isFailure && pnlResult.isFailure
            val any401 = listOf(cashResult, arResult, apResult, pnlResult).any {
                (it.exceptionOrNull() as? ErpAuthError)?.status == 401
            }

            if (allFailed) {
                if (cached != null) {
                    return ReportsUiState(
                        status = ReportsLoadStatus.READY,
                        snapshot = cached.copy(stale = true, authExpired = any401),
                    )
                }
                val firstErr = cashResult.exceptionOrNull()
                    ?: pnlResult.exceptionOrNull()
                    ?: arResult.exceptionOrNull()
                    ?: apResult.exceptionOrNull()
                return ReportsUiState(
                    status = ReportsLoadStatus.ERROR,
                    errorMessage = firstErr?.let { friendlyReportError(it, "ERPNext reports") }
                        ?: "Could not reach ERPNext right now.",
                )
            }

            val balances = cashResult.getOrElse { cached?.accountBalances.orEmpty() }
            val liquidCash = balances.filter { it.kind == AccountType.BANK }.sumOf { it.balanceMinor }
            val cardBalance = balances.filter { it.kind == AccountType.CARD }.sumOf { it.balanceMinor }
            val arReport = arResult.getOrNull() ?: cached?.arReport
            val apReport = apResult.getOrNull() ?: cached?.apReport
            val pnlData = pnlResult.getOrNull() ?: cached?.pnlData
            val runway = pnlData?.let {
                computeRunway(
                    liquidCashMinor = liquidCash,
                    cardBalanceMinor = cardBalance,
                    months = it.months,
                    today = todayDate,
                )
            }

            val snapshot = ReportsSnapshot(
                company = company,
                fetchedAt = now(),
                stale = false,
                liquidCashMinor = liquidCash,
                cardBalanceMinor = cardBalance,
                accountBalances = balances,
                arReport = arReport,
                apReport = apReport,
                pnlData = pnlData,
                runway = runway,
                cashError = cashResult.exceptionOrNull()?.let { friendlyReportError(it, "bank balances") },
                arError = arResult.exceptionOrNull()?.let { friendlyReportError(it, "Accounts Receivable") },
                apError = apResult.exceptionOrNull()?.let { friendlyReportError(it, "Accounts Payable") },
                pnlError = pnlResult.exceptionOrNull()?.let { friendlyReportError(it, "Profit & Loss") },
                authExpired = any401,
            )

            try {
                repo.setSetting(REPORTS_CACHE_KEY, serializeReportsCache(siteUrl, snapshot))
            } catch (_: Throwable) {
                // Best effort cache write
            }

            ReportsUiState(status = ReportsLoadStatus.READY, snapshot = snapshot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (cached != null) {
                ReportsUiState(status = ReportsLoadStatus.READY, snapshot = cached.copy(stale = true))
            } else {
                ReportsUiState(
                    status = ReportsLoadStatus.ERROR,
                    errorMessage = friendlyReportError(e, "ERPNext reports"),
                )
            }
        }
    }

    private fun bucketsToJson(b: AgeingBuckets): JSONObject = JSONObject()
        .put("b0", b.b0To30)
        .put("b1", b.b31To60)
        .put("b2", b.b61To90)
        .put("b3", b.b91To120)
        .put("b4", b.b120Plus)

    private fun bucketsFromJson(o: JSONObject?): AgeingBuckets {
        if (o == null) return AgeingBuckets()
        return AgeingBuckets(
            b0To30 = o.optLong("b0", 0L),
            b31To60 = o.optLong("b1", 0L),
            b61To90 = o.optLong("b2", 0L),
            b91To120 = o.optLong("b3", 0L),
            b120Plus = o.optLong("b4", 0L),
        )
    }

    private fun exposureToJson(r: PartyExposureReport): JSONObject = JSONObject()
        .put("kind", r.kind.name)
        .put("reportDate", r.reportDate)
        .put("totalOutstandingMinor", r.totalOutstandingMinor)
        .put("totalInvoicedMinor", r.totalInvoicedMinor)
        .put("totalPaidMinor", r.totalPaidMinor)
        .put("totalAdvanceMinor", r.totalAdvanceMinor)
        .put("totalCreditNoteMinor", r.totalCreditNoteMinor)
        .put("buckets", bucketsToJson(r.buckets))
        .put("rows", JSONArray().apply {
            r.rows.forEach { row ->
                put(
                    JSONObject()
                        .put("party", row.party)
                        .put("partyType", row.partyType)
                        .put("invoicedMinor", row.invoicedMinor)
                        .put("paidMinor", row.paidMinor)
                        .put("advanceMinor", row.advanceMinor)
                        .put("creditNoteMinor", row.creditNoteMinor)
                        .put("outstandingMinor", row.outstandingMinor)
                        .put("totalDueMinor", row.totalDueMinor)
                        .put("buckets", bucketsToJson(row.buckets))
                )
            }
        })

    private fun exposureFromJson(o: JSONObject?): PartyExposureReport? {
        if (o == null) return null
        val kind = try {
            ExposureKind.valueOf(o.optString("kind", ""))
        } catch (_: Exception) {
            return null
        }
        val rowsArr = o.optJSONArray("rows") ?: JSONArray()
        val rows = (0 until rowsArr.length()).mapNotNull { i ->
            val ro = rowsArr.optJSONObject(i) ?: return@mapNotNull null
            PartyExposureRow(
                party = ro.optString("party", ""),
                partyType = ro.optString("partyType", "").takeIf { it.isNotBlank() },
                invoicedMinor = ro.optLong("invoicedMinor", 0L),
                paidMinor = ro.optLong("paidMinor", 0L),
                advanceMinor = ro.optLong("advanceMinor", 0L),
                creditNoteMinor = ro.optLong("creditNoteMinor", 0L),
                outstandingMinor = ro.optLong("outstandingMinor", 0L),
                totalDueMinor = ro.optLong("totalDueMinor", 0L),
                buckets = bucketsFromJson(ro.optJSONObject("buckets")),
            )
        }
        return PartyExposureReport(
            kind = kind,
            reportDate = o.optString("reportDate", ""),
            totalOutstandingMinor = o.optLong("totalOutstandingMinor", 0L),
            totalInvoicedMinor = o.optLong("totalInvoicedMinor", 0L),
            totalPaidMinor = o.optLong("totalPaidMinor", 0L),
            totalAdvanceMinor = o.optLong("totalAdvanceMinor", 0L),
            totalCreditNoteMinor = o.optLong("totalCreditNoteMinor", 0L),
            buckets = bucketsFromJson(o.optJSONObject("buckets")),
            rows = rows,
        )
    }

    fun serializeReportsCache(siteUrl: String, s: ReportsSnapshot): String {
        val balArr = JSONArray().apply {
            s.accountBalances.forEach { b ->
                put(
                    JSONObject()
                        .put("name", b.name)
                        .put("glAccount", b.glAccount)
                        .put("kind", b.kind.name)
                        .put("balanceMinor", b.balanceMinor)
                )
            }
        }
        val pnlObj = s.pnlData?.let { p ->
            JSONObject()
                .put("fyName", p.fiscalYear.name)
                .put("fyStart", p.fiscalYear.startDate)
                .put("fyEnd", p.fiscalYear.endDate)
                .put("asOfDate", p.asOfDate)
                .put("mtdIncomeMinor", p.mtdIncomeMinor)
                .put("mtdExpenseMinor", p.mtdExpenseMinor)
                .put("fyIncomeMinor", p.fyIncomeMinor)
                .put("fyExpenseMinor", p.fyExpenseMinor)
                .put("fromFallback", p.fromFallback)
                .put("months", JSONArray().apply {
                    p.months.forEach { m ->
                        put(
                            JSONObject()
                                .put("ym", m.ym)
                                .put("label", m.label)
                                .put("incomeMinor", m.incomeMinor)
                                .put("expenseMinor", m.expenseMinor)
                        )
                    }
                })
                .put("lines", JSONArray().apply {
                    p.lines.forEach { ln ->
                        put(
                            JSONObject()
                                .put("account", ln.account)
                                .put("label", ln.label)
                                .put("rootType", ln.rootType.name)
                                .put("indent", ln.indent)
                                .put("isGroup", ln.isGroup)
                                .put("mtdMinor", ln.mtdMinor)
                                .put("fyMinor", ln.fyMinor)
                        )
                    }
                })
        }
        return JSONObject()
            .put("site", siteUrl)
            .put("company", s.company)
            .put("fetchedAt", s.fetchedAt)
            .put("liquidCashMinor", s.liquidCashMinor)
            .put("cardBalanceMinor", s.cardBalanceMinor)
            .put("accountBalances", balArr)
            .put("arReport", s.arReport?.let(::exposureToJson))
            .put("apReport", s.apReport?.let(::exposureToJson))
            .put("pnlData", pnlObj)
            .toString()
    }

    fun parseReportsCache(raw: String?, siteUrl: String, company: String): ReportsSnapshot? {
        if (raw.isNullOrBlank()) return null
        return try {
            val o = JSONObject(raw)
            if (o.optString("site", "") != siteUrl) return null
            if (o.optString("company", "") != company) return null
            val balArr = o.optJSONArray("accountBalances") ?: JSONArray()
            val balances = (0 until balArr.length()).mapNotNull { i ->
                val bo = balArr.optJSONObject(i) ?: return@mapNotNull null
                val kind = try {
                    AccountType.valueOf(bo.optString("kind", AccountType.BANK.name))
                } catch (_: Exception) {
                    AccountType.BANK
                }
                BankBalanceItem(
                    name = bo.optString("name", ""),
                    glAccount = bo.optString("glAccount", ""),
                    kind = kind,
                    balanceMinor = bo.optLong("balanceMinor", 0L),
                )
            }
            val ar = exposureFromJson(o.optJSONObject("arReport"))
            val ap = exposureFromJson(o.optJSONObject("apReport"))
            val po = o.optJSONObject("pnlData")
            val pnl = if (po != null) {
                val fy = FiscalYearInfo(
                    name = po.optString("fyName", ""),
                    startDate = po.optString("fyStart", ""),
                    endDate = po.optString("fyEnd", ""),
                )
                val monthsArr = po.optJSONArray("months") ?: JSONArray()
                val months = (0 until monthsArr.length()).mapNotNull { i ->
                    val mo = monthsArr.optJSONObject(i) ?: return@mapNotNull null
                    MonthPerformance(
                        ym = mo.optString("ym", ""),
                        label = mo.optString("label", ""),
                        incomeMinor = mo.optLong("incomeMinor", 0L),
                        expenseMinor = mo.optLong("expenseMinor", 0L),
                    )
                }
                val linesArr = po.optJSONArray("lines") ?: JSONArray()
                val lines = (0 until linesArr.length()).mapNotNull { i ->
                    val lo = linesArr.optJSONObject(i) ?: return@mapNotNull null
                    val rt = try {
                        PnlRootType.valueOf(lo.optString("rootType", PnlRootType.EXPENSE.name))
                    } catch (_: Exception) {
                        PnlRootType.EXPENSE
                    }
                    PnlLineItem(
                        account = lo.optString("account", ""),
                        label = lo.optString("label", ""),
                        rootType = rt,
                        indent = lo.optInt("indent", 1),
                        isGroup = lo.optBoolean("isGroup", false),
                        mtdMinor = lo.optLong("mtdMinor", 0L),
                        fyMinor = lo.optLong("fyMinor", 0L),
                    )
                }
                ProfitAndLossData(
                    fiscalYear = fy,
                    asOfDate = po.optString("asOfDate", ""),
                    months = months,
                    mtdIncomeMinor = po.optLong("mtdIncomeMinor", 0L),
                    mtdExpenseMinor = po.optLong("mtdExpenseMinor", 0L),
                    fyIncomeMinor = po.optLong("fyIncomeMinor", 0L),
                    fyExpenseMinor = po.optLong("fyExpenseMinor", 0L),
                    lines = lines,
                    fromFallback = po.optBoolean("fromFallback", false),
                )
            } else null
            val liquid = o.optLong("liquidCashMinor", 0L)
            val card = o.optLong("cardBalanceMinor", 0L)
            val runway = pnl?.let {
                val asOf = try {
                    LocalDate.parse(it.asOfDate)
                } catch (_: Exception) {
                    LocalDate.now()
                }
                computeRunway(liquid, card, it.months, asOf)
            }
            ReportsSnapshot(
                company = company,
                fetchedAt = o.optLong("fetchedAt", 0L),
                stale = true,
                liquidCashMinor = liquid,
                cardBalanceMinor = card,
                accountBalances = balances,
                arReport = ar,
                apReport = ap,
                pnlData = pnl,
                runway = runway,
            )
        } catch (_: Throwable) {
            null
        }
    }
}
