package app.onebook.autoledger.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.onebook.autoledger.core.TxnState
import app.onebook.autoledger.reports.ReportSection
import app.onebook.autoledger.state.AppContainer
import app.onebook.autoledger.ui.components.AppBanners
import app.onebook.autoledger.ui.theme.LedgerTheme
import app.onebook.autoledger.ui.theme.LocalBottomBarClearance
import kotlinx.coroutines.delay

enum class MainTab(val label: String) {
    HOME("Home"),
    REPORTS("Reports"),
    INBOX("Inbox"),
    ACTIVITY("Activity"),
    SETTINGS("Settings"),
}

@Composable
fun MainTabsScaffold(
    container: AppContainer,
    selectedTab: MainTab,
    onSelectTab: (MainTab) -> Unit,
    selectedReportSection: ReportSection = ReportSection.MIS,
    onSelectReportSection: (ReportSection) -> Unit = {},
    onSignInAgain: () -> Unit,
    onOpenTxn: (String) -> Unit,
    onOpenGroup: (String) -> Unit,
    onEditAccounts: () -> Unit,
    onRunSetupAgain: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenDebugReceiver: () -> Unit,
) {
    val c = LedgerTheme.colors
    val bootState by container.state.collectAsState()
    var pendingCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(bootState.dataVersion) {
        while (true) {
            pendingCount = try {
                container.repo.countPending(TxnState.NEW)
            } catch (_: Throwable) {
                pendingCount
            }
            delay(15_000L)
        }
    }

    val hasBanners = bootState.needsLogin || bootState.engineUnusable

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(c.paper),
    ) {
        CompositionLocalProvider(LocalBottomBarClearance provides 92.dp) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .let { if (hasBanners) it else it.statusBarsPadding() },
            ) {
                if (hasBanners) {
                    AppBanners(bootState = bootState, onSignInAgain = onSignInAgain)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    when (selectedTab) {
                        MainTab.HOME -> HomeScreen(
                            container = container,
                            onGoInbox = { onSelectTab(MainTab.INBOX) },
                            onGoActivity = { onSelectTab(MainTab.ACTIVITY) },
                            onGoSignIn = onSignInAgain,
                            onGoReports = { section ->
                                onSelectReportSection(section)
                                onSelectTab(MainTab.REPORTS)
                            },
                        )
                        MainTab.REPORTS -> ReportsScreen(
                            container = container,
                            selectedSection = selectedReportSection,
                            onSelectSection = onSelectReportSection,
                            onGoSignIn = onSignInAgain,
                        )
                        MainTab.INBOX -> InboxScreen(
                            container = container,
                            onOpenTxn = onOpenTxn,
                            onOpenGroup = onOpenGroup,
                            onOpenSettingsAccounts = onEditAccounts,
                            onOpenActivity = { onSelectTab(MainTab.ACTIVITY) },
                        )
                        MainTab.ACTIVITY -> ActivityScreen(
                            container = container,
                            onSignInAgain = onSignInAgain,
                        )
                        MainTab.SETTINGS -> SettingsScreen(
                            container = container,
                            onEditAccounts = onEditAccounts,
                            onRunSetupAgain = onRunSetupAgain,
                            onOpenRules = onOpenRules,
                            onOpenDebugReceiver = onOpenDebugReceiver,
                        )
                    }
                }
            }
        }

        // Floating navigation footer
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("floating_footer"),
                shape = RoundedCornerShape(28.dp),
                color = c.surface,
                tonalElevation = 4.dp,
                shadowElevation = 12.dp,
                border = BorderStroke(1.dp, c.line),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MainTab.entries.forEach { tab ->
                        val selected = selectedTab == tab
                        val icon = when (tab) {
                            MainTab.HOME -> if (selected) Icons.Rounded.Home else Icons.Outlined.Home
                            MainTab.REPORTS -> if (selected) Icons.Rounded.BarChart else Icons.Outlined.BarChart
                            MainTab.INBOX -> if (selected) Icons.Rounded.Inbox else Icons.Outlined.Inbox
                            MainTab.ACTIVITY -> if (selected) Icons.AutoMirrored.Rounded.ReceiptLong else Icons.AutoMirrored.Outlined.ReceiptLong
                            MainTab.SETTINGS -> if (selected) Icons.Rounded.Settings else Icons.Outlined.Settings
                        }
                        val tint = if (selected) c.accent else c.muted

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 52.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .clickable { onSelectTab(tab) }
                                .padding(vertical = 5.dp, horizontal = 2.dp)
                                .testTag("tab_${tab.name.lowercase()}"),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(if (selected) c.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
                                    .padding(horizontal = 12.dp, vertical = 3.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (tab == MainTab.INBOX && pendingCount > 0) {
                                    BadgedBox(
                                        badge = {
                                            Badge(
                                                containerColor = c.accent,
                                                contentColor = c.surface,
                                            ) {
                                                Text(
                                                    text = if (pendingCount > 99) "99+" else pendingCount.toString(),
                                                    fontSize = 10.sp,
                                                )
                                            }
                                        },
                                    ) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = tab.label,
                                            tint = tint,
                                            modifier = Modifier.size(21.dp),
                                        )
                                    }
                                } else {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = tab.label,
                                        tint = tint,
                                        modifier = Modifier.size(21.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = tab.label,
                                fontSize = 11.sp,
                                lineHeight = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = tint,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
