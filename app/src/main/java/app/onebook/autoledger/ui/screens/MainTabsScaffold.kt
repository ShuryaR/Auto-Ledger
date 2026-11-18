package app.onebook.autoledger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.onebook.autoledger.core.TxnState
import app.onebook.autoledger.state.AppContainer
import app.onebook.autoledger.ui.components.AppBanners
import app.onebook.autoledger.ui.theme.LedgerTheme
import kotlinx.coroutines.delay

enum class MainTab(val label: String) {
    HOME("Home"),
    INBOX("Inbox"),
    ACTIVITY("Activity"),
    SETTINGS("Settings"),
}

@Composable
fun MainTabsScaffold(
    container: AppContainer,
    selectedTab: MainTab,
    onSelectTab: (MainTab) -> Unit,
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

    Scaffold(
        containerColor = c.paper,
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider(color = c.line, thickness = 1.dp)
                NavigationBar(
                    containerColor = c.surface,
                    tonalElevation = 0.dp,
                ) {
                    MainTab.entries.forEach { tab ->
                        val selected = selectedTab == tab
                        val icon = when (tab) {
                            MainTab.HOME -> if (selected) Icons.Rounded.Home else Icons.Outlined.Home
                            MainTab.INBOX -> if (selected) Icons.Rounded.Inbox else Icons.Outlined.Inbox
                            MainTab.ACTIVITY -> if (selected) Icons.AutoMirrored.Rounded.ReceiptLong else Icons.AutoMirrored.Outlined.ReceiptLong
                            MainTab.SETTINGS -> if (selected) Icons.Rounded.Settings else Icons.Outlined.Settings
                        }
                        NavigationBarItem(
                            selected = selected,
                            onClick = { onSelectTab(tab) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = c.accent,
                                selectedTextColor = c.accent,
                                unselectedIconColor = c.muted,
                                unselectedTextColor = c.muted,
                                indicatorColor = c.accentSoft,
                            ),
                            label = {
                                Text(
                                    text = tab.label,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            },
                            icon = {
                                if (tab == MainTab.INBOX && pendingCount > 0) {
                                    BadgedBox(
                                        badge = {
                                            Badge(
                                                containerColor = c.accent,
                                                contentColor = c.surface,
                                            ) {
                                                Text(if (pendingCount > 99) "99+" else pendingCount.toString())
                                            }
                                        },
                                    ) {
                                        Icon(icon, contentDescription = tab.label)
                                    }
                                } else {
                                    Icon(icon, contentDescription = tab.label)
                                }
                            },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(c.paper)
                .let { if (hasBanners) it else it.statusBarsPadding() },
        ) {
            if (hasBanners) {
                AppBanners(bootState = bootState, onSignInAgain = onSignInAgain)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(bottom = innerPadding.calculateBottomPadding()),
            ) {
                when (selectedTab) {
                    MainTab.HOME -> HomeScreen(
                        container = container,
                        onGoInbox = { onSelectTab(MainTab.INBOX) },
                        onGoActivity = { onSelectTab(MainTab.ACTIVITY) },
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
}
