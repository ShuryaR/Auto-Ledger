package app.onebook.autoledger.ui.navigation

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.onebook.autoledger.BuildConfig
import app.onebook.autoledger.inbox.Step
import app.onebook.autoledger.reports.ReportSection
import app.onebook.autoledger.state.AppContainer
import app.onebook.autoledger.state.GateTarget
import app.onebook.autoledger.ui.components.BannerTone
import app.onebook.autoledger.ui.components.LedgerBanner
import app.onebook.autoledger.ui.components.LedgerButton
import app.onebook.autoledger.ui.screens.DebugReceiverScreen
import app.onebook.autoledger.ui.screens.LoginScreen
import app.onebook.autoledger.ui.screens.MainTab
import app.onebook.autoledger.ui.screens.MainTabsScaffold
import app.onebook.autoledger.ui.screens.RulesScreen
import app.onebook.autoledger.ui.screens.SetupAccountsScreen
import app.onebook.autoledger.ui.screens.SetupCompanyScreen
import app.onebook.autoledger.ui.screens.SetupDefaultsScreen
import app.onebook.autoledger.ui.screens.SetupPermissionsScreen
import app.onebook.autoledger.ui.screens.TxnAccountScreen
import app.onebook.autoledger.ui.screens.TxnAmountScreen
import app.onebook.autoledger.ui.screens.TxnBulkScreen
import app.onebook.autoledger.ui.screens.TxnConfirmScreen
import app.onebook.autoledger.ui.screens.TxnDetailScreen
import app.onebook.autoledger.ui.screens.TxnInvoicesScreen
import app.onebook.autoledger.ui.screens.TxnOrderScreen
import app.onebook.autoledger.ui.screens.TxnPartyScreen
import app.onebook.autoledger.ui.screens.TxnResultScreen
import app.onebook.autoledger.ui.theme.LedgerTheme
import org.json.JSONArray
import org.json.JSONObject

object Routes {
    const val LOGIN = "login"
    const val LOGIN_AGAIN = "login/again"
    const val SETUP_COMPANY = "setup/company"
    const val SETUP_ACCOUNTS = "setup/accounts?fromSettings={fromSettings}"
    const val SETUP_DEFAULTS = "setup/defaults"
    const val SETUP_PERMISSIONS = "setup/permissions"
    const val TABS = "tabs"
    const val TXN_DETAIL = "txn/{id}"
    const val TXN_AMOUNT = "txn/{id}/amount"
    const val TXN_PARTY = "txn/{id}/party"
    const val TXN_ORDER = "txn/{id}/order"
    const val TXN_INVOICES = "txn/{id}/invoices"
    const val TXN_ACCOUNT = "txn/{id}/account"
    const val TXN_CONFIRM = "txn/{id}/confirm"
    const val TXN_RESULT = "txn/result?offline={offline}&docs={docs}"
    const val TXN_BULK = "txn/bulk?key={key}"
    const val RULES = "rules"
    const val DEBUG_RECEIVER = "debug/receiver"

    fun login(canGoBack: Boolean = false) = if (canGoBack) LOGIN_AGAIN else LOGIN
    fun setupAccounts(fromSettings: Boolean = false) = "setup/accounts?fromSettings=$fromSettings"
    fun txnDetail(id: String) = "txn/${Uri.encode(id)}"
    fun txnStep(id: String, step: Step): String {
        val enc = Uri.encode(id)
        return when (step) {
            Step.CHOOSE -> "txn/$enc"
            Step.AMOUNT -> "txn/$enc/amount"
            Step.PARTY -> "txn/$enc/party"
            Step.ORDER -> "txn/$enc/order"
            Step.INVOICES -> "txn/$enc/invoices"
            Step.ACCOUNT -> "txn/$enc/account"
            Step.CONFIRM -> "txn/$enc/confirm"
        }
    }

    fun txnResult(offline: Boolean, docs: List<Pair<String, String>>): String {
        val arr = JSONArray().apply {
            docs.forEach { (dt, name) ->
                put(JSONObject().put("dt", dt).put("name", name))
            }
        }
        return "txn/result?offline=$offline&docs=${Uri.encode(arr.toString())}"
    }

    fun parseDocsArg(raw: String): List<Pair<String, String>> = try {
        val arr = JSONArray(Uri.decode(raw))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val dt = o.optString("dt", "")
            val name = o.optString("name", "")
            if (dt.isNotEmpty() && name.isNotEmpty()) dt to name else null
        }
    } catch (_: Throwable) {
        emptyList()
    }

    fun txnBulk(key: String) = "txn/bulk?key=${Uri.encode(key)}"
}

@Composable
fun AutoLedgerApp(
    container: AppContainer,
    deepLinkToInboxSignal: Long,
) {
    val c = LedgerTheme.colors
    val bootState by container.state.collectAsState()

    if (!bootState.bootReady) {
        Box(
            modifier = Modifier.fillMaxSize().background(c.paper),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = c.accent)
        }
        return
    }

    if (bootState.bootFailed) {
        Column(
            modifier = Modifier.fillMaxSize().background(c.paper).padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Could not open AutoLedger", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.ink)
            Spacer(Modifier.height(8.dp))
            LedgerBanner(
                tone = BannerTone.ERROR,
                message = "The local database on this phone could not be opened.",
            )
            LedgerButton(label = "Try again", onClick = { container.boot() })
        }
        return
    }

    val navController = rememberNavController()
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.HOME) }
    var selectedReportSection by rememberSaveable { mutableStateOf(ReportSection.MIS) }
    val backEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backEntry?.destination?.route

    val startDestination = when (bootState.gateTarget()) {
        GateTarget.LOGIN -> Routes.LOGIN
        GateTarget.SETUP -> Routes.SETUP_COMPANY
        GateTarget.HOME -> Routes.TABS
    }

    // Redirect immediately on sign-out
    LaunchedEffect(bootState.signedIn) {
        if (!bootState.signedIn && currentRoute != null && !currentRoute.startsWith("login")) {
            navController.navigate(Routes.login(canGoBack = false)) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    // Handle notification deep link to Inbox safely (without interpreting external args)
    LaunchedEffect(deepLinkToInboxSignal) {
        if (deepLinkToInboxSignal > 0L && bootState.gateTarget() == GateTarget.HOME) {
            selectedTab = MainTab.INBOX
            navController.popBackStack(Routes.TABS, inclusive = false)
        }
    }

    fun returnToInboxTab() {
        selectedTab = MainTab.INBOX
        if (!navController.popBackStack(Routes.TABS, inclusive = false)) {
            navController.navigate(Routes.TABS) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    fun returnToActivityTab() {
        selectedTab = MainTab.ACTIVITY
        if (!navController.popBackStack(Routes.TABS, inclusive = false)) {
            navController.navigate(Routes.TABS) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(Routes.LOGIN) {
            LoginScreen(
                container = container,
                initialSiteUrl = bootState.session?.siteUrl.orEmpty(),
                initialUser = bootState.session?.user.orEmpty(),
                canGoBack = false,
                onBack = {},
                onSignedIn = {
                    val dest = if (container.state.value.setupDone) Routes.TABS else Routes.SETUP_COMPANY
                    navController.navigate(dest) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.LOGIN_AGAIN) {
            LoginScreen(
                container = container,
                initialSiteUrl = bootState.session?.siteUrl.orEmpty(),
                initialUser = bootState.session?.user.orEmpty(),
                canGoBack = true,
                onBack = { navController.popBackStack() },
                onSignedIn = {
                    val dest = if (container.state.value.setupDone) Routes.TABS else Routes.SETUP_COMPANY
                    navController.navigate(dest) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.SETUP_COMPANY) {
            SetupCompanyScreen(
                container = container,
                onContinue = { navController.navigate(Routes.setupAccounts(fromSettings = false)) },
                onBack = if (bootState.setupDone) {
                    { navController.popBackStack() }
                } else null,
            )
        }

        composable(
            route = Routes.SETUP_ACCOUNTS,
            arguments = listOf(navArgument("fromSettings") { type = NavType.BoolType; defaultValue = false }),
        ) { entry ->
            val fromSettings = entry.arguments?.getBoolean("fromSettings") ?: false
            SetupAccountsScreen(
                container = container,
                onBack = { navController.popBackStack() },
                onContinue = {
                    if (fromSettings) {
                        navController.popBackStack()
                    } else {
                        navController.navigate(Routes.SETUP_DEFAULTS)
                    }
                },
            )
        }

        composable(Routes.SETUP_DEFAULTS) {
            SetupDefaultsScreen(
                container = container,
                onBack = { navController.popBackStack() },
                onContinue = { navController.navigate(Routes.SETUP_PERMISSIONS) },
            )
        }

        composable(Routes.SETUP_PERMISSIONS) {
            SetupPermissionsScreen(
                container = container,
                onBack = { navController.popBackStack() },
                onDone = {
                    selectedTab = MainTab.HOME
                    navController.navigate(Routes.TABS) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.TABS) {
            MainTabsScaffold(
                container = container,
                selectedTab = selectedTab,
                onSelectTab = { selectedTab = it },
                selectedReportSection = selectedReportSection,
                onSelectReportSection = { selectedReportSection = it },
                onSignInAgain = { navController.navigate(Routes.login(canGoBack = true)) },
                onOpenTxn = { txnId -> navController.navigate(Routes.txnDetail(txnId)) },
                onOpenGroup = { groupKey -> navController.navigate(Routes.txnBulk(groupKey)) },
                onEditAccounts = { navController.navigate(Routes.setupAccounts(fromSettings = true)) },
                onRunSetupAgain = { navController.navigate(Routes.SETUP_COMPANY) },
                onOpenRules = { navController.navigate(Routes.RULES) },
                onOpenDebugReceiver = {
                    if (BuildConfig.DEBUG) navController.navigate(Routes.DEBUG_RECEIVER)
                },
            )
        }

        composable(
            route = Routes.TXN_DETAIL,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = Uri.decode(entry.arguments?.getString("id").orEmpty())
            TxnDetailScreen(
                container = container,
                txnId = id,
                onBackToInbox = { returnToInboxTab() },
                onOpenActivity = { returnToActivityTab() },
                onNavigateStep = { step -> navController.navigate(Routes.txnStep(id, step)) },
            )
        }

        composable(
            route = Routes.TXN_AMOUNT,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = Uri.decode(entry.arguments?.getString("id").orEmpty())
            TxnAmountScreen(
                container = container,
                txnId = id,
                onBack = { navController.popBackStack() },
                onBackToInbox = { returnToInboxTab() },
                onNavigateStep = { step -> navController.navigate(Routes.txnStep(id, step)) },
            )
        }

        composable(
            route = Routes.TXN_PARTY,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = Uri.decode(entry.arguments?.getString("id").orEmpty())
            TxnPartyScreen(
                container = container,
                txnId = id,
                onBack = { navController.popBackStack() },
                onBackToInbox = { returnToInboxTab() },
                onNavigateStep = { step -> navController.navigate(Routes.txnStep(id, step)) },
            )
        }

        composable(
            route = Routes.TXN_ORDER,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = Uri.decode(entry.arguments?.getString("id").orEmpty())
            TxnOrderScreen(
                container = container,
                txnId = id,
                onBack = { navController.popBackStack() },
                onBackToInbox = { returnToInboxTab() },
                onNavigateStep = { step -> navController.navigate(Routes.txnStep(id, step)) },
            )
        }

        composable(
            route = Routes.TXN_INVOICES,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = Uri.decode(entry.arguments?.getString("id").orEmpty())
            TxnInvoicesScreen(
                container = container,
                txnId = id,
                onBack = { navController.popBackStack() },
                onBackToInbox = { returnToInboxTab() },
                onNavigateStep = { step -> navController.navigate(Routes.txnStep(id, step)) },
            )
        }

        composable(
            route = Routes.TXN_ACCOUNT,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = Uri.decode(entry.arguments?.getString("id").orEmpty())
            TxnAccountScreen(
                container = container,
                txnId = id,
                onBack = { navController.popBackStack() },
                onBackToInbox = { returnToInboxTab() },
                onNavigateStep = { step -> navController.navigate(Routes.txnStep(id, step)) },
            )
        }

        composable(
            route = Routes.TXN_CONFIRM,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = Uri.decode(entry.arguments?.getString("id").orEmpty())
            TxnConfirmScreen(
                container = container,
                txnId = id,
                onBack = { navController.popBackStack() },
                onBackToInbox = { returnToInboxTab() },
                onSignInAgain = { navController.navigate(Routes.login(canGoBack = true)) },
                onResult = { offline, docs ->
                    navController.navigate(Routes.txnResult(offline, docs)) {
                        popUpTo(Routes.TABS) { inclusive = false }
                    }
                },
            )
        }

        composable(
            route = Routes.TXN_RESULT,
            arguments = listOf(
                navArgument("offline") { type = NavType.BoolType; defaultValue = false },
                navArgument("docs") { type = NavType.StringType; defaultValue = "[]" },
            ),
        ) { entry ->
            val offline = entry.arguments?.getBoolean("offline") ?: false
            val docs = Routes.parseDocsArg(entry.arguments?.getString("docs").orEmpty())
            TxnResultScreen(
                container = container,
                offline = offline,
                docs = docs,
                onBackToInbox = { returnToInboxTab() },
                onOpenActivity = { returnToActivityTab() },
            )
        }

        composable(
            route = Routes.TXN_BULK,
            arguments = listOf(navArgument("key") { type = NavType.StringType }),
        ) { entry ->
            val key = Uri.decode(entry.arguments?.getString("key").orEmpty())
            TxnBulkScreen(
                container = container,
                groupKey = key,
                onBackToInbox = { returnToInboxTab() },
            )
        }

        composable(Routes.RULES) {
            RulesScreen(
                container = container,
                onBack = { navController.popBackStack() },
            )
        }

        if (BuildConfig.DEBUG) {
            composable(Routes.DEBUG_RECEIVER) {
                DebugReceiverScreen(
                    container = container,
                    onBack = { navController.popBackStack() },
                    onOpenInbox = { returnToInboxTab() },
                )
            }
        }
    }
}
