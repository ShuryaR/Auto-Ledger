package app.onebook.autoledger.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.onebook.autoledger.home.HomeLoadStatus
import app.onebook.autoledger.home.HomeLogic
import app.onebook.autoledger.home.HomeUiState
import app.onebook.autoledger.inbox.Formatters
import app.onebook.autoledger.reports.ReportSection
import app.onebook.autoledger.state.AppContainer
import app.onebook.autoledger.ui.components.BannerTone
import app.onebook.autoledger.ui.components.ButtonVariant
import app.onebook.autoledger.ui.components.HeroCashRunwayCard
import app.onebook.autoledger.ui.components.LedgerBanner
import app.onebook.autoledger.ui.components.LedgerButton
import app.onebook.autoledger.ui.components.LedgerCard
import app.onebook.autoledger.ui.components.LedgerScreen
import app.onebook.autoledger.ui.theme.LedgerTheme
import app.onebook.autoledger.ui.theme.tnum
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    container: AppContainer,
    onGoInbox: () -> Unit,
    onGoActivity: () -> Unit,
    onGoSignIn: () -> Unit,
    onGoReports: (ReportSection) -> Unit = {},
) {
    val c = LedgerTheme.colors
    val scope = rememberCoroutineScope()
    val bootState by container.state.collectAsState()
    var uiState by remember { mutableStateOf(HomeUiState()) }
    var refreshing by remember { mutableStateOf(false) }

    suspend fun load() {
        val snap = HomeLogic.loadHomeSnapshot(
            repo = container.repo,
            client = container.client,
            siteUrl = bootState.session?.siteUrl,
            companyKey = AppContainer.COMPANY_KEY,
            now = System::currentTimeMillis,
        )
        uiState = snap
    }

    LaunchedEffect(bootState.session?.siteUrl, bootState.dataVersion, bootState.homeRefreshVersion) {
        if (bootState.session != null) {
            load()
        }
    }

    fun onRefresh() {
        scope.launch {
            refreshing = true
            try {
                container.runCycle()
                load()
            } finally {
                refreshing = false
            }
        }
    }

    LedgerScreen(
        title = uiState.company?.takeIf { it.isNotBlank() } ?: "AutoLedger",
        subtitle = uiState.model?.lastSyncedText ?: "Executive Overview",
        refreshing = refreshing,
        onRefresh = { onRefresh() },
        applySystemBarPadding = false,
    ) {
        val auth = uiState.auth
        if (auth != null) {
            val is401 = auth.status == 401
            LedgerBanner(
                tone = BannerTone.WARN,
                title = if (is401) "Session expired" else "Access check needed",
                message = if (is401) {
                    "ERPNext did not accept your saved login. Sign in again to refresh balances."
                } else {
                    val dt = auth.deniedDoctype
                    if (dt != null) "Your ERPNext user could not read $dt records." else "Your ERPNext user does not have permission to read accounting reports."
                },
                actionLabel = if (is401) "Sign in again" else null,
                onAction = if (is401) onGoSignIn else null,
            )
        }

        if (uiState.status == HomeLoadStatus.LOADING && uiState.model == null) {
            LedgerBanner(message = "Loading your numbers from ERPNext...")
            return@LedgerScreen
        }

        if (uiState.status == HomeLoadStatus.ERROR && uiState.model == null) {
            LedgerBanner(
                tone = BannerTone.ERROR,
                title = "Could not load your overview",
                message = "Check your internet connection and pull down to try again.",
                actionLabel = "Try again",
                onAction = { onRefresh() },
            )
            return@LedgerScreen
        }

        val m = uiState.model ?: return@LedgerScreen

        // Hero Liquid Cash + Runway drill-down card
        HeroCashRunwayCard(
            liquidCashMinor = m.liquidCashMinor,
            accounts = m.bank.accounts,
            runwayHeadline = m.runwayHeadline,
            runwayIsPositive = m.runwayIsPositive,
            asOfLabel = m.asOfLabel,
            onClick = { onGoReports(ReportSection.MIS) },
        )

        val trustTone = when {
            m.trust.mismatch -> BannerTone.WARN
            m.trust.incomplete -> BannerTone.INFO
            else -> BannerTone.OK
        }
        val trustTitle = when {
            m.trust.mismatch -> "Balance differs"
            m.trust.incomplete -> "Needs attention"
            else -> "Up to date"
        }
        val trustDetail = (m.trust.reasons + listOfNotNull(m.trustLine))
            .distinct()
            .joinToString("\n")
            .ifBlank { "Your bank accounts and ERPNext are in sync." }
        LedgerBanner(
            tone = trustTone,
            title = trustTitle,
            message = trustDetail,
        )

        if (m.couldNotRefresh) {
            LedgerBanner(
                tone = BannerTone.WARN,
                compact = true,
                message = "Could not reach ERPNext right now. Showing figures ${m.asOfLabel ?: "saved on this phone"}.",
            )
        }

        // Individual Bank & Credit Card balances
        if (m.bank.accounts.isNotEmpty() || m.cards.isNotEmpty()) {
            LedgerCard {
                Text(
                    text = "Accounts",
                    style = MaterialTheme.typography.titleSmall,
                    color = c.muted,
                )
                Spacer(Modifier.height(10.dp))
                m.bank.accounts.forEachIndexed { idx, acc ->
                    if (idx > 0) Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(c.accentSoft),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AccountBalance,
                                    contentDescription = null,
                                    tint = c.accent,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = acc.name,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = c.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = acc.text,
                            style = MaterialTheme.typography.titleMedium.tnum,
                            fontWeight = FontWeight.SemiBold,
                            color = c.ink,
                        )
                    }
                }

                if (m.cards.isNotEmpty()) {
                    if (m.bank.accounts.isNotEmpty()) {
                        HorizontalDivider(color = c.line, modifier = Modifier.padding(vertical = 12.dp))
                    }
                    Text(
                        text = "Credit cards (separate from liquid cash)",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.muted,
                    )
                    Spacer(Modifier.height(8.dp))
                    m.cards.forEachIndexed { idx, card ->
                        if (idx > 0) Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(c.moneyOutSoft),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.CreditCard,
                                        contentDescription = null,
                                        tint = c.moneyOut,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = card.name,
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = c.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = card.text,
                                style = MaterialTheme.typography.titleMedium.tnum,
                                fontWeight = FontWeight.SemiBold,
                                color = c.moneyOut,
                            )
                        }
                    }
                }
            }
        }

        // Receivable & Payable clickable drill-down row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LedgerCard(
                modifier = Modifier
                    .weight(1f)
                    .testTag("home_ar_card"),
                onClick = { onGoReports(ReportSection.AR) },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = m.receivable.label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = c.muted,
                    )
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = "Open AR Ageing",
                        tint = c.muted,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = m.receivable.text,
                    style = MaterialTheme.typography.titleLarge.tnum,
                    fontWeight = FontWeight.Bold,
                    color = c.moneyIn,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (m.arOverdueMinor != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (m.arOverdueMinor > 0L) {
                            "${HomeLogic.wholeText(m.arOverdueMinor)} overdue"
                        } else {
                            "All current"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (m.arOverdueMinor > 0L) c.warn else c.ok,
                    )
                }
            }
            LedgerCard(
                modifier = Modifier
                    .weight(1f)
                    .testTag("home_ap_card"),
                onClick = { onGoReports(ReportSection.AP) },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = m.payable.label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = c.muted,
                    )
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = "Open AP Ageing",
                        tint = c.muted,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = m.payable.text,
                    style = MaterialTheme.typography.titleLarge.tnum,
                    fontWeight = FontWeight.Bold,
                    color = c.moneyOut,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (m.apOverdueMinor != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (m.apOverdueMinor > 0L) {
                            "${HomeLogic.wholeText(m.apOverdueMinor)} overdue"
                        } else {
                            "All current"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (m.apOverdueMinor > 0L) c.warn else c.ok,
                    )
                }
            }
        }

        // This Month P&L Preview card
        if (m.mtdIncomeMinor != null && m.mtdExpenseMinor != null) {
            val mtdNet = m.mtdNetMinor ?: (m.mtdIncomeMinor - m.mtdExpenseMinor)
            LedgerCard(
                modifier = Modifier.testTag("home_pnl_card"),
                onClick = { onGoReports(ReportSection.PNL) },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = "This Month (P&L)",
                            style = MaterialTheme.typography.titleSmall,
                            color = c.ink,
                        )
                        Text(
                            text = "Month-to-date income & expenses",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.muted,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (m.mtdMarginPct != null) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = if (mtdNet >= 0L) c.moneyInSoft else c.moneyOutSoft,
                                border = BorderStroke(
                                    1.dp,
                                    (if (mtdNet >= 0L) c.moneyIn else c.moneyOut).copy(alpha = 0.2f),
                                ),
                            ) {
                                Text(
                                    text = "${m.mtdMarginPct}% margin",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (mtdNet >= 0L) c.moneyIn else c.moneyOut,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = "Open Profit & Loss",
                            tint = c.muted,
                            modifier = Modifier.size(18.dp),
                        )
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
                            text = HomeLogic.wholeText(m.mtdIncomeMinor),
                            style = MaterialTheme.typography.titleMedium.tnum,
                            fontWeight = FontWeight.Bold,
                            color = c.moneyIn,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Expenses", fontSize = 12.sp, color = c.muted)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = HomeLogic.wholeText(m.mtdExpenseMinor),
                            style = MaterialTheme.typography.titleMedium.tnum,
                            fontWeight = FontWeight.Bold,
                            color = c.moneyOut,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(if (mtdNet >= 0L) "Net profit" else "Net loss", fontSize = 12.sp, color = c.muted)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (mtdNet > 0L) "+${HomeLogic.wholeText(mtdNet)}" else HomeLogic.wholeText(mtdNet),
                            style = MaterialTheme.typography.titleMedium.tnum,
                            fontWeight = FontWeight.Bold,
                            color = if (mtdNet >= 0L) c.ink else c.moneyOut,
                        )
                    }
                }
            }
        }

        // Unanswered Inbox CTA card
        LedgerCard(
            accentBorder = m.unclassifiedCount > 0,
            onClick = onGoInbox,
            modifier = Modifier.testTag("home_inbox_card"),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(c.accentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Inbox,
                        contentDescription = null,
                        tint = c.accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Inbox", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.muted)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (m.unclassifiedCount == 0) {
                            "All bank messages answered"
                        } else {
                            "${m.unclassifiedCount} message${if (m.unclassifiedCount == 1) "" else "s"} to answer"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = c.ink,
                    )
                }
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = c.muted)
            }
            if (m.unclassifiedCount > 0) {
                Spacer(Modifier.height(12.dp))
                LedgerButton(
                    label = "Review Inbox",
                    onClick = onGoInbox,
                    modifier = Modifier.testTag("home_review_inbox_button"),
                )
            }
        }

        // Drafts waiting card
        LedgerCard(
            onClick = onGoActivity,
            modifier = Modifier.testTag("home_activity_card"),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(c.surfaceSubtle),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ReceiptLong,
                        contentDescription = null,
                        tint = c.muted,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Waiting for your accountant", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.muted)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${m.drafts.count} draft${if (m.drafts.count == 1) "" else "s"} in ERPNext",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = c.ink,
                    )
                }
            }
            if (m.drafts.summaryText.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = m.drafts.summaryText,
                    style = MaterialTheme.typography.bodyMedium.tnum,
                    color = c.ink,
                )
            }
            if (m.drafts.footnote != null) {
                Spacer(Modifier.height(2.dp))
                Text(m.drafts.footnote, fontSize = 13.sp, color = c.muted)
            }
            Spacer(Modifier.height(12.dp))
            LedgerButton(
                label = "Open Activity",
                onClick = onGoActivity,
                variant = ButtonVariant.SECONDARY,
                modifier = Modifier.testTag("home_open_activity_button"),
            )
        }
    }
}
