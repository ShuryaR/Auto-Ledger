package app.onebook.autoledger

import app.onebook.autoledger.core.AccountType
import app.onebook.autoledger.erp.AgeingBucket
import app.onebook.autoledger.erp.AgeingBuckets
import app.onebook.autoledger.erp.ErpReports
import app.onebook.autoledger.erp.ExposureKind
import app.onebook.autoledger.erp.FiscalYearInfo
import app.onebook.autoledger.erp.MonthPerformance
import app.onebook.autoledger.erp.PartyExposureRow
import app.onebook.autoledger.erp.PnlRootType
import app.onebook.autoledger.reports.BankBalanceItem
import app.onebook.autoledger.reports.ExposureSort
import app.onebook.autoledger.reports.ReportsLogic
import app.onebook.autoledger.reports.ReportsSnapshot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class ReportsLogicTest {

    @Test
    fun parseColumnYearMonthHandlesErpNextFormats() {
        assertEquals(YearMonth.of(2026, 4), ErpReports.parseColumnYearMonth("apr_2026", "Apr 2026"))
        assertEquals(YearMonth.of(2026, 11), ErpReports.parseColumnYearMonth("nov_26", "Nov 26"))
        assertEquals(YearMonth.of(2026, 8), ErpReports.parseColumnYearMonth("2026-08", "2026-08"))
        assertEquals(YearMonth.of(2025, 9), ErpReports.parseColumnYearMonth("sept_2025", "Sept 2025"))
        assertNull(ErpReports.parseColumnYearMonth("total", "Total"))
    }

    @Test
    fun parseExposureResponseParsesBucketsAndTotalArrayRow() {
        val columns = JSONArray()
            .put(JSONObject().put("fieldname", "party"))
            .put(JSONObject().put("fieldname", "invoiced"))
            .put(JSONObject().put("fieldname", "paid"))
            .put(JSONObject().put("fieldname", "credit_note"))
            .put(JSONObject().put("fieldname", "outstanding"))
            .put(JSONObject().put("fieldname", "range1"))
            .put(JSONObject().put("fieldname", "range2"))
            .put(JSONObject().put("fieldname", "range3"))
            .put(JSONObject().put("fieldname", "range4"))
            .put(JSONObject().put("fieldname", "range5"))

        val rowAcme = JSONObject()
            .put("party", "Acme Corp")
            .put("party_type", "Customer")
            .put("invoiced", 15000.0)
            .put("paid", 5000.0)
            .put("advance", 0.0)
            .put("credit_note", 0.0)
            .put("outstanding", 10000.0)
            .put("range1", 4000.0)
            .put("range2", 3000.0)
            .put("range3", 2000.0)
            .put("range4", 500.0)
            .put("range5", 500.0)

        val rowBeta = JSONObject()
            .put("party", "Beta Foods")
            .put("invoiced", 2000.0)
            .put("paid", 0.0)
            .put("outstanding", 2000.0)
            .put("range1", 2000.0)
            .put("range2", 0.0)
            .put("range3", 0.0)
            .put("range4", 0.0)
            .put("range5", 0.0)

        val totalRow = JSONArray()
            .put("Total")
            .put(17000.0)
            .put(5000.0)
            .put(0.0)
            .put(12000.0)
            .put(6000.0)
            .put(3000.0)
            .put(2000.0)
            .put(500.0)
            .put(500.0)

        val rep = JSONObject()
            .put("columns", columns)
            .put("result", JSONArray().put(rowBeta).put(rowAcme).put(totalRow))

        val parsed = ErpReports.parseExposureResponse(ExposureKind.AR, rep, "2026-11-18")
        assertEquals(1200000L, parsed.totalOutstandingMinor)
        assertEquals(1700000L, parsed.totalInvoicedMinor)
        assertEquals(600000L, parsed.buckets.b0To30)
        assertEquals(300000L, parsed.buckets.b31To60)
        assertEquals(200000L, parsed.buckets.b61To90)
        assertEquals(50000L, parsed.buckets.b91To120)
        assertEquals(50000L, parsed.buckets.b120Plus)
        assertEquals(600000L, parsed.buckets.overdueMinor)
        assertEquals(2, parsed.rows.size)
        // Sorted highest due first
        assertEquals("Acme Corp", parsed.rows[0].party)
        assertEquals("Beta Foods", parsed.rows[1].party)
    }

    @Test
    fun parseProfitAndLossResponseExtractsMtdFyAndLines() {
        val fy = FiscalYearInfo("2026-2027", "2026-04-01", "2027-03-31")
        val today = LocalDate.of(2026, 11, 18)

        val columns = JSONArray()
            .put(JSONObject().put("fieldname", "account").put("label", "Account"))
            .put(JSONObject().put("fieldname", "aug_2026").put("label", "Aug 2026"))
            .put(JSONObject().put("fieldname", "sep_2026").put("label", "Sep 2026"))
            .put(JSONObject().put("fieldname", "oct_2026").put("label", "Oct 2026"))
            .put(JSONObject().put("fieldname", "nov_2026").put("label", "Nov 2026"))

        val result = JSONArray()
            .put(
                JSONObject()
                    .put("account", "Income")
                    .put("account_name", "Income")
                    .put("root_type", "Income")
                    .put("indent", 0)
                    .put("is_group", 1)
                    .put("aug_2026", 10000)
                    .put("sep_2026", 12000)
                    .put("oct_2026", 8000)
                    .put("nov_2026", 5000),
            )
            .put(
                JSONObject()
                    .put("account", "Sales - OB")
                    .put("account_name", "Sales")
                    .put("root_type", "Income")
                    .put("indent", 1)
                    .put("is_group", 0)
                    .put("aug_2026", 10000)
                    .put("sep_2026", 12000)
                    .put("oct_2026", 8000)
                    .put("nov_2026", 5000),
            )
            .put(
                JSONObject()
                    .put("account", "Total Income (Credit)")
                    .put("account_name", "Total Income (Credit)")
                    .put("aug_2026", 10000)
                    .put("sep_2026", 12000)
                    .put("oct_2026", 8000)
                    .put("nov_2026", 5000),
            )
            .put(
                JSONObject()
                    .put("account", "Expenses")
                    .put("account_name", "Expenses")
                    .put("root_type", "Expense")
                    .put("indent", 0)
                    .put("is_group", 1)
                    .put("aug_2026", 15000)
                    .put("sep_2026", 15000)
                    .put("oct_2026", 12000)
                    .put("nov_2026", 3000),
            )
            .put(
                JSONObject()
                    .put("account", "Payroll - OB")
                    .put("account_name", "Payroll")
                    .put("root_type", "Expense")
                    .put("indent", 1)
                    .put("is_group", 0)
                    .put("aug_2026", 15000)
                    .put("sep_2026", 15000)
                    .put("oct_2026", 12000)
                    .put("nov_2026", 3000),
            )
            .put(
                JSONObject()
                    .put("account", "Total Expense (Debit)")
                    .put("account_name", "Total Expense (Debit)")
                    .put("aug_2026", 15000)
                    .put("sep_2026", 15000)
                    .put("oct_2026", 12000)
                    .put("nov_2026", 3000),
            )
            .put(
                JSONObject()
                    .put("account", "Profit for the year")
                    .put("account_name", "Profit for the year")
                    .put("nov_2026", 2000),
            )

        val rep = JSONObject().put("columns", columns).put("result", result)
        val pnl = ErpReports.parseProfitAndLossResponse(rep, fy, today)

        assertEquals(500000L, pnl.mtdIncomeMinor)
        assertEquals(300000L, pnl.mtdExpenseMinor)
        assertEquals(200000L, pnl.mtdNetMinor)
        assertEquals(3500000L, pnl.fyIncomeMinor)
        assertEquals(4500000L, pnl.fyExpenseMinor)
        assertEquals(-1000000L, pnl.fyNetMinor)
        assertEquals(4, pnl.months.size)
        assertEquals(2, pnl.lines.size)
        assertEquals(PnlRootType.INCOME, pnl.lines[0].rootType)
        assertEquals(PnlRootType.EXPENSE, pnl.lines[1].rootType)
    }

    @Test
    fun computeRunwayUsesTrailingThreeCompletedMonthsAndHandlesPositiveFlow() {
        val today = LocalDate.of(2026, 11, 18)
        val months = listOf(
            MonthPerformance("2026-07", "Jul 26", incomeMinor = 500000L, expenseMinor = 500000L),
            MonthPerformance("2026-08", "Aug 26", incomeMinor = 1000000L, expenseMinor = 1500000L), // -5000
            MonthPerformance("2026-09", "Sep 26", incomeMinor = 1000000L, expenseMinor = 1400000L), // -4000
            MonthPerformance("2026-10", "Oct 26", incomeMinor = 1000000L, expenseMinor = 1300000L), // -3000
            MonthPerformance("2026-11", "Nov 26", incomeMinor = 0L, expenseMinor = 200000L), // partial month ignored
        )
        // Total 3M burn (Aug+Sep+Oct) = 12,000.00 INR (1,200,000 minor) -> Avg 4,000.00/mo (400,000 minor)
        // Liquid cash = 20,000.00 INR (2,000,000 minor) -> 5.0 months runway
        val burnRunway = ReportsLogic.computeRunway(
            liquidCashMinor = 2_000_000L,
            cardBalanceMinor = -150_000L,
            months = months,
            today = today,
        )
        assertFalse(burnRunway.isCashFlowPositive)
        assertEquals(listOf("2026-08", "2026-09", "2026-10"), burnRunway.burnMonthsUsed.map { it.ym })
        assertEquals(400_000L, burnRunway.avgMonthlyNetBurnMinor)
        assertEquals(5.0, burnRunway.runwayMonths!!, 0.01)
        assertNotNull(burnRunway.projectedZeroDate)
        assertEquals("5.0 mo runway", ReportsLogic.formatRunwayHeadline(burnRunway))

        val positiveMonths = listOf(
            MonthPerformance("2026-08", "Aug 26", incomeMinor = 1500000L, expenseMinor = 1000000L),
            MonthPerformance("2026-09", "Sep 26", incomeMinor = 1400000L, expenseMinor = 1100000L),
            MonthPerformance("2026-10", "Oct 26", incomeMinor = 1300000L, expenseMinor = 1200000L),
        )
        val positiveRunway = ReportsLogic.computeRunway(2_000_000L, 0L, positiveMonths, today)
        assertTrue(positiveRunway.isCashFlowPositive)
        assertNull(positiveRunway.runwayMonths)
        assertEquals("Cash-flow positive", ReportsLogic.formatRunwayHeadline(positiveRunway))
    }

    @Test
    fun filterAndSortExposureRespectsBucketQueryAndSortMode() {
        val rows = listOf(
            PartyExposureRow(
                party = "Zeta Supplies",
                partyType = "Customer",
                invoicedMinor = 5000L,
                paidMinor = 0L,
                advanceMinor = 0L,
                creditNoteMinor = 0L,
                outstandingMinor = 5000L,
                totalDueMinor = 5000L,
                buckets = AgeingBuckets(b0To30 = 5000L),
            ),
            PartyExposureRow(
                party = "Alpha Traders",
                partyType = "Customer",
                invoicedMinor = 3000L,
                paidMinor = 0L,
                advanceMinor = 0L,
                creditNoteMinor = 0L,
                outstandingMinor = 3000L,
                totalDueMinor = 3000L,
                buckets = AgeingBuckets(b0To30 = 500L, b120Plus = 2500L),
            ),
        )
        val overdueSorted = ReportsLogic.filterAndSortExposure(rows, "", null, ExposureSort.MOST_OVERDUE)
        assertEquals("Alpha Traders", overdueSorted[0].party)

        val bucket120 = ReportsLogic.filterAndSortExposure(rows, "", AgeingBucket.B120_PLUS, ExposureSort.HIGHEST_DUE)
        assertEquals(1, bucket120.size)
        assertEquals("Alpha Traders", bucket120[0].party)

        val alphaByName = ReportsLogic.filterAndSortExposure(rows, "alpha", null, ExposureSort.NAME_AZ)
        assertEquals(1, alphaByName.size)
    }

    @Test
    fun reportsCacheRoundTripsAndIsolatesBySiteAndCompany() {
        val snap = ReportsSnapshot(
            company = "Acme Ltd",
            fetchedAt = 1_762_600_000_000L,
            liquidCashMinor = 1_500_000L,
            cardBalanceMinor = -50_000L,
            accountBalances = listOf(
                BankBalanceItem("IDFC Current", "IDFC - A", AccountType.BANK, 1_500_000L),
            ),
            arReport = null,
            apReport = null,
            pnlData = null,
            runway = null,
        )
        val raw = ReportsLogic.serializeReportsCache("https://erp.example.com", snap)
        val restored = ReportsLogic.parseReportsCache(raw, "https://erp.example.com", "Acme Ltd")
        assertNotNull(restored)
        assertEquals(1_500_000L, restored!!.liquidCashMinor)
        assertTrue(restored.stale)

        assertNull(ReportsLogic.parseReportsCache(raw, "https://other.example.com", "Acme Ltd"))
        assertNull(ReportsLogic.parseReportsCache(raw, "https://erp.example.com", "Other Co"))
    }
}
