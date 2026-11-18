package app.onebook.autoledger.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.onebook.autoledger.core.AccountMapping
import app.onebook.autoledger.core.ActionType
import app.onebook.autoledger.core.PendingTxn
import app.onebook.autoledger.core.Rule
import app.onebook.autoledger.core.SuggestEngine
import app.onebook.autoledger.core.TxnState
import app.onebook.autoledger.inbox.Actions
import app.onebook.autoledger.inbox.Describe
import app.onebook.autoledger.inbox.FlowDraft
import app.onebook.autoledger.inbox.FlowLogic
import app.onebook.autoledger.inbox.Formatters
import app.onebook.autoledger.inbox.Outcomes
import app.onebook.autoledger.inbox.Step
import app.onebook.autoledger.inbox.SuggestionsUi
import app.onebook.autoledger.ingest.AutoApply
import app.onebook.autoledger.ingest.DupCheck
import app.onebook.autoledger.ingest.DupFlag
import app.onebook.autoledger.ingest.IngestPipeline
import app.onebook.autoledger.state.AppContainer
import app.onebook.autoledger.ui.components.BackLink
import app.onebook.autoledger.ui.components.BannerTone
import app.onebook.autoledger.ui.components.ButtonVariant
import app.onebook.autoledger.ui.components.ExternalOpenButton
import app.onebook.autoledger.ui.components.LedgerBanner
import app.onebook.autoledger.ui.components.LedgerButton
import app.onebook.autoledger.ui.components.LedgerChoiceRow
import app.onebook.autoledger.ui.components.LedgerDetailCard
import app.onebook.autoledger.ui.components.LedgerScreen
import app.onebook.autoledger.ui.components.LedgerSuggestionCard
import app.onebook.autoledger.ui.components.openExternalUrl
import app.onebook.autoledger.ui.components.LedgerTextField
import app.onebook.autoledger.ui.theme.LedgerTheme

@Composable
fun TxnDetailScreen(
    container: AppContainer,
    txnId: String,
    onBackToInbox: () -> Unit,
    onOpenActivity: () -> Unit,
    onNavigateStep: (Step) -> Unit,
) {
    val c = LedgerTheme.colors
    val context = LocalContext.current
    val bootState by container.state.collectAsState()
    val flowState by container.flowStore.state.collectAsState()

    var loading by remember(txnId) { mutableStateOf(true) }
    var txn by remember(txnId) { mutableStateOf<PendingTxn?>(null) }
    var mappings by remember(txnId) { mutableStateOf<List<AccountMapping>>(emptyList()) }
    var rules by remember(txnId) { mutableStateOf<List<Rule>>(emptyList()) }
    var drawings by remember(txnId) { mutableStateOf("") }
    var dupFlag by remember(txnId) { mutableStateOf<DupFlag?>(null) }
    var heldOther by remember(txnId) { mutableStateOf<String?>(null) }

    LaunchedEffect(txnId, bootState.dataVersion) {
        loading = true
        try {
            val t = container.repo.getPending(txnId)
            val m = container.repo.listMappings()
            val r = container.repo.listRules()
            val d = container.repo.getSetting(AppContainer.DRAWINGS_KEY).orEmpty()
            val dup = DupFlag.parse(container.repo.getSetting(DupCheck.dupKey(txnId)))
            val held = container.repo.getSetting(AutoApply.autoHeldKey(txnId))

            txn = t
            mappings = m
            rules = r
            drawings = d
            dupFlag = dup
            heldOther = held

            if (t != null) {
                val existing = container.flowStore.draftOf(txnId)
                if (existing.mapping == null) {
                    val resolved = IngestPipeline.resolveMapping(m, t)
                    if (resolved != null) {
                        container.flowStore.start(txnId, existing.copy(mapping = resolved))
                    }
                }
            }
        } finally {
            loading = false
        }
    }

    if (loading) {
        LedgerScreen(top = { BackLink(onPress = onBackToInbox) }, title = "Loading...") {
            LedgerBanner(message = "Reading transaction details...")
        }
        return
    }

    val current = txn
    if (current == null) {
        LedgerScreen(top = { BackLink(onPress = onBackToInbox) }, title = "Not found") {
            LedgerBanner(tone = BannerTone.WARN, message = Outcomes.GONE_MESSAGE)
            LedgerButton(label = "Back to Inbox", onClick = onBackToInbox)
        }
        return
    }

    if (current.state != TxnState.NEW) {
        val stateLabel = when (current.state) {
            TxnState.IGNORED -> "This message was ignored."
            TxnState.SUBMITTED -> "This entry has already been submitted in ERPNext."
            else -> "This message has already been answered."
        }
        LedgerScreen(top = { BackLink(onPress = onBackToInbox) }, title = "Already answered") {
            LedgerBanner(tone = BannerTone.INFO, message = stateLabel)
            LedgerButton(label = "Back to Inbox", onClick = onBackToInbox)
            Spacer(Modifier.height(8.dp))
            LedgerButton(label = "Open Activity", onClick = onOpenActivity, variant = ButtonVariant.SECONDARY)
        }
        return
    }

    val draft = flowState.drafts[txnId] ?: FlowDraft()
    val today = remember(current.id) { Formatters.istToday(System.currentTimeMillis()) }
    val resolvedMapping = draft.mapping ?: IngestPipeline.resolveMapping(mappings, current)
    val accountLabel = Formatters.accountLabel(current, resolvedMapping)
    val siteUrl = bootState.session?.siteUrl.orEmpty()

    val suggestions = remember(current, rules) {
        SuggestionsUi.usableSuggestions(
            current,
            SuggestEngine.suggest(current, rules, emptyList(), emptyList()),
            drawings = drawings.isNotBlank(),
        )
    }

    fun pickAction(type: ActionType) {
        val nextDraft = FlowDraft(
            mapping = resolvedMapping,
            action = type,
            inrAmountMinor = draft.inrAmountMinor,
        )
        container.flowStore.start(txnId, nextDraft)
        onNavigateStep(Actions.nextStep(current, nextDraft))
    }

    LedgerScreen(
        top = { BackLink(label = "Inbox", onPress = onBackToInbox) },
        title = "What was this?",
    ) {
        LedgerDetailCard(txn = current, today = today, accountLabel = accountLabel)

        val dupWarning = Describe.duplicateWarning(dupFlag)
        if (dupWarning != null) {
            LedgerBanner(
                tone = BannerTone.WARN,
                title = dupWarning.title,
                message = dupWarning.message,
                actionLabel = if (siteUrl.isNotBlank()) "Open in ERPNext" else null,
                onAction = if (siteUrl.isNotBlank()) {
                    { openExternalUrl(context, Outcomes.erpDocUrl(siteUrl, dupWarning.doctype, dupWarning.name)) }
                } else null,
            )
        }

        if (!heldOther.isNullOrBlank()) {
            LedgerBanner(
                tone = BannerTone.WARN,
                compact = true,
                message = "Not done automatically because another payment looks similar. Check before confirming.",
            )
        }

        if (!current.error.isNullOrBlank()) {
            LedgerBanner(
                tone = BannerTone.ERROR,
                title = "Previous attempt failed",
                message = current.error,
            )
        }

        if (resolvedMapping == null) {
            Text(
                text = "Which of your accounts is this?",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.ink,
            )
            Spacer(Modifier.height(6.dp))
            if (mappings.isEmpty()) {
                LedgerBanner(
                    tone = BannerTone.WARN,
                    message = "No bank or card accounts are configured in Settings yet.",
                )
            } else {
                mappings.filter { it.kind == current.accountType }.ifEmpty { mappings }.forEach { m ->
                    LedgerChoiceRow(
                        title = m.bankAccount,
                        subtitle = m.last4.joinToString(", ").takeIf { it.isNotEmpty() }?.let { "Ending $it" },
                        selected = false,
                        onClick = {
                            container.flowStore.patch(txnId) { it.copy(mapping = m) }
                        },
                    )
                }
            }
            return@LedgerScreen
        }

        if (suggestions.isNotEmpty()) {
            Text(
                text = "Suggested",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.muted,
            )
            Spacer(Modifier.height(6.dp))
            suggestions.forEach { s ->
                val card = SuggestionsUi.suggestionCard(s)
                LedgerSuggestionCard(
                    title = card.title,
                    subtitle = card.subtitle,
                    onClick = {
                        val nextDraft = SuggestionsUi.draftFromSuggestion(s).copy(
                            mapping = resolvedMapping,
                            inrAmountMinor = draft.inrAmountMinor,
                        )
                        container.flowStore.start(txnId, nextDraft)
                        onNavigateStep(Actions.nextStep(current, nextDraft))
                    },
                )
            }
            Spacer(Modifier.height(6.dp))
        }

        Text(
            text = "All options",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = c.muted,
        )
        Spacer(Modifier.height(6.dp))
        Actions.actionsFor(current.direction, current.accountType).forEach { act ->
            val needsDrawings = act.type == ActionType.PERSONAL && drawings.isBlank()
            LedgerChoiceRow(
                title = act.label,
                subtitle = if (needsDrawings) "Set a Personal / Drawings account in Settings first." else act.hint,
                selected = draft.action == act.type,
                disabled = needsDrawings,
                onClick = { pickAction(act.type) },
            )
        }
    }
}

@Composable
fun TxnAmountScreen(
    container: AppContainer,
    txnId: String,
    onBack: () -> Unit,
    onBackToInbox: () -> Unit,
    onNavigateStep: (Step) -> Unit,
) {
    val flowState by container.flowStore.state.collectAsState()
    val draft = flowState.drafts[txnId] ?: FlowDraft()

    var txn by remember(txnId) { mutableStateOf<PendingTxn?>(null) }
    var loading by remember(txnId) { mutableStateOf(true) }
    var text by remember(txnId) {
        mutableStateOf(draft.inrAmountMinor?.let { FlowLogic.toPaise(it) }.orEmpty())
    }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(txnId) {
        txn = container.repo.getPending(txnId)
        loading = false
    }

    if (loading) {
        LedgerScreen(top = { BackLink(onPress = onBack) }, title = "Loading...") {}
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

    fun submitAmount() {
        val minor = FlowLogic.parseRupees(text)
        if (minor == null) {
            error = "Enter the rupee amount, for example 8,512.40."
            return
        }
        val nextDraft = draft.copy(inrAmountMinor = minor)
        container.flowStore.patch(txnId) { it.copy(inrAmountMinor = minor) }
        onNavigateStep(Actions.nextStep(current, nextDraft))
    }

    LedgerScreen(
        top = { BackLink(onPress = onBack) },
        title = "How much was it in rupees?",
        subtitle = "The bank message shows ${Formatters.plainAmount(current)} but not the rupee amount charged to your card.",
        footer = {
            LedgerButton(label = "Continue", onClick = { submitAmount() })
        },
    ) {
        LedgerTextField(
            label = "Amount in rupees",
            value = text,
            onValueChange = {
                text = it
                error = null
            },
            placeholder = "0.00",
            hint = "Use the amount on your card statement or the card app.",
            error = error,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submitAmount() }),
        )
        Text(
            text = "If you do not know it yet, leave this message in the Inbox and answer it when your statement arrives.",
            fontSize = 13.5.sp,
            lineHeight = 20.sp,
            color = LedgerTheme.colors.muted,
        )
    }
}
