package app.onebook.autoledger.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.onebook.autoledger.BuildConfig
import app.onebook.autoledger.core.AccountMapping
import app.onebook.autoledger.core.AccountType
import app.onebook.autoledger.ingest.IngestPipeline
import app.onebook.autoledger.sms.EngineHealth
import app.onebook.autoledger.sms.Perms
import app.onebook.autoledger.state.AppContainer
import app.onebook.autoledger.state.SetupLogic
import app.onebook.autoledger.ui.components.BackfillChoiceSection
import app.onebook.autoledger.ui.components.BannerTone
import app.onebook.autoledger.ui.components.ButtonVariant
import app.onebook.autoledger.ui.components.LedgerBanner
import app.onebook.autoledger.ui.components.LedgerButton
import app.onebook.autoledger.ui.components.LedgerCard
import app.onebook.autoledger.ui.components.LedgerConfirmDialog
import app.onebook.autoledger.ui.components.LedgerScreen
import app.onebook.autoledger.ui.theme.LedgerTheme
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    container: AppContainer,
    onEditAccounts: () -> Unit,
    onRunSetupAgain: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenDebugReceiver: () -> Unit,
) {
    val c = LedgerTheme.colors
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val bootState by container.state.collectAsState()

    var company by remember { mutableStateOf("") }
    var mappings by remember { mutableStateOf<List<AccountMapping>>(emptyList()) }
    var drawingsAccount by remember { mutableStateOf("") }
    var ruleCount by remember { mutableIntStateOf(0) }
    var days by remember { mutableIntStateOf(IngestPipeline.DEFAULT_BACKFILL_DAYS) }
    var permStatus by remember { mutableStateOf(container.sms.permissionStatus()) }
    var health by remember { mutableStateOf<EngineHealth?>(null) }

    var scanning by remember { mutableStateOf(false) }
    var scanMessage by remember { mutableStateOf<Pair<BannerTone, String>?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }

    LaunchedEffect(bootState.dataVersion) {
        company = container.repo.getSetting(AppContainer.COMPANY_KEY).orEmpty()
        drawingsAccount = container.repo.getSetting(AppContainer.DRAWINGS_KEY).orEmpty()
        mappings = container.repo.listMappings()
        ruleCount = container.repo.listRules().size
        days = IngestPipeline.getBackfillDays(container.repo)
        health = container.sms.engineHealth()
        permStatus = container.sms.permissionStatus()
    }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permStatus = container.sms.permissionStatus()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permStatus = container.sms.permissionStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LedgerScreen(
        title = "Settings",
        subtitle = bootState.session?.siteUrl,
        applySystemBarPadding = false,
    ) {
        // Account & company
        LedgerCard {
            Text("ERPNext connection", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
            Spacer(Modifier.height(6.dp))
            Text("Signed in as ${bootState.session?.user ?: "—"}", fontSize = 14.sp, color = c.muted)
            if (company.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text("Company: $company", fontSize = 14.sp, color = c.muted)
            }
            if (drawingsAccount.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text("Personal / Drawings: $drawingsAccount", fontSize = 14.sp, color = c.muted)
            }
            Spacer(Modifier.height(12.dp))
            LedgerButton(
                label = "Run setup again",
                variant = ButtonVariant.SECONDARY,
                onClick = onRunSetupAgain,
            )
            Spacer(Modifier.height(8.dp))
            LedgerButton(
                label = "Sign out",
                variant = ButtonVariant.DANGER,
                onClick = { confirmSignOut = true },
            )
        }

        // Configured accounts
        LedgerCard {
            Text("Bank & card accounts", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
            Spacer(Modifier.height(6.dp))
            if (mappings.isEmpty()) {
                Text("No accounts configured yet.", fontSize = 14.sp, color = c.muted)
            } else {
                mappings.forEach { m ->
                    val kindTxt = if (m.kind == AccountType.CARD) "Credit card" else "Bank"
                    Text(
                        text = "${m.bankAccount} ($kindTxt · ${m.last4.joinToString(", ")})",
                        fontSize = 14.sp,
                        color = c.ink,
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            LedgerButton(
                label = "Edit accounts",
                variant = ButtonVariant.SECONDARY,
                onClick = onEditAccounts,
            )
        }

        // Automatic rules
        LedgerCard {
            Text("Automatic rules", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (ruleCount == 0) "No saved rules yet." else "$ruleCount saved rule${if (ruleCount == 1) "" else "s"}",
                fontSize = 14.sp,
                color = c.muted,
            )
            Spacer(Modifier.height(10.dp))
            LedgerButton(
                label = "Manage rules",
                variant = ButtonVariant.SECONDARY,
                onClick = onOpenRules,
            )
        }

        // SMS permissions & Backfill
        LedgerCard {
            Text("SMS reading", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
            Spacer(Modifier.height(6.dp))
            Text(
                text = buildString {
                    append("Read SMS: ${if (permStatus.readSms) "Allowed" else "Not allowed"}\n")
                    append("Receive SMS: ${if (permStatus.receiveSms) "Allowed" else "Not allowed"}\n")
                    append("Notifications: ${if (permStatus.notifications) "Allowed" else "Off"}")
                    health?.let { h ->
                        if (!h.usable) append("\nReader issue: ${h.reason}")
                    }
                },
                fontSize = 13.5.sp,
                lineHeight = 20.sp,
                color = c.muted,
            )
            if (!permStatus.allGranted) {
                Spacer(Modifier.height(10.dp))
                LedgerButton(
                    label = "Grant SMS & notification access",
                    variant = ButtonVariant.SECONDARY,
                    onClick = { permLauncher.launch(Perms.wanted()) },
                )
            }
            Spacer(Modifier.height(12.dp))
            BackfillChoiceSection(
                selectedDays = days,
                onSelect = { chosenDays ->
                    days = chosenDays
                    scope.launch {
                        container.repo.setSetting(IngestPipeline.BACKFILL_DAYS_KEY, chosenDays.toString())
                    }
                },
                note = SetupLogic.windowDisclosure(days),
            )
            if (scanMessage != null) {
                Spacer(Modifier.height(8.dp))
                LedgerBanner(tone = scanMessage!!.first, compact = true, message = scanMessage!!.second)
            }
            Spacer(Modifier.height(10.dp))
            LedgerButton(
                label = "Scan messages now",
                variant = ButtonVariant.SECONDARY,
                enabled = permStatus.readSms && !scanning,
                loading = scanning,
                onClick = {
                    scope.launch {
                        scanning = true
                        scanMessage = null
                        try {
                            IngestPipeline.syncSenderPatterns(container.repo, container.sms)
                            val res = IngestPipeline.ensureBackfill(
                                repo = container.repo,
                                sms = container.sms,
                                fullWindow = true,
                            )
                            container.bumpData()
                            container.bumpHome()
                            scanMessage = BannerTone.OK to SetupLogic.foundMessage(res)
                        } catch (_: Throwable) {
                            scanMessage = BannerTone.ERROR to "Could not scan messages right now."
                        } finally {
                            scanning = false
                        }
                    }
                },
            )
        }

        if (BuildConfig.DEBUG) {
            LedgerCard {
                Text("Developer tools", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
                Spacer(Modifier.height(8.dp))
                LedgerButton(
                    label = "SMS receiver test",
                    variant = ButtonVariant.SECONDARY,
                    onClick = onOpenDebugReceiver,
                )
            }
        }

        Text(
            text = "AutoLedger ${BuildConfig.VERSION_NAME}",
            fontSize = 12.5.sp,
            color = c.muted,
        )
    }

    LedgerConfirmDialog(
        visible = confirmSignOut,
        title = "Sign out?",
        message = "You will need to sign in again before entries can be created in ERPNext.",
        confirmLabel = "Sign out",
        destructive = true,
        onConfirm = {
            confirmSignOut = false
            scope.launch {
                container.signOut()
            }
        },
        onDismiss = { confirmSignOut = false },
    )
}
