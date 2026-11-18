package app.onebook.autoledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.onebook.autoledger.home.HomeLoadStatus
import app.onebook.autoledger.home.HomeLogic
import app.onebook.autoledger.home.HomeUiState
import app.onebook.autoledger.state.AppContainer
import app.onebook.autoledger.ui.components.BannerTone
import app.onebook.autoledger.ui.components.ButtonVariant
import app.onebook.autoledger.ui.components.LedgerBanner
import app.onebook.autoledger.ui.components.LedgerButton
import app.onebook.autoledger.ui.components.LedgerCard
import app.onebook.autoledger.ui.components.LedgerFigure
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
        subtitle = uiState.model?.lastSyncedText ?: "Overview",
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

        // Bank balances card
        LedgerCard {
            val accounts = m.bank.accounts
            Text(
                text = if (accounts.size > 1) "Bank balances" else "Bank balance",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.muted,
            )
            Spacer(Modifier.height(8.dp))
            if (accounts.isEmpty()) {
                Text(
                    text = "No bank accounts configured.",
                    fontSize = 14.sp,
                    color = c.muted,
                )
            } else if (accounts.size == 1) {
                val acc = accounts[0]
                LedgerFigure(
                    value = acc.text,
                    label = acc.name,
                    note = m.asOfLabel,
                )
            } else {
                val tot = m.bank.total
                if (tot != null) {
                    LedgerFigure(value = tot.text, label = "Total across ${accounts.size} accounts", note = m.asOfLabel)
                    HorizontalDivider(color = c.line, modifier = Modifier.padding(vertical = 12.dp))
                }
                accounts.forEachIndexed { idx, acc ->
                    if (idx > 0) Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = acc.name,
                            fontSize = 14.5.sp,
                            color = c.ink,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = acc.text,
                            style = MaterialTheme.typography.titleMedium.tnum,
                            fontWeight = FontWeight.SemiBold,
                            color = c.ink,
                        )
                    }
                }
            }
        }

        // Receivable & Payable row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LedgerCard(modifier = Modifier.weight(1f)) {
                Text(m.receivable.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.muted)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = m.receivable.text,
                    style = MaterialTheme.typography.titleLarge.tnum,
                    fontWeight = FontWeight.Bold,
                    color = c.moneyIn,
                )
            }
            LedgerCard(modifier = Modifier.weight(1f)) {
                Text(m.payable.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.muted)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = m.payable.text,
                    style = MaterialTheme.typography.titleLarge.tnum,
                    fontWeight = FontWeight.Bold,
                    color = c.moneyOut,
                )
            }
        }

        // Unanswered Inbox CTA card
        LedgerCard(
            accentBorder = m.unclassifiedCount > 0,
            onClick = onGoInbox,
        ) {
            Text("Inbox", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.muted)
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (m.unclassifiedCount == 0) {
                    "All bank messages answered"
                } else {
                    "${m.unclassifiedCount} message${if (m.unclassifiedCount == 1) "" else "s"} to answer"
                },
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = c.ink,
            )
            if (m.unclassifiedCount > 0) {
                Spacer(Modifier.height(10.dp))
                LedgerButton(label = "Review Inbox", onClick = onGoInbox)
            }
        }

        // Drafts waiting card
        LedgerCard(onClick = onGoActivity) {
            Text("Waiting for your accountant", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.muted)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${m.drafts.count} draft${if (m.drafts.count == 1) "" else "s"} in ERPNext",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = c.ink,
            )
            if (m.drafts.summaryText.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(m.drafts.summaryText, style = MaterialTheme.typography.bodyMedium.tnum, color = c.ink)
            }
            if (m.drafts.footnote != null) {
                Spacer(Modifier.height(2.dp))
                Text(m.drafts.footnote, fontSize = 13.sp, color = c.muted)
            }
            Spacer(Modifier.height(10.dp))
            LedgerButton(
                label = "Open Activity",
                onClick = onGoActivity,
                variant = ButtonVariant.SECONDARY,
            )
        }
    }
}
