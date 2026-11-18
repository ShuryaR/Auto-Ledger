package app.onebook.autoledger.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.onebook.autoledger.core.AccountType
import app.onebook.autoledger.erp.AgeingBucket
import app.onebook.autoledger.erp.ExposureKind
import app.onebook.autoledger.erp.PartyExposureReport
import app.onebook.autoledger.erp.PartyExposureRow
import app.onebook.autoledger.erp.PnlLineItem
import app.onebook.autoledger.erp.PnlRootType
import app.onebook.autoledger.home.HomeLogic
import app.onebook.autoledger.inbox.Formatters
import app.onebook.autoledger.reports.ExposureSort
import app.onebook.autoledger.reports.PnlPeriod
import app.onebook.autoledger.reports.ReportSection
import app.onebook.autoledger.reports.ReportsLogic
import app.onebook.autoledger.reports.ReportsSnapshot
import app.onebook.autoledger.ui.components.AgeingStackedBar
import app.onebook.autoledger.ui.components.BannerTone
import app.onebook.autoledger.ui.components.DetailLine
import app.onebook.autoledger.ui.components.LedgerBanner
import app.onebook.autoledger.ui.components.LedgerCard
import app.onebook.autoledger.ui.components.LedgerChip
import app.onebook.autoledger.ui.components.LedgerEmptyState
import app.onebook.autoledger.ui.components.LedgerSearchField
import app.onebook.autoledger.ui.components.MonthlyInOutBarChart
import app.onebook.autoledger.ui.components.RunwayGaugeBar
import app.onebook.autoledger.ui.components.SegmentedPillBar
import app.onebook.autoledger.ui.theme.LedgerTheme
import app.onebook.autoledger.ui.theme.tnum
import kotlin.math.roundToInt

internal fun LazyListScope.misSectionItems(
    snapshot: ReportsSnapshot,
    onJumpSection: (ReportSection) -> Unit,
) {
    if (snapshot.cashError != null) {
        item(key = "mis_cash_err") {
            LedgerBanner(tone = BannerTone.WARN, compact = true, message = snapshot.cashError)
        }
    }
    if (snapshot.pnlError != null && snapshot.pnlData == null) {
        item(key = "mis_pnl_err") {
            LedgerBanner(tone = BannerTone.WARN, compact = true, message = snapshot.pnlError)
        }
    }

    item(key = "mis_runway_hero") {
        val c = LedgerTheme.colors
        val runway = snapshot.runway
        val headline = ReportsLogic.formatRunwayHeadline(runway)
        val badgeColor = when {
            runway == null || runway.burnMonthsUsed.isEmpty() -> c.muted
            runway.isCashFlowPositive -> c.ok
            (runway.runwayMonths ?: 0.0) < 3.0 -> c.danger
            (runway.runwayMonths ?: 0.0) < 6.0 -> c.warn
            else -> c.accent
        }

        LedgerCard(modifier = Modifier.testTag("mis_runway_card")) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Cash & Runway",
                    style = MaterialTheme.typography.titleSmall,
                    color = c.muted,
                )
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = badgeColor.copy(alpha = 0.13f),
                ) {
                    Text(
                        text = headline,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = Formatters.formatInr(snapshot.liquidCashMinor),
                style = MaterialTheme.typography.headlineLarge.tnum,
                fontWeight = FontWeight.Bold,
                color = c.ink,
            )
            Text(
                text = "Liquid bank balance across ${snapshot.accountBalances.count { it.kind == AccountType.BANK }} account(s)",
                style = MaterialTheme.typography.bodySmall,
                color = c.muted,
            )

            Spacer(Modifier.height(14.dp))
            RunwayGaugeBar(runway = runway)

            if (runway != null && runway.burnMonthsUsed.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = c.line)
                Spacer(Modifier.height(12.dp))
                val monthsLabel = runway.burnMonthsUsed.joinToString(", ") { it.label }
                Text(
                    text = "Trailing ${runway.burnMonthsUsed.size}-Month Burn ($monthsLabel)",
                    style = MaterialTheme.typography.labelLarge,
                    color = c.ink,
                )
                Spacer(Modifier.height(8.dp))
                DetailLine("3M Income", Formatters.formatInr(runway.threeMonthIncomeMinor), strong = true)
                DetailLine("3M Expenses", Formatters.formatInr(runway.threeMonthExpenseMinor), strong = true)
                if (runway.isCashFlowPositive) {
                    DetailLine(
                        "Avg Monthly Surplus",
                        "+${Formatters.formatInr(runway.avgMonthlyNetSurplusMinor)}",
                        strong = true,
                    )
                } else {
                    DetailLine(
                        "Avg Monthly Net Burn",
                        Formatters.formatInr(runway.avgMonthlyNetBurnMinor),
                        strong = true,
                    )
                    if (runway.projectedZeroDate != null) {
                        DetailLine("Projected Zero-Cash Date", runway.projectedZeroDate, mono = true)
                    }
                }
            }
            if (snapshot.cardBalanceMinor != 0L) {
                Spacer(Modifier.height(8.dp))
                DetailLine(
                    "Credit Cards (excl. from cash)",
                    Formatters.formatInr(snapshot.cardBalanceMinor),
                )
            }
        }
    }

    item(key = "mis_working_capital") {
        val c = LedgerTheme.colors
        val ar = snapshot.arReport
        val ap = snapshot.apReport
        val wcGap = snapshot.workingCapitalGapMinor

        LedgerCard(modifier = Modifier.testTag("mis_working_capital_card")) {
            Text(
                text = "Working Capital & Exposure",
                style = MaterialTheme.typography.titleSmall,
                color = c.muted,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (wcGap == null) HomeLogic.DASH
                else if (wcGap >= 0L) "+${Formatters.formatInr(wcGap)}"
                else Formatters.formatInr(wcGap),
                style = MaterialTheme.typography.titleLarge.tnum,
                fontWeight = FontWeight.Bold,
                color = if ((wcGap ?: 0L) >= 0L) c.moneyIn else c.moneyOut,
            )
            Text(
                text = "Net Working Capital Gap (AR \u2212 AP)",
                style = MaterialTheme.typography.bodySmall,
                color = c.muted,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ExposureMiniTile(
                    title = "Receivable (AR)",
                    amountText = ar?.let { HomeLogic.wholeText(it.totalOutstandingMinor) } ?: HomeLogic.DASH,
                    subText = ar?.let {
                        val pct = if (it.totalOutstandingMinor > 0L) {
                            ((it.buckets.overdueMinor.toDouble() / it.totalOutstandingMinor.toDouble()) * 100.0).roundToInt()
                        } else 0
                        "$pct% overdue"
                    },
                    amountColor = c.moneyIn,
                    onClick = { onJumpSection(ReportSection.AR) },
                    modifier = Modifier.weight(1f),
                )
                ExposureMiniTile(
                    title = "Payable (AP)",
                    amountText = ap?.let { HomeLogic.wholeText(it.totalOutstandingMinor) } ?: HomeLogic.DASH,
                    subText = ap?.let {
                        val pct = if (it.totalOutstandingMinor > 0L) {
                            ((it.buckets.overdueMinor.toDouble() / it.totalOutstandingMinor.toDouble()) * 100.0).roundToInt()
                        } else 0
                        "$pct% overdue"
                    },
                    amountColor = c.moneyOut,
                    onClick = { onJumpSection(ReportSection.AP) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    val pnl = snapshot.pnlData
    if (pnl != null) {
        item(key = "mis_cashflow_trend") {
            val c = LedgerTheme.colors
            LedgerCard(modifier = Modifier.testTag("mis_trend_card")) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Monthly Cash Flow",
                        style = MaterialTheme.typography.titleSmall,
                        color = c.muted,
                    )
                    Text(
                        text = "See P&L",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = c.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onJumpSection(ReportSection.PNL) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                MonthlyInOutBarChart(months = pnl.months)
            }
        }
    }
}

@Composable
private fun ExposureMiniTile(
    title: String,
    amountText: String,
    subText: String?,
    amountColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LedgerTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Surface(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = c.surfaceSubtle,
        border = BorderStroke(1.dp, c.line),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, fontSize = 12.sp, color = c.muted, fontWeight = FontWeight.Medium)
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = c.muted, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = amountText,
                style = MaterialTheme.typography.titleMedium.tnum,
                fontWeight = FontWeight.Bold,
                color = amountColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subText != null) {
                Spacer(Modifier.height(2.dp))
                Text(subText, fontSize = 11.5.sp, color = c.muted)
            }
        }
    }
}

internal fun LazyListScope.pnlSectionItems(
    snapshot: ReportsSnapshot,
    period: PnlPeriod,
    onSelectPeriod: (PnlPeriod) -> Unit,
    incomeExpanded: Boolean,
    onToggleIncome: () -> Unit,
    expenseExpanded: Boolean,
    onToggleExpense: () -> Unit,
) {
    if (snapshot.pnlError != null && snapshot.pnlData == null) {
        item(key = "pnl_err") {
            LedgerBanner(tone = BannerTone.WARN, message = snapshot.pnlError)
        }
        return
    }
    val pnl = snapshot.pnlData ?: run {
        item(key = "pnl_empty") {
            LedgerEmptyState(
                title = "No Profit & Loss data",
                message = "Could not load Profit & Loss figures for ${snapshot.company}.",
            )
        }
        return
    }

    item(key = "pnl_period_toggle") {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            SegmentedPillBar(
                items = PnlPeriod.entries,
                selected = period,
                labelOf = { it.label },
                onSelect = onSelectPeriod,
                tagPrefix = "pnl_period",
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val c = LedgerTheme.colors
                Text(
                    text = "FY ${pnl.fiscalYear.name} (${pnl.fiscalYear.startDate} to ${pnl.fiscalYear.endDate})",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.muted,
                )
                if (pnl.fromFallback) {
                    Text(
                        text = "GL Entry summary",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = c.accent,
                    )
                }
            }
        }
    }

    val incomeMinor = if (period == PnlPeriod.MTD) pnl.mtdIncomeMinor else pnl.fyIncomeMinor
    val expenseMinor = if (period == PnlPeriod.MTD) pnl.mtdExpenseMinor else pnl.fyExpenseMinor
    val netMinor = incomeMinor - expenseMinor
    val marginPct = ReportsLogic.marginPct(incomeMinor, expenseMinor)

    item(key = "pnl_summary") {
        val c = LedgerTheme.colors
        LedgerCard(modifier = Modifier.testTag("pnl_summary_card")) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (period == PnlPeriod.MTD) "Month-to-Date Summary" else "Fiscal Year Summary",
                    style = MaterialTheme.typography.titleSmall,
                    color = c.muted,
                )
                if (marginPct != null) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = if (netMinor >= 0L) c.moneyInSoft else c.moneyOutSoft,
                    ) {
                        Text(
                            text = "$marginPct% margin",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (netMinor >= 0L) c.moneyIn else c.moneyOut,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Income", fontSize = 12.sp, color = c.muted)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = HomeLogic.wholeText(incomeMinor),
                        style = MaterialTheme.typography.titleMedium.tnum,
                        fontWeight = FontWeight.Bold,
                        color = c.moneyIn,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Expenses", fontSize = 12.sp, color = c.muted)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = HomeLogic.wholeText(expenseMinor),
                        style = MaterialTheme.typography.titleMedium.tnum,
                        fontWeight = FontWeight.Bold,
                        color = c.moneyOut,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(if (netMinor >= 0L) "Net profit" else "Net loss", fontSize = 12.sp, color = c.muted)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (netMinor > 0L) "+${HomeLogic.wholeText(netMinor)}" else HomeLogic.wholeText(netMinor),
                        style = MaterialTheme.typography.titleMedium.tnum,
                        fontWeight = FontWeight.Bold,
                        color = if (netMinor >= 0L) c.ink else c.moneyOut,
                    )
                }
            }
        }
    }

    item(key = "pnl_chart") {
        val c = LedgerTheme.colors
        LedgerCard {
            Text(
                text = "Monthly Performance",
                style = MaterialTheme.typography.titleSmall,
                color = c.muted,
            )
            Spacer(Modifier.height(10.dp))
            MonthlyInOutBarChart(months = pnl.months)
        }
    }

    val incomeLines = pnl.lines.filter {
        it.rootType == PnlRootType.INCOME && (if (period == PnlPeriod.MTD) it.mtdMinor != 0L else it.fyMinor != 0L)
    }
    val expenseLines = pnl.lines.filter {
        it.rootType == PnlRootType.EXPENSE && (if (period == PnlPeriod.MTD) it.mtdMinor != 0L else it.fyMinor != 0L)
    }

    item(key = "pnl_income_lines") {
        PnlAccountGroupCard(
            title = "Income accounts",
            totalMinor = incomeMinor,
            items = incomeLines,
            period = period,
            expanded = incomeExpanded,
            onToggle = onToggleIncome,
            accentIsPositive = true,
        )
    }

    item(key = "pnl_expense_lines") {
        PnlAccountGroupCard(
            title = "Expense accounts",
            totalMinor = expenseMinor,
            items = expenseLines,
            period = period,
            expanded = expenseExpanded,
            onToggle = onToggleExpense,
            accentIsPositive = false,
        )
    }
}

@Composable
private fun PnlAccountGroupCard(
    title: String,
    totalMinor: Long,
    items: List<PnlLineItem>,
    period: PnlPeriod,
    expanded: Boolean,
    onToggle: () -> Unit,
    accentIsPositive: Boolean,
) {
    val c = LedgerTheme.colors
    val valueColor = if (accentIsPositive) c.moneyIn else c.moneyOut

    LedgerCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onToggle),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text(
                    text = "${items.size} account line(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.muted,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = Formatters.formatInr(totalMinor),
                    style = MaterialTheme.typography.titleMedium.tnum,
                    fontWeight = FontWeight.Bold,
                    color = valueColor,
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                    tint = c.muted,
                )
            }
        }
        if (expanded) {
            if (items.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "No posted entries in this period.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.muted,
                )
            } else {
                HorizontalDivider(color = c.line, modifier = Modifier.padding(vertical = 10.dp))
                items.forEachIndexed { idx, line ->
                    if (idx > 0) Spacer(Modifier.height(8.dp))
                    val amt = if (period == PnlPeriod.MTD) line.mtdMinor else line.fyMinor
                    val indentDp = ((line.indent - 1).coerceIn(0, 3) * 12).dp
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = indentDp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = line.label,
                            fontSize = if (line.isGroup) 14.sp else 13.5.sp,
                            fontWeight = if (line.isGroup) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (line.isGroup) c.ink else c.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = Formatters.formatInr(amt),
                            style = MaterialTheme.typography.bodyMedium.tnum,
                            fontWeight = if (line.isGroup) FontWeight.SemiBold else FontWeight.Medium,
                            color = c.ink,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
internal fun LazyListScope.exposureSectionItems(
    kind: ExposureKind,
    report: PartyExposureReport?,
    errorMessage: String?,
    query: String,
    onQueryChange: (String) -> Unit,
    selectedBucket: AgeingBucket?,
    onSelectBucket: (AgeingBucket?) -> Unit,
    sort: ExposureSort,
    onSelectSort: (ExposureSort) -> Unit,
) {
    if (errorMessage != null && report == null) {
        item(key = "${kind.name}_err") {
            LedgerBanner(tone = BannerTone.WARN, message = errorMessage)
        }
        return
    }
    if (report == null) {
        item(key = "${kind.name}_empty") {
            LedgerEmptyState(
                title = "No ${kind.label} data",
                message = "Pull down to fetch ${kind.label} and ageing analysis from ERPNext.",
            )
        }
        return
    }

    val isAr = kind == ExposureKind.AR
    val overduePct = if (report.totalOutstandingMinor > 0L) {
        ((report.buckets.overdueMinor.toDouble() / report.totalOutstandingMinor.toDouble()) * 100.0).roundToInt()
    } else 0

    item(key = "${kind.name}_summary") {
        val c = LedgerTheme.colors
        val headlineColor = if (isAr) c.moneyIn else c.moneyOut
        LedgerCard(modifier = Modifier.testTag("${kind.name.lowercase()}_summary_card")) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isAr) "Total Receivable" else "Total Payable",
                    style = MaterialTheme.typography.titleSmall,
                    color = c.muted,
                )
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (report.buckets.overdueMinor > 0L) c.warnSoft else c.okSoft,
                ) {
                    Text(
                        text = if (report.buckets.overdueMinor > 0L) "$overduePct% overdue" else "All current",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (report.buckets.overdueMinor > 0L) c.warn else c.ok,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = Formatters.formatInr(report.totalOutstandingMinor),
                style = MaterialTheme.typography.headlineLarge.tnum,
                fontWeight = FontWeight.Bold,
                color = headlineColor,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Overdue (31d+)", fontSize = 12.sp, color = c.muted)
                    Text(
                        text = HomeLogic.wholeText(report.buckets.overdueMinor),
                        style = MaterialTheme.typography.titleSmall.tnum,
                        fontWeight = FontWeight.SemiBold,
                        color = if (report.buckets.overdueMinor > 0L) c.warn else c.ink,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Advances", fontSize = 12.sp, color = c.muted)
                    Text(
                        text = HomeLogic.wholeText(report.totalAdvanceMinor),
                        style = MaterialTheme.typography.titleSmall.tnum,
                        fontWeight = FontWeight.SemiBold,
                        color = c.ink,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Parties", fontSize = 12.sp, color = c.muted)
                    Text(
                        text = report.rows.size.toString(),
                        style = MaterialTheme.typography.titleSmall.tnum,
                        fontWeight = FontWeight.SemiBold,
                        color = c.ink,
                    )
                }
            }
        }
    }

    item(key = "${kind.name}_ageing") {
        val c = LedgerTheme.colors
        LedgerCard(modifier = Modifier.testTag("${kind.name.lowercase()}_ageing_card")) {
            Text(
                text = "Ageing Analysis (Due Date)",
                style = MaterialTheme.typography.titleSmall,
                color = c.muted,
            )
            Spacer(Modifier.height(10.dp))
            AgeingStackedBar(
                buckets = report.buckets,
                selectedBucket = selectedBucket,
                onSelectBucket = onSelectBucket,
            )
        }
    }

    item(key = "${kind.name}_controls") {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            LedgerSearchField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = if (isAr) "Search customer" else "Search supplier",
                modifier = Modifier.testTag("${kind.name.lowercase()}_search_input"),
            )
            Spacer(Modifier.height(8.dp))
            FlowRow {
                ExposureSort.entries.forEach { s ->
                    LedgerChip(
                        label = s.label,
                        selected = sort == s,
                        onClick = { onSelectSort(s) },
                    )
                }
            }
        }
    }

    val filteredRows = ReportsLogic.filterAndSortExposure(
        rows = report.rows,
        query = query,
        bucket = selectedBucket,
        sort = sort,
    )

    if (filteredRows.isEmpty()) {
        item(key = "${kind.name}_no_rows") {
            LedgerEmptyState(
                title = "No matching parties",
                message = if (report.rows.isEmpty()) {
                    "No open ${kind.label.lowercase()} balances as of ${report.reportDate}."
                } else {
                    "Try clearing your search or ageing bucket filter."
                },
                actionLabel = if (query.isNotEmpty() || selectedBucket != null) "Clear filters" else null,
                onAction = if (query.isNotEmpty() || selectedBucket != null) {
                    {
                        onQueryChange("")
                        onSelectBucket(null)
                    }
                } else null,
            )
        }
    } else {
        items(filteredRows, key = { "${kind.name}:${it.party}" }) { row ->
            PartyExposureRowCard(
                row = row,
                isAr = isAr,
                highlightedBucket = selectedBucket,
            )
        }
    }
}

@Composable
private fun PartyExposureRowCard(
    row: PartyExposureRow,
    isAr: Boolean,
    highlightedBucket: AgeingBucket?,
) {
    val c = LedgerTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val amountMinor = highlightedBucket?.let { row.buckets.amountFor(it) } ?: row.outstandingMinor
    val hasOverdue = row.buckets.overdueMinor > 0L

    LedgerCard(onClick = { expanded = !expanded }) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.party,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 15.5.sp,
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (hasOverdue) c.warn else c.ok),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (hasOverdue) {
                            "${HomeLogic.wholeText(row.buckets.overdueMinor)} overdue (31d+)"
                        } else {
                            "Current (0\u201330d)"
                        },
                        fontSize = 12.5.sp,
                        color = if (hasOverdue) c.warn else c.muted,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = Formatters.formatInr(amountMinor),
                    style = MaterialTheme.typography.titleMedium.tnum,
                    fontWeight = FontWeight.Bold,
                    color = if (isAr) c.moneyIn else c.moneyOut,
                )
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Hide ageing details" else "Show ageing details",
                    tint = c.muted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        if (expanded) {
            HorizontalDivider(color = c.line, modifier = Modifier.padding(vertical = 10.dp))
            Text("Ageing Breakdown", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = c.muted)
            Spacer(Modifier.height(6.dp))
            AgeingBucket.entries.forEach { bucket ->
                val bAmt = row.buckets.amountFor(bucket)
                if (bAmt != 0L) {
                    DetailLine(bucket.label, Formatters.formatInr(bAmt))
                }
            }
            HorizontalDivider(color = c.line, modifier = Modifier.padding(vertical = 8.dp))
            DetailLine("Invoiced", Formatters.formatInr(row.invoicedMinor))
            DetailLine("Paid", Formatters.formatInr(row.paidMinor))
            if (row.advanceMinor != 0L) {
                DetailLine("Advance", Formatters.formatInr(row.advanceMinor))
            }
            if (row.creditNoteMinor != 0L) {
                DetailLine(if (isAr) "Credit Note" else "Debit Note", Formatters.formatInr(row.creditNoteMinor))
            }
        }
    }
}
