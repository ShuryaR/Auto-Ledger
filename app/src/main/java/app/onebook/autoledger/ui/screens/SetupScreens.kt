package app.onebook.autoledger.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.onebook.autoledger.core.AccountType
import app.onebook.autoledger.erp.ErpDocs
import app.onebook.autoledger.ingest.BackfillResult
import app.onebook.autoledger.ingest.IngestPipeline
import app.onebook.autoledger.sms.Perms
import app.onebook.autoledger.sms.PermissionStatus
import app.onebook.autoledger.state.AccountDraft
import app.onebook.autoledger.state.AppContainer
import app.onebook.autoledger.state.SetupLogic
import app.onebook.autoledger.ui.components.BackfillChoiceSection
import app.onebook.autoledger.ui.components.BannerTone
import app.onebook.autoledger.ui.components.ButtonVariant
import app.onebook.autoledger.ui.components.ChoiceRole
import app.onebook.autoledger.ui.components.LedgerBanner
import app.onebook.autoledger.ui.components.LedgerButton
import app.onebook.autoledger.ui.components.LedgerCard
import app.onebook.autoledger.ui.components.LedgerChip
import app.onebook.autoledger.ui.components.LedgerChoiceRow
import app.onebook.autoledger.ui.components.LedgerScreen
import app.onebook.autoledger.ui.components.LedgerTextField
import app.onebook.autoledger.ui.components.StepHeader
import app.onebook.autoledger.ui.theme.LedgerTheme
import kotlinx.coroutines.launch

@Composable
fun SetupCompanyScreen(
    container: AppContainer,
    onContinue: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val client = container.client

    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var companies by remember { mutableStateOf<List<ErpDocs.CompanyInfo>>(emptyList()) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var reloadNonce by remember { mutableIntStateOf(0) }

    LaunchedEffect(client, reloadNonce) {
        if (client == null) return@LaunchedEffect
        loading = true
        loadError = false
        try {
            val loaded = ErpDocs.listCompanies(client)
            val saved = container.repo.getSetting(AppContainer.COMPANY_KEY)
            companies = loaded
            val inr = loaded.filter { it.currency == "INR" }
            if (chosen == null) {
                if (!saved.isNullOrBlank() && inr.any { it.name == saved }) {
                    chosen = saved
                } else if (loaded.size == 1 && inr.size == 1) {
                    chosen = inr[0].name
                }
            }
        } catch (_: Throwable) {
            loadError = true
        } finally {
            loading = false
        }
    }

    val onlyForeign = companies.isNotEmpty() && companies.all { it.currency != "INR" }

    LedgerScreen(
        top = { StepHeader(step = 1, total = 4, label = "Company", onBack = onBack) },
        title = "Choose your company",
        subtitle = "AutoLedger creates draft entries in this company's books.",
        footer = {
            LedgerButton(
                label = "Continue",
                onClick = {
                    val cName = chosen ?: return@LedgerButton
                    scope.launch {
                        saving = true
                        saveFailed = false
                        try {
                            container.repo.setSetting(AppContainer.COMPANY_KEY, cName)
                            onContinue()
                        } catch (_: Throwable) {
                            saveFailed = true
                        } finally {
                            saving = false
                        }
                    }
                },
                enabled = chosen != null && !saving,
                loading = saving,
            )
        },
    ) {
        if (saveFailed) {
            LedgerBanner(
                tone = BannerTone.ERROR,
                message = "Could not save the company on this phone. Try again.",
            )
        }
        if (loading) {
            LedgerBanner(message = "Loading your companies...")
        }
        if (loadError) {
            LedgerBanner(
                tone = BannerTone.ERROR,
                title = "Could not load companies",
                message = "Check your internet connection and that your ERPNext user can read Company records.",
                actionLabel = "Try again",
                onAction = { reloadNonce++ },
            )
        }
        if (onlyForeign) {
            LedgerBanner(
                tone = BannerTone.WARN,
                title = "INR companies only",
                message = "AutoLedger works with companies whose currency is INR. None of the companies on this site use INR.",
            )
        }
        companies.forEach { company ->
            val inr = company.currency == "INR"
            LedgerChoiceRow(
                title = company.name,
                subtitle = if (inr) "Currency INR" else "Currency ${company.currency}. AutoLedger supports INR companies only.",
                selected = chosen == company.name,
                disabled = !inr,
                onClick = { chosen = company.name },
            )
        }
    }
}

private const val SAVED_ACCOUNT_NOTE =
    "Already saved. Saved accounts cannot be removed from the app yet; edit their details below."

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupAccountsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onContinue: () -> Unit,
) {
    val c = LedgerTheme.colors
    val scope = rememberCoroutineScope()
    val client = container.client

    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var drafts by remember { mutableStateOf<List<AccountDraft>?>(null) }
    var modes by remember { mutableStateOf<List<ErpDocs.ModeOfPaymentInfo>>(emptyList()) }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var none by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var reloadNonce by remember { mutableIntStateOf(0) }

    LaunchedEffect(client, reloadNonce) {
        if (client == null) return@LaunchedEffect
        loading = true
        loadError = false
        try {
            val company = container.repo.getSetting(AppContainer.COMPANY_KEY)
            if (company.isNullOrBlank()) {
                loadError = true
                return@LaunchedEffect
            }
            val accounts = ErpDocs.listBankAccounts(client, company)
            val loadedModes = ErpDocs.listModesOfPayment(client)
            val existing = container.repo.listMappings()
            val listed = accounts.filter { a ->
                a.isCompanyAccount || existing.any { m -> m.bankAccount == a.name }
            }
            modes = loadedModes
            drafts = SetupLogic.buildDrafts(listed, existing, loadedModes)
        } catch (_: Throwable) {
            loadError = true
        } finally {
            loading = false
        }
    }

    fun patchDraft(name: String, transform: (AccountDraft) -> AccountDraft) {
        none = false
        drafts = drafts?.map { d -> if (d.bankAccount == name) transform(d) else d }
    }

    LedgerScreen(
        top = { StepHeader(step = 2, total = 4, label = "Accounts", onBack = onBack) },
        title = "Which accounts send SMS alerts?",
        subtitle = "Choose the bank and card accounts AutoLedger should read messages for. At least one is needed.",
        footer = {
            LedgerButton(
                label = "Continue",
                enabled = drafts != null && !saving,
                loading = saving,
                onClick = {
                    val current = drafts ?: return@LedgerButton
                    val r = SetupLogic.validateDrafts(current)
                    errors = r.errors
                    none = r.none
                    if (r.none || r.errors.isNotEmpty()) return@LedgerButton
                    scope.launch {
                        saving = true
                        saveFailed = false
                        try {
                            for (m in r.mappings) container.repo.saveMapping(m)
                            container.bumpData()
                            container.bumpHome()
                            onContinue()
                        } catch (_: Throwable) {
                            saveFailed = true
                        } finally {
                            saving = false
                        }
                    }
                },
            )
        },
    ) {
        if (loadError) {
            LedgerBanner(
                tone = BannerTone.ERROR,
                title = "Could not load your accounts",
                message = "Check your internet connection and that your ERPNext user can read Bank Account and Mode of Payment records.",
                actionLabel = "Try again",
                onAction = { reloadNonce++ },
            )
        }
        if (loading && !loadError) {
            LedgerBanner(message = "Loading your bank accounts...")
        }
        val currentDrafts = drafts
        if (currentDrafts != null && currentDrafts.isEmpty()) {
            LedgerBanner(
                tone = BannerTone.WARN,
                title = "No bank accounts found",
                message = "Add your bank and card accounts as Bank Account records in ERPNext, then run setup again.",
            )
        }
        if (none) {
            LedgerBanner(tone = BannerTone.WARN, message = "Select at least one account to continue.")
        }
        if (saveFailed) {
            LedgerBanner(tone = BannerTone.ERROR, message = "Could not save the accounts on this phone. Try again.")
        }

        currentDrafts?.forEach { d ->
            LedgerChoiceRow(
                role = ChoiceRole.CHECKBOX,
                title = d.bankAccount,
                subtitle = when {
                    !d.supported -> SetupLogic.NOT_SUPPORTED
                    d.saved -> SAVED_ACCOUNT_NOTE
                    else -> null
                },
                selected = d.selected,
                disabled = !d.supported || d.saved,
                onClick = { patchDraft(d.bankAccount) { it.copy(selected = !it.selected) } },
            )
            if (d.selected) {
                LedgerCard {
                    Text("Type", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.ink)
                    Spacer(Modifier.height(6.dp))
                    FlowRow {
                        LedgerChip(
                            label = "Bank account",
                            selected = d.kind == AccountType.BANK,
                            onClick = { patchDraft(d.bankAccount) { it.copy(kind = AccountType.BANK) } },
                        )
                        LedgerChip(
                            label = "Credit card",
                            selected = d.kind == AccountType.CARD,
                            onClick = { patchDraft(d.bankAccount) { it.copy(kind = AccountType.CARD) } },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    LedgerTextField(
                        label = "Sender id for ${d.bankAccount}",
                        value = d.senders,
                        onValueChange = { t -> patchDraft(d.bankAccount) { it.copy(senders = t) } },
                        hint = "The name the bank's messages come from. IDFC First uses IDFCFB.",
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            autoCorrectEnabled = false,
                        ),
                    )
                    LedgerTextField(
                        label = "Last 4 digits for ${d.bankAccount}",
                        value = d.last4,
                        onValueChange = { t -> patchDraft(d.bankAccount) { it.copy(last4 = t) } },
                        placeholder = "3455, 8129",
                        hint = "If several cards share this ERPNext account, enter each one, separated by commas.",
                        error = errors[d.bankAccount],
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            autoCorrectEnabled = false,
                        ),
                    )
                    Text(
                        text = "Default mode of payment",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = c.ink,
                    )
                    Spacer(Modifier.height(6.dp))
                    FlowRow {
                        modes.forEach { m ->
                            LedgerChip(
                                label = m.name,
                                selected = d.modeOfPayment == m.name,
                                onClick = { patchDraft(d.bankAccount) { it.copy(modeOfPayment = m.name) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SetupDefaultsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onContinue: () -> Unit,
) {
    val c = LedgerTheme.colors
    val scope = rememberCoroutineScope()
    val client = container.client

    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var accounts by remember { mutableStateOf<List<ErpDocs.LedgerAccountInfo>>(emptyList()) }
    var drawings by remember { mutableStateOf<String?>(null) }
    var defaultExpense by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var reloadNonce by remember { mutableIntStateOf(0) }

    LaunchedEffect(client, reloadNonce) {
        if (client == null) return@LaunchedEffect
        loading = true
        loadError = false
        try {
            val company = container.repo.getSetting(AppContainer.COMPANY_KEY)
            if (company.isNullOrBlank()) {
                loadError = true
                return@LaunchedEffect
            }
            val loaded = ErpDocs.listLedgerAccounts(client, company)
            val savedDrawings = container.repo.getSetting(AppContainer.DRAWINGS_KEY)
            val savedDefault = container.repo.getSetting(AppContainer.DEFAULT_EXPENSE_KEY)
            accounts = loaded
            if (drawings == null) {
                if (!savedDrawings.isNullOrBlank()) {
                    drawings = savedDrawings
                } else {
                    val likely = loaded.filter {
                        it.name.contains("drawing", ignoreCase = true) ||
                            it.name.contains("personal", ignoreCase = true) ||
                            it.name.contains("owner", ignoreCase = true) ||
                            it.name.contains("capital", ignoreCase = true) ||
                            it.name.contains("director", ignoreCase = true) ||
                            it.accountType == "Equity"
                    }
                    if (likely.size == 1) drawings = likely[0].name
                }
            }
            if (defaultExpense == null && !savedDefault.isNullOrBlank()) {
                defaultExpense = savedDefault
            }
        } catch (_: Throwable) {
            loadError = true
        } finally {
            loading = false
        }
    }

    val equityLike = remember(accounts) {
        accounts.filter {
            it.name.contains("drawing", ignoreCase = true) ||
                it.name.contains("personal", ignoreCase = true) ||
                it.name.contains("owner", ignoreCase = true) ||
                it.name.contains("capital", ignoreCase = true) ||
                it.name.contains("director", ignoreCase = true) ||
                it.accountType == "Equity"
        }
    }
    val expenseLike = remember(accounts) {
        accounts.filter {
            it.accountType == null ||
                it.accountType.contains("Expense", ignoreCase = true) ||
                it.name.contains("expense", ignoreCase = true) ||
                it.name.contains("charges", ignoreCase = true) ||
                it.name.contains("misc", ignoreCase = true)
        }
    }

    fun persistAndNext(saveChosen: Boolean) {
        scope.launch {
            saving = true
            saveFailed = false
            try {
                if (saveChosen) {
                    if (!drawings.isNullOrBlank()) {
                        container.repo.setSetting(AppContainer.DRAWINGS_KEY, drawings!!)
                    } else {
                        container.repo.deleteSetting(AppContainer.DRAWINGS_KEY)
                    }
                    if (!defaultExpense.isNullOrBlank()) {
                        container.repo.setSetting(AppContainer.DEFAULT_EXPENSE_KEY, defaultExpense!!)
                    } else {
                        container.repo.deleteSetting(AppContainer.DEFAULT_EXPENSE_KEY)
                    }
                }
                onContinue()
            } catch (_: Throwable) {
                saveFailed = true
            } finally {
                saving = false
            }
        }
    }

    LedgerScreen(
        top = { StepHeader(step = 3, total = 4, label = "Defaults", onBack = onBack) },
        title = "Account defaults",
        subtitle = "Optional, used when you mark a payment as Personal.",
        footer = {
            androidx.compose.foundation.layout.Column {
                LedgerButton(
                    label = "Continue",
                    enabled = !saving,
                    loading = saving,
                    onClick = { persistAndNext(saveChosen = true) },
                )
                Spacer(Modifier.height(6.dp))
                LedgerButton(
                    label = "Skip for now",
                    variant = ButtonVariant.QUIET,
                    enabled = !saving,
                    onClick = { persistAndNext(saveChosen = false) },
                )
            }
        },
    ) {
        if (saveFailed) {
            LedgerBanner(
                tone = BannerTone.ERROR,
                message = "Could not save your defaults on this phone. Try again.",
            )
        }
        if (loadError) {
            LedgerBanner(
                tone = BannerTone.ERROR,
                title = "Could not load accounts",
                message = "Check your internet connection and that your ERPNext user can read Account records.",
                actionLabel = "Try again",
                onAction = { reloadNonce++ },
            )
        }
        if (loading && !loadError) {
            LedgerBanner(message = "Loading accounts...")
        }

        if (!loading && !loadError) {
            Text(
                text = "Personal / Drawings account",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.ink,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "When you mark money as Personal, AutoLedger records it against this account.",
                fontSize = 13.sp,
                color = c.muted,
            )
            Spacer(Modifier.height(8.dp))
            val listForDrawings = if (equityLike.isNotEmpty()) equityLike else accounts
            if (listForDrawings.isEmpty()) {
                LedgerBanner(tone = BannerTone.WARN, message = "No equity or drawings accounts found in ERPNext.")
            } else {
                listForDrawings.take(40).forEach { acc ->
                    LedgerChoiceRow(
                        title = acc.name,
                        subtitle = acc.accountType,
                        selected = drawings == acc.name,
                        onClick = {
                            drawings = if (drawings == acc.name) null else acc.name
                        },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = "Default expense account (optional)",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.ink,
            )
            Spacer(Modifier.height(8.dp))
            (expenseLike.ifEmpty { accounts }).take(40).forEach { acc ->
                LedgerChoiceRow(
                    title = acc.name,
                    subtitle = acc.accountType,
                    selected = defaultExpense == acc.name,
                    onClick = {
                        defaultExpense = if (defaultExpense == acc.name) null else acc.name
                    },
                )
            }
        }
    }
}

@Composable
fun SetupPermissionsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val bootState by container.state.collectAsState()

    var status by remember { mutableStateOf(container.sms.permissionStatus()) }
    var days by remember { mutableIntStateOf(IngestPipeline.DEFAULT_BACKFILL_DAYS) }
    var backfillResult by remember { mutableStateOf<BackfillResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var askedAlready by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        days = IngestPipeline.getBackfillDays(container.repo)
        status = container.sms.permissionStatus()
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        status = container.sms.permissionStatus()
        askedAlready = true
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                status = container.sms.permissionStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val smsOk = status.receiveSms && status.readSms
    val canFinish = !busy && bootState.signedIn

    LedgerScreen(
        top = { StepHeader(step = 4, total = 4, label = "SMS access", onBack = onBack) },
        title = "Let AutoLedger read bank SMS",
        subtitle = "Only messages from the bank senders you chose are read. Nothing leaves your phone until you answer.",
        footer = {
            LedgerButton(
                label = if (smsOk) "Scan messages & finish" else "Finish without SMS access",
                enabled = canFinish,
                loading = busy,
                onClick = {
                    scope.launch {
                        busy = true
                        errorMsg = null
                        try {
                            container.repo.setSetting(IngestPipeline.BACKFILL_DAYS_KEY, days.toString())
                            IngestPipeline.syncSenderPatterns(container.repo, container.sms)
                            if (status.readSms) {
                                val res = IngestPipeline.ensureBackfill(
                                    repo = container.repo,
                                    sms = container.sms,
                                    fullWindow = true,
                                )
                                backfillResult = res
                            }
                            container.completeSetup()
                            onDone()
                        } catch (_: Throwable) {
                            errorMsg = "Could not finish setup on this phone. Try again."
                        } finally {
                            busy = false
                        }
                    }
                },
            )
        },
    ) {
        if (errorMsg != null) {
            LedgerBanner(tone = BannerTone.ERROR, message = errorMsg!!)
        }
        if (backfillResult != null) {
            LedgerBanner(
                tone = BannerTone.OK,
                message = SetupLogic.foundMessage(backfillResult!!),
            )
        }

        LedgerCard {
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
        }

        LedgerCard {
            Text(
                text = "Permissions",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = LedgerTheme.colors.ink,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = buildString {
                    append("Read SMS: ${if (status.readSms) "Allowed" else "Not allowed"}\n")
                    append("Receive SMS: ${if (status.receiveSms) "Allowed" else "Not allowed"}\n")
                    append("Notifications: ${if (status.notifications) "Allowed" else "Off"}")
                },
                fontSize = 13.5.sp,
                lineHeight = 20.sp,
                color = LedgerTheme.colors.muted,
            )
            if (!status.allGranted) {
                Spacer(Modifier.height(12.dp))
                LedgerButton(
                    label = if (askedAlready && !smsOk) "Ask for SMS access again" else "Allow SMS & notifications",
                    variant = ButtonVariant.SECONDARY,
                    onClick = { launcher.launch(Perms.wanted()) },
                )
            } else {
                Spacer(Modifier.height(8.dp))
                LedgerBanner(
                    tone = BannerTone.OK,
                    compact = true,
                    message = "All requested permissions are granted.",
                )
            }
        }
    }
}
