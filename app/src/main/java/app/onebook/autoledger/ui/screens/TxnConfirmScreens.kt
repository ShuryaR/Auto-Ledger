package app.onebook.autoledger.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.onebook.autoledger.core.AccountMapping
import app.onebook.autoledger.core.ActionChoice
import app.onebook.autoledger.core.ActionType
import app.onebook.autoledger.core.ClassifyEngine
import app.onebook.autoledger.core.PendingTxn
import app.onebook.autoledger.core.Rule
import app.onebook.autoledger.core.SuggestEngine
import app.onebook.autoledger.core.TxnState
import app.onebook.autoledger.erp.ErpDocs
import app.onebook.autoledger.inbox.Actions
import app.onebook.autoledger.inbox.AnswerSubmitter
import app.onebook.autoledger.inbox.BulkApply
import app.onebook.autoledger.inbox.BulkResult
import app.onebook.autoledger.inbox.Counterpart
import app.onebook.autoledger.inbox.CounterpartLink
import app.onebook.autoledger.inbox.Describe
import app.onebook.autoledger.inbox.FlowDraft
import app.onebook.autoledger.inbox.FlowLogic
import app.onebook.autoledger.inbox.FlowParty
import app.onebook.autoledger.inbox.FlowSettings
import app.onebook.autoledger.inbox.Formatters
import app.onebook.autoledger.inbox.InboxModelBuilder
import app.onebook.autoledger.inbox.Outcome
import app.onebook.autoledger.inbox.Outcomes
import app.onebook.autoledger.inbox.PlanAndChoice
import app.onebook.autoledger.inbox.PlanExtras
import app.onebook.autoledger.inbox.SuggestionsUi
import app.onebook.autoledger.ingest.AutoApply
import app.onebook.autoledger.ingest.DupCheck
import app.onebook.autoledger.ingest.DupFlag
import app.onebook.autoledger.state.AppContainer
import app.onebook.autoledger.ui.components.BackLink
import app.onebook.autoledger.ui.components.BannerTone
import app.onebook.autoledger.ui.components.ButtonVariant
import app.onebook.autoledger.ui.components.DetailLine as UiDetailLine
import app.onebook.autoledger.ui.components.ExternalOpenButton
import app.onebook.autoledger.ui.components.LedgerBanner
import app.onebook.autoledger.ui.components.LedgerButton
import app.onebook.autoledger.ui.components.LedgerCard
import app.onebook.autoledger.ui.components.LedgerChoiceRow
import app.onebook.autoledger.ui.components.LedgerScreen
import app.onebook.autoledger.ui.components.LedgerSearchField
import app.onebook.autoledger.ui.components.LedgerToggleRow
import app.onebook.autoledger.ui.components.openExternalUrl
import app.onebook.autoledger.ui.theme.LedgerTheme
import kotlinx.coroutines.launch

@Composable
fun TxnConfirmScreen(
    container: AppContainer,
    txnId: String,
    onBack: () -> Unit,
    onBackToInbox: () -> Unit,
    onSignInAgain: () -> Unit,
    onResult: (offline: Boolean, docs: List<Pair<String, String>>) -> Unit,
) {
    val c = LedgerTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bootState by container.state.collectAsState()
    val flowState by container.flowStore.state.collectAsState()
    val draft = flowState.drafts[txnId] ?: FlowDraft()

    var loading by remember(txnId) { mutableStateOf(true) }
    var txn by remember(txnId) { mutableStateOf<PendingTxn?>(null) }
    var settings by remember(txnId) { mutableStateOf<FlowSettings?>(null) }
    var dupFlag by remember(txnId) { mutableStateOf<DupFlag?>(null) }
    var checkedFrom by remember(txnId) { mutableStateOf<String?>(null) }
    var counterLink by remember(txnId) { mutableStateOf<CounterpartLink>(CounterpartLink.None) }
    var planResult by remember(txnId) { mutableStateOf<PlanAndChoice?>(null) }
    var planError by remember(txnId) { mutableStateOf<String?>(null) }

    var rememberAnswer by remember(txnId) {
        mutableStateOf(draft.action?.let { Actions.rememberDefault(it) } ?: false)
    }
    var sending by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<Outcome.Error?>(null) }

    LaunchedEffect(txnId, draft) {
        loading = true
        planError = null
        try {
            val loaded = container.repo.getPending(txnId)
            txn = loaded
            val company = container.repo.getSetting(AppContainer.COMPANY_KEY).orEmpty()
            val drawings = container.repo.getSetting(AppContainer.DRAWINGS_KEY).orEmpty()
            val today = Formatters.istToday(System.currentTimeMillis())
            val fs = FlowSettings(company = company, drawingsAccount = drawings, today = today)
            settings = fs
            dupFlag = DupFlag.parse(container.repo.getSetting(DupCheck.dupKey(txnId)))
            checkedFrom = container.repo.getSetting(DupCheck.CHECKED_FROM_KEY)

            if (loaded != null && loaded.state == TxnState.NEW) {
                val mappings = container.repo.listMappings()
                val initial = FlowLogic.planFor(loaded, draft, fs)
                var link: CounterpartLink = CounterpartLink.None
                if (draft.action == ActionType.TRANSFER) {
                    val counterMapping = Counterpart.counterMappingOf(draft, loaded.direction, mappings)
                    link = Counterpart.findCounterpartLeg(loaded, counterMapping, initial.plan, container.repo, mappings)
                }
                counterLink = link
                val refOverride = (link as? CounterpartLink.Linked)?.reference
                planResult = if (refOverride != null) {
                    FlowLogic.planFor(loaded, draft, fs, PlanExtras(referenceOverride = refOverride))
                } else {
                    initial
                }
            }
        } catch (e: Throwable) {
            planError = Outcomes.planErrorMessage(e)
        } finally {
            loading = false
        }
    }

    if (loading) {
        LedgerScreen(top = { BackLink(onPress = onBack) }, title = "Review") {
            LedgerBanner(message = "Preparing entry...")
        }
        return
    }

    val current = txn
    if (current == null || current.state != TxnState.NEW) {
        LedgerScreen(top = { BackLink(onPress = onBackToInbox) }, title = "Unavailable") {
            LedgerBanner(tone = BannerTone.WARN, message = Outcomes.GONE_MESSAGE)
            LedgerButton(label = "Back to Inbox", onClick = onBackToInbox)
        }
        return
    }

    val siteUrl = bootState.session?.siteUrl.orEmpty()
    val pac = planResult
    val isIgnore = draft.action == ActionType.IGNORE

    LedgerScreen(
        top = { BackLink(onPress = onBack) },
        title = if (isIgnore) "Ignore this message?" else "Confirm entry",
        subtitle = "AutoLedger creates a draft in ERPNext for your accountant to review.",
        footer = {
            if (pac != null) {
                LedgerButton(
                    label = if (isIgnore) "Ignore message" else "Create draft in ERPNext",
                    enabled = !sending,
                    loading = sending,
                    onClick = {
                        val erp = container.erp
                        if (erp == null) {
                            onSignInAgain()
                            return@LedgerButton
                        }
                        scope.launch {
                            sending = true
                            submitError = null
                            container.flowStore.beginSend(txnId)
                            try {
                                when (
                                    val outcome = AnswerSubmitter.submitAnswer(
                                        repo = container.repo,
                                        erp = erp,
                                        txn = current,
                                        plan = pac.plan,
                                        choice = pac.choice,
                                        rememberChoice = rememberAnswer,
                                    )
                                ) {
                                    is Outcome.Created -> {
                                        container.flowStore.clear(txnId)
                                        container.bumpData()
                                        container.bumpHome()
                                        onResult(false, outcome.docs)
                                    }
                                    is Outcome.Offline -> {
                                        container.flowStore.clear(txnId)
                                        container.bumpData()
                                        onResult(true, emptyList())
                                    }
                                    Outcome.Login -> {
                                        container.flowStore.setFlash("Sign in again to send your saved answer.")
                                        onSignInAgain()
                                    }
                                    Outcome.Gone -> {
                                        container.flowStore.clear(txnId)
                                        container.flowStore.setFlash(Outcomes.GONE_MESSAGE)
                                        container.bumpData()
                                        onBackToInbox()
                                    }
                                    Outcome.Busy -> {
                                        submitError = Outcome.Error("This payment is already being sent. Please wait a moment.")
                                    }
                                    is Outcome.Error -> {
                                        submitError = outcome
                                        container.bumpData()
                                    }
                                }
                            } finally {
                                container.flowStore.endSend(txnId)
                                sending = false
                            }
                        }
                    },
                )
            }
        },
    ) {
        if (planError != null) {
            LedgerBanner(tone = BannerTone.ERROR, message = planError!!)
            return@LedgerScreen
        }

        val err = submitError
        if (err != null) {
            LedgerBanner(
                tone = BannerTone.ERROR,
                title = "Could not create entry",
                message = err.message,
                actionLabel = if (err.doctype != null && siteUrl.isNotBlank()) "Open in ERPNext" else null,
                onAction = if (err.doctype != null && siteUrl.isNotBlank()) {
                    { openExternalUrl(context, Outcomes.erpNewDocUrl(siteUrl, err.doctype)) }
                } else null,
            )
        }

        val dupWarning = Describe.duplicateWarning(dupFlag)
        if (dupWarning != null) {
            LedgerBanner(
                tone = BannerTone.WARN,
                title = dupWarning.title,
                message = dupWarning.message,
                actionLabel = if (siteUrl.isNotBlank()) "Open existing" else null,
                onAction = if (siteUrl.isNotBlank()) {
                    { openExternalUrl(context, Outcomes.erpDocUrl(siteUrl, dupWarning.doctype, dupWarning.name)) }
                } else null,
            )
        }

        val unchecked = Describe.uncheckedNote(current.occurredOn, checkedFrom)
        if (unchecked != null) {
            LedgerBanner(tone = BannerTone.INFO, compact = true, message = unchecked)
        }

        when (val link = counterLink) {
            is CounterpartLink.Linked -> {
                LedgerBanner(
                    tone = BannerTone.INFO,
                    compact = true,
                    message = "Matched with the other half of this transfer (${link.counterLabel}). Reusing reference ${link.reference} so only one transfer entry is needed.",
                )
            }
            is CounterpartLink.Several -> {
                LedgerBanner(
                    tone = BannerTone.WARN,
                    compact = true,
                    message = Counterpart.SEVERAL_TRANSFERS_WARNING,
                )
            }
            CounterpartLink.None -> Unit
        }

        if (current.currency != "INR" && draft.inrAmountMinor != null) {
            val fc = FlowLogic.foreignAmountCheck(current.amountMinor, current.currency, draft.inrAmountMinor)
            if (fc.warning != null) {
                LedgerBanner(tone = BannerTone.WARN, message = fc.warning)
            } else {
                LedgerBanner(tone = BannerTone.INFO, compact = true, message = fc.text)
            }
        }

        if (pac != null) {
            if (pac.plan.steps.isEmpty()) {
                LedgerCard {
                    Text(FlowLogic.IGNORE_NOTE, fontSize = 14.5.sp, lineHeight = 20.sp, color = c.ink)
                }
            } else {
                pac.plan.steps.forEach { step ->
                    val rows = Describe.describeStep(step, draft.newParty?.labels.orEmpty())
                    LedgerCard {
                        Text(
                            text = Describe.stepTitle(step),
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = c.ink,
                        )
                        Spacer(Modifier.height(8.dp))
                        rows.forEach { r ->
                            UiDetailLine(label = r.label, value = r.value, mono = r.mono, strong = r.strong)
                        }
                    }
                }
            }
        }

        val act = draft.action
        if (act != null && Actions.rememberShown(act)) {
            LedgerCard {
                LedgerToggleRow(
                    title = "Remember this answer",
                    subtitle = "Suggest this answer automatically next time.",
                    checked = rememberAnswer,
                    onCheckedChange = { rememberAnswer = it },
                )
            }
        }
    }
}

@Composable
fun TxnResultScreen(
    container: AppContainer,
    offline: Boolean,
    docs: List<Pair<String, String>>,
    onBackToInbox: () -> Unit,
    onOpenActivity: () -> Unit,
) {
    val context = LocalContext.current
    val bootState by container.state.collectAsState()
    val siteUrl = bootState.session?.siteUrl.orEmpty()

    val title = when {
        offline -> "Saved to send later"
        docs.isEmpty() -> "Message ignored"
        else -> "Draft created"
    }
    val subtitle = when {
        offline -> Outcomes.OFFLINE_MESSAGE
        docs.isEmpty() -> FlowLogic.IGNORE_NOTE
        else -> "Waiting in ERPNext for your accountant to review and submit."
    }

    LedgerScreen(
        title = title,
        subtitle = subtitle,
        footer = {
            Column {
                LedgerButton(label = "Back to Inbox", onClick = onBackToInbox)
                Spacer(Modifier.height(8.dp))
                LedgerButton(
                    label = "View in Activity",
                    variant = ButtonVariant.SECONDARY,
                    onClick = onOpenActivity,
                )
            }
        },
    ) {
        LedgerBanner(
            tone = if (offline) BannerTone.INFO else BannerTone.OK,
            message = if (offline) {
                "You can keep answering other messages while offline."
            } else if (docs.isEmpty()) {
                "Nothing was added to ERPNext."
            } else {
                "Created ${docs.size} draft ${if (docs.size == 1) "entry" else "entries"} in ERPNext."
            },
        )

        if (!offline && docs.isNotEmpty()) {
            docs.forEach { (doctype, name) ->
                LedgerCard {
                    Text(doctype, fontSize = 13.sp, color = LedgerTheme.colors.muted)
                    Spacer(Modifier.height(2.dp))
                    Text(name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = LedgerTheme.colors.ink)
                    if (siteUrl.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        ExternalOpenButton(
                            label = "Open in ERPNext",
                            onClick = { openExternalUrl(context, Outcomes.erpDocUrl(siteUrl, doctype, name)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TxnBulkScreen(
    container: AppContainer,
    groupKey: String,
    onBackToInbox: () -> Unit,
) {
    val c = LedgerTheme.colors
    val scope = rememberCoroutineScope()
    val client = container.client

    var loading by remember(groupKey) { mutableStateOf(true) }
    var items by remember(groupKey) { mutableStateOf<List<PendingTxn>>(emptyList()) }
    var mappings by remember(groupKey) { mutableStateOf<List<AccountMapping>>(emptyList()) }
    var rules by remember(groupKey) { mutableStateOf<List<Rule>>(emptyList()) }
    var company by remember(groupKey) { mutableStateOf("") }
    var drawings by remember(groupKey) { mutableStateOf("") }

    var selectedAction by remember(groupKey) { mutableStateOf<ActionType?>(null) }
    var selectedAccount by remember(groupKey) { mutableStateOf<String?>(null) }
    var selectedParty by remember(groupKey) { mutableStateOf<FlowParty?>(null) }
    var makeAuto by remember(groupKey) { mutableStateOf(false) }

    var ledgerAccounts by remember(groupKey) { mutableStateOf<List<ErpDocs.LedgerAccountInfo>>(emptyList()) }
    var partyHits by remember(groupKey) { mutableStateOf<List<ErpDocs.PartySearchHit>>(emptyList()) }
    var pickerSearch by remember(groupKey) { mutableStateOf("") }

    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<BulkResult?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(groupKey) {
        loading = true
        try {
            val pending = container.repo.listPending(listOf(TxnState.NEW))
            val queued = container.repo.listQueuedTxnIds().toHashSet()
            val r = container.repo.listRules()
            val m = container.repo.listMappings()
            val comp = container.repo.getSetting(AppContainer.COMPANY_KEY).orEmpty()
            val draw = container.repo.getSetting(AppContainer.DRAWINGS_KEY).orEmpty()

            val groups = ClassifyEngine.groupSimilar(pending.filter { it.id !in queued }, r)
            val grp = groups.firstOrNull { it.key == groupKey }
            items = grp?.items.orEmpty()
            rules = r
            mappings = m
            company = comp
            drawings = draw

            val sug = grp?.suggestion
            if (sug != null) {
                selectedAction = sug.type
                selectedAccount = sug.expenseAccount ?: sug.incomeAccount
                selectedParty = sug.party?.let { FlowParty(it) }
            }
        } finally {
            loading = false
        }
    }

    LaunchedEffect(selectedAction, client, company) {
        if (client == null || company.isBlank()) return@LaunchedEffect
        pickerSearch = ""
        when (selectedAction) {
            ActionType.EXPENSE -> {
                ledgerAccounts = try { ErpDocs.listLedgerAccounts(client, company, "Expense") } catch (_: Throwable) { emptyList() }
            }
            ActionType.OTHER_INCOME -> {
                ledgerAccounts = try { ErpDocs.listLedgerAccounts(client, company, "Income") } catch (_: Throwable) { emptyList() }
            }
            ActionType.SUPPLIER_PAYMENT -> {
                val seed = items.firstOrNull()?.let { SuggestionsUi.searchTerm(it.counterparty ?: it.merchant) }.orEmpty()
                pickerSearch = seed
                partyHits = try { ErpDocs.searchParties(client, app.onebook.autoledger.core.PartyDoctype.SUPPLIER, seed) } catch (_: Throwable) { emptyList() }
            }
            else -> Unit
        }
    }

    if (loading) {
        LedgerScreen(top = { BackLink(onPress = onBackToInbox) }, title = "Loading...") {}
        return
    }

    if (result != null) {
        val r = result!!
        LedgerScreen(
            title = InboxModelBuilder.bulkTitle(r),
            subtitle = InboxModelBuilder.bulkSummary(r),
            footer = { LedgerButton(label = "Back to Inbox", onClick = onBackToInbox) },
        ) {
            LedgerBanner(
                tone = if (r.failed.isEmpty()) BannerTone.OK else BannerTone.WARN,
                message = InboxModelBuilder.bulkSummary(r),
            )
            r.failed.forEach { f ->
                LedgerBanner(
                    tone = BannerTone.ERROR,
                    compact = true,
                    message = InboxModelBuilder.plainFailure(f.message),
                )
            }
        }
        return
    }

    if (items.isEmpty()) {
        LedgerScreen(top = { BackLink(onPress = onBackToInbox) }, title = "No payments left") {
            LedgerBanner(tone = BannerTone.INFO, message = "All payments in this group were already answered.")
            LedgerButton(label = "Back to Inbox", onClick = onBackToInbox)
        }
        return
    }

    val first = items[0]
    val allowed = InboxModelBuilder.bulkActions(items).filter {
        it.type != ActionType.PERSONAL || drawings.isNotBlank()
    }
    val currentChoice: ActionChoice? = when (selectedAction) {
        ActionType.EXPENSE -> selectedAccount?.let { ActionChoice.Expense(it, selectedParty?.ref) }
        ActionType.OTHER_INCOME -> selectedAccount?.let { ActionChoice.OtherIncome(it) }
        ActionType.SUPPLIER_PAYMENT -> selectedParty?.let { ActionChoice.SupplierPayment(it.ref) }
        ActionType.PERSONAL -> ActionChoice.Personal
        ActionType.IGNORE -> ActionChoice.Ignore
        else -> null
    }
    val rangeText = InboxModelBuilder.amountRange(items)
    val autoRuleCandidate = currentChoice?.let {
        InboxModelBuilder.automaticRule(rules, groupKey, it, items)
    }

    LedgerScreen(
        top = { BackLink(label = "Inbox", onPress = onBackToInbox) },
        title = InboxModelBuilder.groupTitle(items),
        subtitle = listOfNotNull(rangeText, "Applies the same answer to ${items.size} payments").joinToString(" \u00B7 "),
        footer = {
            LedgerButton(
                label = "Apply to ${items.size} payments",
                enabled = currentChoice != null && !busy,
                loading = busy,
                onClick = {
                    val choice = currentChoice ?: return@LedgerButton
                    val erp = container.erp ?: return@LedgerButton
                    scope.launch {
                        busy = true
                        errorMsg = null
                        try {
                            val planFn = InboxModelBuilder.planChoice(
                                mappings = mappings,
                                company = company,
                                drawingsAccount = drawings,
                                today = Formatters.istToday(System.currentTimeMillis()),
                            )
                            val res = BulkApply.applyToGroup(
                                repo = container.repo,
                                erp = erp,
                                plan = planFn,
                                items = items,
                                choice = choice,
                                concurrency = 3,
                                remember = true,
                            )
                            if (makeAuto && res.done.isNotEmpty()) {
                                val doneTxns = items.filter { it.id in res.done }
                                val latestRules = container.repo.listRules()
                                InboxModelBuilder.automaticRule(latestRules, groupKey, choice, doneTxns)?.let { rule ->
                                    AutoApply.switchAutomatic(container.repo, rule, true, System.currentTimeMillis())
                                }
                            }
                            container.bumpData()
                            container.bumpHome()
                            result = res
                        } catch (_: Throwable) {
                            errorMsg = "Could not apply answers right now."
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

        Text("Choose answer", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.muted)
        Spacer(Modifier.height(6.dp))
        allowed.forEach { act ->
            LedgerChoiceRow(
                title = act.label,
                subtitle = act.hint,
                selected = selectedAction == act.type,
                onClick = {
                    selectedAction = act.type
                    if (act.type != ActionType.EXPENSE && act.type != ActionType.OTHER_INCOME) selectedAccount = null
                    if (act.type != ActionType.SUPPLIER_PAYMENT) selectedParty = null
                },
            )
        }

        if (selectedAction == ActionType.EXPENSE || selectedAction == ActionType.OTHER_INCOME) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (selectedAction == ActionType.EXPENSE) "Expense account" else "Income account",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.muted,
            )
            Spacer(Modifier.height(6.dp))
            LedgerSearchField(
                value = pickerSearch,
                onValueChange = { pickerSearch = it },
                placeholder = "Filter accounts",
            )
            Spacer(Modifier.height(8.dp))
            val q = pickerSearch.trim().lowercase()
            ledgerAccounts.filter { q.isEmpty() || it.name.lowercase().contains(q) }.take(30).forEach { acc ->
                LedgerChoiceRow(
                    title = Formatters.shortAccount(acc.name),
                    subtitle = if (Formatters.shortAccount(acc.name) != acc.name) acc.name else null,
                    selected = selectedAccount == acc.name,
                    onClick = { selectedAccount = acc.name },
                )
            }
        } else if (selectedAction == ActionType.SUPPLIER_PAYMENT) {
            Spacer(Modifier.height(10.dp))
            Text("Supplier", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.muted)
            Spacer(Modifier.height(6.dp))
            partyHits.forEach { hit ->
                LedgerChoiceRow(
                    title = hit.displayName,
                    selected = selectedParty?.ref == hit.ref,
                    onClick = { selectedParty = FlowParty(hit.ref, hit.displayName) },
                )
            }
        }

        if (autoRuleCandidate != null) {
            Spacer(Modifier.height(8.dp))
            LedgerCard {
                LedgerToggleRow(
                    title = "Always do this automatically",
                    subtitle = "Creates drafts without asking for future matching ${Formatters.kindLabel(first.kind).lowercase()} payments.",
                    checked = makeAuto,
                    onCheckedChange = { makeAuto = it },
                )
            }
        }
    }
}
