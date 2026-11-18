package app.onebook.autoledger.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.onebook.autoledger.core.AccountMapping
import app.onebook.autoledger.core.AccountType
import app.onebook.autoledger.core.ActionType
import app.onebook.autoledger.core.Direction
import app.onebook.autoledger.core.OpenInvoice
import app.onebook.autoledger.core.OpenOrder
import app.onebook.autoledger.core.PartyDoctype
import app.onebook.autoledger.core.PendingTxn
import app.onebook.autoledger.core.TxnState
import app.onebook.autoledger.erp.ErpDocs
import app.onebook.autoledger.inbox.Actions
import app.onebook.autoledger.inbox.AllocationRow
import app.onebook.autoledger.inbox.FlowDraft
import app.onebook.autoledger.inbox.FlowLogic
import app.onebook.autoledger.inbox.FlowParty
import app.onebook.autoledger.inbox.Formatters
import app.onebook.autoledger.inbox.InvoicePick
import app.onebook.autoledger.inbox.Outcomes
import app.onebook.autoledger.inbox.Step
import app.onebook.autoledger.inbox.SuggestionsUi
import app.onebook.autoledger.inbox.TransferSource
import app.onebook.autoledger.state.AppContainer
import app.onebook.autoledger.ui.components.BackLink
import app.onebook.autoledger.ui.components.BannerTone
import app.onebook.autoledger.ui.components.ButtonVariant
import app.onebook.autoledger.ui.components.ChoiceRole
import app.onebook.autoledger.ui.components.LedgerBanner
import app.onebook.autoledger.ui.components.LedgerButton
import app.onebook.autoledger.ui.components.LedgerCard
import app.onebook.autoledger.ui.components.LedgerChoiceRow
import app.onebook.autoledger.ui.components.LedgerEmptyState
import app.onebook.autoledger.ui.components.LedgerScreen
import app.onebook.autoledger.ui.components.LedgerSearchField
import app.onebook.autoledger.ui.components.LedgerTextField
import app.onebook.autoledger.ui.theme.LedgerTheme
import kotlinx.coroutines.delay

@Composable
fun TxnPartyScreen(
    container: AppContainer,
    txnId: String,
    onBack: () -> Unit,
    onBackToInbox: () -> Unit,
    onNavigateStep: (Step) -> Unit,
) {
    val c = LedgerTheme.colors
    val client = container.client
    val flowState by container.flowStore.state.collectAsState()
    val draft = flowState.drafts[txnId] ?: FlowDraft()

    var txn by remember(txnId) { mutableStateOf<PendingTxn?>(null) }
    var loadingTxn by remember(txnId) { mutableStateOf(true) }

    var searchQuery by remember { mutableStateOf("") }
    var seeded by remember(txnId) { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var hits by remember { mutableStateOf<List<ErpDocs.PartySearchHit>>(emptyList()) }

    var createMode by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var requiredFields by remember { mutableStateOf<List<ErpDocs.RequiredFieldInfo>>(emptyList()) }
    var loadingFields by remember { mutableStateOf(false) }
    val extraValues = remember { mutableStateMapOf<String, String>() }
    var fieldErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    LaunchedEffect(txnId) {
        val loaded = container.repo.getPending(txnId)
        txn = loaded
        loadingTxn = false
        if (loaded != null && !seeded) {
            seeded = true
            val seed = SuggestionsUi.searchTerm(loaded.counterparty ?: loaded.merchant).orEmpty()
            searchQuery = seed
            newName = (loaded.counterparty ?: loaded.merchant).orEmpty()
        }
    }

    val current = txn
    val action = draft.action
    val doctype = remember(action, current?.direction) {
        if (action != null && current != null) Actions.partyDoctypeFor(action, current.direction) else null
    }

    LaunchedEffect(client, doctype, searchQuery, seeded) {
        if (client == null || doctype == null || !seeded) return@LaunchedEffect
        delay(220)
        searching = true
        searchError = null
        try {
            hits = ErpDocs.searchParties(client, doctype, searchQuery.trim())
        } catch (_: Throwable) {
            searchError = "Could not search ERPNext right now. Check your connection."
        } finally {
            searching = false
        }
    }

    LaunchedEffect(createMode, client, doctype) {
        if (!createMode || client == null || doctype == null) return@LaunchedEffect
        loadingFields = true
        try {
            requiredFields = ErpDocs.getRequiredFields(client, doctype.wire)
        } catch (_: Throwable) {
            requiredFields = emptyList()
        } finally {
            loadingFields = false
        }
    }

    if (loadingTxn) {
        LedgerScreen(top = { BackLink(onPress = onBack) }, title = "Loading...") {}
        return
    }
    if (current == null || current.state != TxnState.NEW || action == null || doctype == null) {
        LedgerScreen(top = { BackLink(onPress = onBackToInbox) }, title = "Unavailable") {
            LedgerBanner(tone = BannerTone.WARN, message = Outcomes.GONE_MESSAGE)
            LedgerButton(label = "Back to Inbox", onClick = onBackToInbox)
        }
        return
    }

    val labelWord = when (doctype) {
        PartyDoctype.CUSTOMER -> "customer"
        PartyDoctype.SUPPLIER -> "supplier"
        PartyDoctype.EMPLOYEE -> "employee"
    }
    val isExpenseSupplier = action == ActionType.EXPENSE

    LedgerScreen(
        top = { BackLink(onPress = onBack) },
        title = "Choose the $labelWord",
        subtitle = if (isExpenseSupplier) "Optional — you can also skip and pick only the expense account." else null,
        footer = if (createMode) {
            {
                LedgerButton(
                    label = "Use new $labelWord",
                    onClick = {
                        val errs = FlowLogic.validateNewParty(newName, extraValues.toMap(), requiredFields)
                        fieldErrors = errs
                        if (errs.isNotEmpty()) return@LedgerButton
                        val np = FlowLogic.buildNewParty(doctype, newName, extraValues.toMap(), requiredFields)
                        val fp = FlowParty(np.ref, np.ref.name)
                        val nextDraft = draft.copy(
                            party = fp,
                            newParty = np,
                            invoices = null,
                            orders = null,
                        )
                        container.flowStore.patch(txnId) { nextDraft }
                        onNavigateStep(Actions.nextStep(current, nextDraft))
                    },
                )
            }
        } else null,
    ) {
        if (isExpenseSupplier) {
            LedgerButton(
                label = "Skip supplier",
                variant = ButtonVariant.SECONDARY,
                onClick = {
                    val nextDraft = draft.copy(party = null, newParty = null)
                    container.flowStore.patch(txnId) { nextDraft }
                    onNavigateStep(Actions.nextStep(current, nextDraft))
                },
            )
            Spacer(Modifier.height(12.dp))
        }

        if (!createMode) {
            LedgerSearchField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = "Search ${doctype.wire} name",
            )
            Spacer(Modifier.height(10.dp))
            if (searching) {
                LedgerBanner(compact = true, message = "Searching ERPNext...")
            }
            if (searchError != null) {
                LedgerBanner(tone = BannerTone.ERROR, compact = true, message = searchError!!)
            }
            if (!searching && searchError == null && hits.isEmpty()) {
                LedgerEmptyState(
                    title = "No matching $labelWord",
                    message = "Try a different spelling or create a new $labelWord below.",
                )
            }
            hits.forEach { hit ->
                val selected = draft.party?.ref == hit.ref
                LedgerChoiceRow(
                    title = hit.displayName,
                    subtitle = if (hit.displayName != hit.ref.name) hit.ref.name else null,
                    selected = selected,
                    onClick = {
                        val fp = FlowParty(hit.ref, hit.displayName)
                        val nextDraft = draft.copy(
                            party = fp,
                            newParty = null,
                            invoices = null,
                            orders = null,
                        )
                        container.flowStore.patch(txnId) { nextDraft }
                        onNavigateStep(Actions.nextStep(current, nextDraft))
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            LedgerButton(
                label = "Create new $labelWord",
                variant = ButtonVariant.SECONDARY,
                onClick = {
                    if (newName.isBlank() && searchQuery.isNotBlank()) newName = searchQuery.trim()
                    createMode = true
                },
            )
        } else {
            LedgerCard {
                Text("New ${doctype.wire}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.ink)
                Spacer(Modifier.height(8.dp))
                LedgerTextField(
                    label = "Name",
                    value = newName,
                    onValueChange = {
                        newName = it
                        fieldErrors = fieldErrors - "name"
                    },
                    error = fieldErrors["name"],
                )
                if (loadingFields) {
                    LedgerBanner(compact = true, message = "Checking required fields...")
                } else {
                    FlowLogic.extraFields(requiredFields).forEach { f ->
                        LedgerTextField(
                            label = f.label,
                            value = extraValues[f.fieldname] ?: "",
                            onValueChange = { v ->
                                extraValues[f.fieldname] = v
                                fieldErrors = fieldErrors - f.fieldname
                            },
                            placeholder = if (f.fieldtype == "Date") "YYYY-MM-DD" else "",
                            error = fieldErrors[f.fieldname],
                        )
                    }
                }
                LedgerButton(
                    label = "Back to search",
                    variant = ButtonVariant.QUIET,
                    onClick = { createMode = false },
                )
            }
        }
    }
}

@Composable
fun TxnOrderScreen(
    container: AppContainer,
    txnId: String,
    onBack: () -> Unit,
    onBackToInbox: () -> Unit,
    onNavigateStep: (Step) -> Unit,
) {
    val client = container.client
    val flowState by container.flowStore.state.collectAsState()
    val draft = flowState.drafts[txnId] ?: FlowDraft()

    var txn by remember(txnId) { mutableStateOf<PendingTxn?>(null) }
    var orders by remember(txnId) { mutableStateOf<List<OpenOrder>>(emptyList()) }
    var loading by remember(txnId) { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    val selectedMap = remember { mutableStateMapOf<String, String>() }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var totalError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(txnId, client) {
        loading = true
        loadError = null
        try {
            val loadedTxn = container.repo.getPending(txnId)
            txn = loadedTxn
            val p = draft.party
            if (loadedTxn != null && p != null && client != null) {
                orders = ErpDocs.listOpenSalesOrders(client, p.name)
                draft.orders?.forEach { pick ->
                    selectedMap[pick.name] = FlowLogic.toPaise(pick.allocatedMinor)
                }
            }
        } catch (_: Throwable) {
            loadError = "Could not load open orders from ERPNext."
        } finally {
            loading = false
        }
    }

    val current = txn
    if (!loading && (current == null || current.state != TxnState.NEW || draft.party == null)) {
        LedgerScreen(top = { BackLink(onPress = onBackToInbox) }, title = "Unavailable") {
            LedgerBanner(tone = BannerTone.WARN, message = Outcomes.GONE_MESSAGE)
            LedgerButton(label = "Back to Inbox", onClick = onBackToInbox)
        }
        return
    }

    val effMinor = current?.let { FlowLogic.effectiveAmountMinor(it, draft.inrAmountMinor) } ?: 0L

    LedgerScreen(
        top = { BackLink(onPress = onBack) },
        title = "Is this for a sales order?",
        subtitle = "Customer: ${draft.party?.displayName ?: draft.party?.name.orEmpty()}",
        footer = {
            Column {
                LedgerButton(
                    label = "Continue",
                    enabled = !loading && loadError == null,
                    onClick = {
                        val rows = orders.filter { selectedMap.containsKey(it.name) }.map { o ->
                            val minor = FlowLogic.parseRupees(selectedMap[o.name].orEmpty()) ?: 0L
                            AllocationRow(o.name, o.remainingMinor, minor)
                        }
                        val (check, picked) = FlowLogic.chooseOrders(effMinor, rows)
                        errors = check.errors
                        totalError = check.total
                        if (check.ok && picked != null && current != null) {
                            val nextDraft = draft.copy(orders = picked)
                            container.flowStore.patch(txnId) { nextDraft }
                            onNavigateStep(Actions.nextStep(current, nextDraft))
                        }
                    },
                )
                Spacer(Modifier.height(6.dp))
                LedgerButton(
                    label = "Skip — not against an order",
                    variant = ButtonVariant.QUIET,
                    enabled = current != null,
                    onClick = {
                        val skipped = FlowLogic.skipOrders()
                        val nextDraft = draft.copy(orders = skipped)
                        container.flowStore.patch(txnId) { nextDraft }
                        if (current != null) onNavigateStep(Actions.nextStep(current, nextDraft))
                    },
                )
            }
        },
    ) {
        if (loading) {
            LedgerBanner(message = "Loading open sales orders...")
            return@LedgerScreen
        }
        if (loadError != null) {
            LedgerBanner(tone = BannerTone.ERROR, message = loadError!!)
            return@LedgerScreen
        }
        if (totalError != null) {
            LedgerBanner(tone = BannerTone.WARN, message = totalError!!)
        }
        if (orders.isEmpty()) {
            LedgerEmptyState(
                title = "No open sales orders",
                message = "Tap Skip below to record this as a general customer advance.",
            )
            return@LedgerScreen
        }

        orders.forEach { o ->
            val isSelected = selectedMap.containsKey(o.name)
            LedgerChoiceRow(
                role = ChoiceRole.CHECKBOX,
                title = o.name,
                subtitle = "${Formatters.formatInr(o.remainingMinor)} left to pay · Total ${Formatters.formatInr(o.totalMinor)}",
                selected = isSelected,
                onClick = {
                    errors = emptyMap()
                    totalError = null
                    if (isSelected) {
                        selectedMap.remove(o.name)
                    } else {
                        val others = selectedMap.values.mapNotNull { FlowLogic.parseRupees(it) }
                        val def = FlowLogic.defaultAllocation(effMinor, o.remainingMinor, others)
                        selectedMap[o.name] = FlowLogic.toPaise(def)
                    }
                },
            )
            if (isSelected) {
                LedgerTextField(
                    label = "Amount for ${o.name}",
                    value = selectedMap[o.name].orEmpty(),
                    onValueChange = { v ->
                        selectedMap[o.name] = v
                        errors = errors - o.name
                        totalError = null
                    },
                    error = errors[o.name],
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        }
    }
}

@Composable
fun TxnInvoicesScreen(
    container: AppContainer,
    txnId: String,
    onBack: () -> Unit,
    onBackToInbox: () -> Unit,
    onNavigateStep: (Step) -> Unit,
) {
    val client = container.client
    val flowState by container.flowStore.state.collectAsState()
    val draft = flowState.drafts[txnId] ?: FlowDraft()

    var txn by remember(txnId) { mutableStateOf<PendingTxn?>(null) }
    var invoices by remember(txnId) { mutableStateOf<List<OpenInvoice>>(emptyList()) }
    var loading by remember(txnId) { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    val selectedMap = remember { mutableStateMapOf<String, String>() }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var totalError by remember { mutableStateOf<String?>(null) }

    val singleOnly = draft.action == ActionType.BILL_PAID_BY_CARD

    LaunchedEffect(txnId, client) {
        loading = true
        loadError = null
        try {
            val loadedTxn = container.repo.getPending(txnId)
            txn = loadedTxn
            val p = draft.party
            if (loadedTxn != null && p != null && client != null) {
                invoices = ErpDocs.listOpenInvoices(client, p.doctype, p.name)
                draft.invoices?.forEach { pick ->
                    selectedMap[pick.name] = FlowLogic.toPaise(pick.allocatedMinor)
                }
            }
        } catch (_: Throwable) {
            loadError = "Could not load open invoices from ERPNext."
        } finally {
            loading = false
        }
    }

    val current = txn
    if (!loading && (current == null || current.state != TxnState.NEW || draft.party == null)) {
        LedgerScreen(top = { BackLink(onPress = onBackToInbox) }, title = "Unavailable") {
            LedgerBanner(tone = BannerTone.WARN, message = Outcomes.GONE_MESSAGE)
            LedgerButton(label = "Back to Inbox", onClick = onBackToInbox)
        }
        return
    }

    val effMinor = current?.let { FlowLogic.effectiveAmountMinor(it, draft.inrAmountMinor) } ?: 0L
    val docWord = if (draft.party?.doctype == PartyDoctype.CUSTOMER) "invoice" else "bill"

    LedgerScreen(
        top = { BackLink(onPress = onBack) },
        title = if (singleOnly) "Which $docWord was paid?" else "Which ${docWord}s does this pay?",
        subtitle = "${draft.party?.displayName ?: draft.party?.name.orEmpty()} · ${Formatters.formatInr(effMinor)} total",
        footer = {
            LedgerButton(
                label = "Continue",
                enabled = !loading && loadError == null && invoices.isNotEmpty(),
                onClick = {
                    val chosen = invoices.filter { selectedMap.containsKey(it.name) }
                    val rows = chosen.map { inv ->
                        AllocationRow(inv.name, inv.outstandingMinor, FlowLogic.parseRupees(selectedMap[inv.name].orEmpty()) ?: 0L)
                    }
                    val check = FlowLogic.validateAllocations(effMinor, rows)
                    errors = check.errors
                    totalError = check.total
                    if (check.ok && current != null) {
                        val picks = chosen.zip(rows).map { (inv, row) ->
                            InvoicePick(inv.doctype, inv.name, row.allocatedMinor)
                        }
                        val nextDraft = draft.copy(invoices = picks)
                        container.flowStore.patch(txnId) { nextDraft }
                        onNavigateStep(Actions.nextStep(current, nextDraft))
                    }
                },
            )
        },
    ) {
        if (loading) {
            LedgerBanner(message = "Loading open ${docWord}s...")
            return@LedgerScreen
        }
        if (loadError != null) {
            LedgerBanner(tone = BannerTone.ERROR, message = loadError!!)
            return@LedgerScreen
        }
        if (totalError != null) {
            LedgerBanner(tone = BannerTone.WARN, message = totalError!!)
        }
        if (invoices.isEmpty()) {
            LedgerEmptyState(
                title = "No open ${docWord}s",
                message = "Go back and choose Supplier payment or Customer advance instead.",
                actionLabel = "Go back",
                onAction = onBack,
            )
            return@LedgerScreen
        }

        invoices.forEach { inv ->
            val isSelected = selectedMap.containsKey(inv.name)
            val dueText = if (inv.dueDate.isNotBlank()) " · Due ${Formatters.longDate(inv.dueDate)}" else ""
            LedgerChoiceRow(
                role = if (singleOnly) ChoiceRole.RADIO else ChoiceRole.CHECKBOX,
                title = inv.name,
                subtitle = "Still due ${Formatters.formatInr(inv.outstandingMinor)}$dueText",
                selected = isSelected,
                onClick = {
                    errors = emptyMap()
                    totalError = null
                    if (singleOnly) {
                        selectedMap.clear()
                        val def = FlowLogic.defaultAllocation(effMinor, inv.outstandingMinor, emptyList())
                        selectedMap[inv.name] = FlowLogic.toPaise(def)
                    } else if (isSelected) {
                        selectedMap.remove(inv.name)
                    } else {
                        val others = selectedMap.values.mapNotNull { FlowLogic.parseRupees(it) }
                        val def = FlowLogic.defaultAllocation(effMinor, inv.outstandingMinor, others)
                        selectedMap[inv.name] = FlowLogic.toPaise(def)
                    }
                },
            )
            if (isSelected) {
                LedgerTextField(
                    label = "Amount for ${inv.name}",
                    value = selectedMap[inv.name].orEmpty(),
                    onValueChange = { v ->
                        selectedMap[inv.name] = v
                        errors = errors - inv.name
                        totalError = null
                    },
                    error = errors[inv.name],
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        }
    }
}

@Composable
fun TxnAccountScreen(
    container: AppContainer,
    txnId: String,
    onBack: () -> Unit,
    onBackToInbox: () -> Unit,
    onNavigateStep: (Step) -> Unit,
) {
    val client = container.client
    val flowState by container.flowStore.state.collectAsState()
    val draft = flowState.drafts[txnId] ?: FlowDraft()

    var txn by remember(txnId) { mutableStateOf<PendingTxn?>(null) }
    var loading by remember(txnId) { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }

    var ledgerAccounts by remember(txnId) { mutableStateOf<List<ErpDocs.LedgerAccountInfo>>(emptyList()) }
    var bankMappings by remember(txnId) { mutableStateOf<List<AccountMapping>>(emptyList()) }
    var erpBankAccounts by remember(txnId) { mutableStateOf<List<ErpDocs.BankAccountInfo>>(emptyList()) }

    val isTransfer = draft.action == ActionType.TRANSFER

    LaunchedEffect(txnId, client, draft.action) {
        loading = true
        loadError = null
        try {
            val loadedTxn = container.repo.getPending(txnId)
            txn = loadedTxn
            val company = container.repo.getSetting(AppContainer.COMPANY_KEY).orEmpty()
            if (loadedTxn != null && client != null && company.isNotBlank()) {
                if (isTransfer) {
                    bankMappings = container.repo.listMappings()
                    erpBankAccounts = ErpDocs.listBankAccounts(client, company)
                } else {
                    val rootType = if (draft.action == ActionType.EXPENSE) "Expense" else "Income"
                    ledgerAccounts = ErpDocs.listLedgerAccounts(client, company, rootType)
                }
            }
        } catch (_: Throwable) {
            loadError = "Could not load accounts from ERPNext."
        } finally {
            loading = false
        }
    }

    val current = txn
    if (!loading && (current == null || current.state != TxnState.NEW || draft.action == null)) {
        LedgerScreen(top = { BackLink(onPress = onBackToInbox) }, title = "Unavailable") {
            LedgerBanner(tone = BannerTone.WARN, message = Outcomes.GONE_MESSAGE)
            LedgerButton(label = "Back to Inbox", onClick = onBackToInbox)
        }
        return
    }

    val title = when {
        isTransfer && current?.direction == Direction.IN -> "Where did the money come from?"
        isTransfer -> "Which account did you transfer to?"
        draft.action == ActionType.OTHER_INCOME -> "Choose the income account"
        else -> "Choose the expense account"
    }

    LedgerScreen(
        top = { BackLink(onPress = onBack) },
        title = title,
    ) {
        if (loading) {
            LedgerBanner(message = "Loading accounts...")
            return@LedgerScreen
        }
        if (loadError != null) {
            LedgerBanner(tone = BannerTone.ERROR, message = loadError!!)
            return@LedgerScreen
        }

        val ownBank = draft.mapping?.bankAccount
        if (isTransfer && current != null) {
            val configuredOthers = bankMappings.filter { it.bankAccount != ownBank }
            val configuredNames = configuredOthers.mapTo(HashSet()) { it.bankAccount }
            val extraErp = erpBankAccounts.filter { it.name != ownBank && it.name !in configuredNames && it.glAccount.isNotBlank() }

            if (current.direction == Direction.OUT) {
                configuredOthers.forEach { m ->
                    LedgerChoiceRow(
                        title = Formatters.shortAccount(m.bankAccount),
                        subtitle = if (m.kind == AccountType.CARD) "Credit card" else "Bank account",
                        selected = draft.toAccount?.bankAccount == m.bankAccount,
                        onClick = {
                            val nextDraft = draft.copy(toAccount = m)
                            container.flowStore.patch(txnId) { nextDraft }
                            onNavigateStep(Actions.nextStep(current, nextDraft))
                        },
                    )
                }
                extraErp.forEach { b ->
                    val synthetic = AccountMapping(
                        bankAccount = b.name,
                        glAccount = b.glAccount,
                        kind = AccountType.BANK,
                        senderIds = emptyList(),
                        last4 = emptyList(),
                        modeOfPayment = draft.mapping?.modeOfPayment ?: "Wire Transfer",
                    )
                    LedgerChoiceRow(
                        title = Formatters.shortAccount(b.name),
                        subtitle = b.glAccount,
                        selected = draft.toAccount?.bankAccount == b.name,
                        onClick = {
                            val nextDraft = draft.copy(toAccount = synthetic)
                            container.flowStore.patch(txnId) { nextDraft }
                            onNavigateStep(Actions.nextStep(current, nextDraft))
                        },
                    )
                }
            } else {
                configuredOthers.forEach { m ->
                    LedgerChoiceRow(
                        title = Formatters.shortAccount(m.bankAccount),
                        subtitle = if (m.kind == AccountType.CARD) "Credit card" else "Bank account",
                        selected = draft.fromAccount?.bankAccount == m.bankAccount,
                        onClick = {
                            val src = TransferSource(m.bankAccount, m.glAccount, m.modeOfPayment)
                            val nextDraft = draft.copy(fromAccount = src)
                            container.flowStore.patch(txnId) { nextDraft }
                            onNavigateStep(Actions.nextStep(current, nextDraft))
                        },
                    )
                }
                extraErp.forEach { b ->
                    LedgerChoiceRow(
                        title = Formatters.shortAccount(b.name),
                        subtitle = b.glAccount,
                        selected = draft.fromAccount?.bankAccount == b.name,
                        onClick = {
                            val src = TransferSource(b.name, b.glAccount, draft.mapping?.modeOfPayment)
                            val nextDraft = draft.copy(fromAccount = src)
                            container.flowStore.patch(txnId) { nextDraft }
                            onNavigateStep(Actions.nextStep(current, nextDraft))
                        },
                    )
                }
            }
            if (configuredOthers.isEmpty() && extraErp.isEmpty()) {
                LedgerEmptyState(
                    title = "No other accounts found",
                    message = "Add another Bank Account in ERPNext to record transfers between accounts.",
                )
            }
        } else if (current != null) {
            LedgerSearchField(
                value = search,
                onValueChange = { search = it },
                placeholder = "Search accounts",
            )
            Spacer(Modifier.height(10.dp))
            val q = search.trim().lowercase()
            val filtered = ledgerAccounts.filter {
                q.isEmpty() || it.name.lowercase().contains(q)
            }
            if (filtered.isEmpty()) {
                LedgerEmptyState(
                    title = "No matching accounts",
                    message = "Try a different search term.",
                )
            } else {
                filtered.forEach { acc ->
                    LedgerChoiceRow(
                        title = Formatters.shortAccount(acc.name),
                        subtitle = if (Formatters.shortAccount(acc.name) != acc.name) acc.name else acc.accountType,
                        selected = draft.account == acc.name,
                        onClick = {
                            val nextDraft = draft.copy(account = acc.name)
                            container.flowStore.patch(txnId) { nextDraft }
                            onNavigateStep(Actions.nextStep(current, nextDraft))
                        },
                    )
                }
            }
        }
    }
}
