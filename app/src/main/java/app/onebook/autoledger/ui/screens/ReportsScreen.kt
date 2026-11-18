package app.onebook.autoledger.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.onebook.autoledger.erp.AgeingBucket
import app.onebook.autoledger.erp.ExposureKind
import app.onebook.autoledger.home.HomeLogic
import app.onebook.autoledger.reports.ExposureSort
import app.onebook.autoledger.reports.PnlPeriod
import app.onebook.autoledger.reports.ReportSection
import app.onebook.autoledger.reports.ReportsLoadStatus
import app.onebook.autoledger.reports.ReportsLogic
import app.onebook.autoledger.reports.ReportsUiState
import app.onebook.autoledger.state.AppContainer
import app.onebook.autoledger.ui.components.BannerTone
import app.onebook.autoledger.ui.components.LedgerBanner
import app.onebook.autoledger.ui.components.LedgerLazyScreen
import app.onebook.autoledger.ui.components.SegmentedPillBar
import kotlinx.coroutines.launch

@Composable
fun ReportsScreen(
    container: AppContainer,
    selectedSection: ReportSection,
    onSelectSection: (ReportSection) -> Unit,
    onGoSignIn: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val bootState by container.state.collectAsState()
    var uiState by remember { mutableStateOf(ReportsUiState()) }
    var refreshing by remember { mutableStateOf(false) }

    var pnlPeriod by remember { mutableStateOf(PnlPeriod.MTD) }
    var incomeExpanded by remember { mutableStateOf(true) }
    var expenseExpanded by remember { mutableStateOf(true) }

    var arQuery by remember { mutableStateOf("") }
    var arBucket by remember { mutableStateOf<AgeingBucket?>(null) }
    var arSort by remember { mutableStateOf(ExposureSort.HIGHEST_DUE) }

    var apQuery by remember { mutableStateOf("") }
    var apBucket by remember { mutableStateOf<AgeingBucket?>(null) }
    var apSort by remember { mutableStateOf(ExposureSort.HIGHEST_DUE) }

    suspend fun load() {
        uiState = ReportsLogic.loadReportsSnapshot(
            repo = container.repo,
            client = container.client,
            siteUrl = bootState.session?.siteUrl,
        )
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
                load()
            } finally {
                refreshing = false
            }
        }
    }

    val snap = uiState.snapshot
    val subtitle = when {
        snap != null && snap.stale -> "Saved figures ${HomeLogic.relativeTime(System.currentTimeMillis(), snap.fetchedAt)}"
        snap != null -> "${snap.company} \u00B7 Updated ${HomeLogic.relativeTime(System.currentTimeMillis(), snap.fetchedAt)}"
        else -> "MIS, P&L, Runway & Ageing"
    }

    LedgerLazyScreen(
        title = "Reports",
        subtitle = subtitle,
        refreshing = refreshing,
        onRefresh = { onRefresh() },
        applySystemBarPadding = false,
        headerExtra = {
            SegmentedPillBar(
                items = ReportSection.entries,
                selected = selectedSection,
                labelOf = { it.label },
                onSelect = onSelectSection,
                tagPrefix = "report_tab",
            )
        },
    ) {
        if (snap?.authExpired == true) {
            item(key = "__auth_expired__") {
                LedgerBanner(
                    tone = BannerTone.WARN,
                    title = "Session expired",
                    message = "ERPNext did not accept your saved login. Sign in again to refresh live reports.",
                    actionLabel = "Sign in again",
                    onAction = onGoSignIn,
                )
            }
        }

        if (snap != null && snap.stale) {
            item(key = "__stale_banner__") {
                LedgerBanner(
                    tone = BannerTone.WARN,
                    compact = true,
                    message = "Showing saved report figures from ${HomeLogic.relativeTime(System.currentTimeMillis(), snap.fetchedAt)}. Pull down to refresh.",
                )
            }
        }

        if (uiState.status == ReportsLoadStatus.LOADING && snap == null) {
            item(key = "__loading__") {
                LedgerBanner(message = "Loading MIS, P&L & ageing reports from ERPNext...")
            }
            return@LedgerLazyScreen
        }

        if (uiState.status == ReportsLoadStatus.ERROR && snap == null) {
            item(key = "__error__") {
                LedgerBanner(
                    tone = BannerTone.ERROR,
                    title = "Could not load reports",
                    message = uiState.errorMessage ?: "Check your connection and pull down to retry.",
                    actionLabel = if (uiState.errorMessage?.contains("Sign in", ignoreCase = true) == true) {
                        "Sign in again"
                    } else {
                        "Try again"
                    },
                    onAction = {
                        if (uiState.errorMessage?.contains("Sign in", ignoreCase = true) == true) {
                            onGoSignIn()
                        } else {
                            onRefresh()
                        }
                    },
                )
            }
            return@LedgerLazyScreen
        }

        val readySnap = snap ?: return@LedgerLazyScreen

        when (selectedSection) {
            ReportSection.MIS -> misSectionItems(
                snapshot = readySnap,
                onJumpSection = onSelectSection,
            )
            ReportSection.PNL -> pnlSectionItems(
                snapshot = readySnap,
                period = pnlPeriod,
                onSelectPeriod = { pnlPeriod = it },
                incomeExpanded = incomeExpanded,
                onToggleIncome = { incomeExpanded = !incomeExpanded },
                expenseExpanded = expenseExpanded,
                onToggleExpense = { expenseExpanded = !expenseExpanded },
            )
            ReportSection.AR -> exposureSectionItems(
                kind = ExposureKind.AR,
                report = readySnap.arReport,
                errorMessage = readySnap.arError,
                query = arQuery,
                onQueryChange = { arQuery = it },
                selectedBucket = arBucket,
                onSelectBucket = { arBucket = it },
                sort = arSort,
                onSelectSort = { arSort = it },
            )
            ReportSection.AP -> exposureSectionItems(
                kind = ExposureKind.AP,
                report = readySnap.apReport,
                errorMessage = readySnap.apError,
                query = apQuery,
                onQueryChange = { apQuery = it },
                selectedBucket = apBucket,
                onSelectBucket = { apBucket = it },
                sort = apSort,
                onSelectSort = { apSort = it },
            )
        }
    }
}
