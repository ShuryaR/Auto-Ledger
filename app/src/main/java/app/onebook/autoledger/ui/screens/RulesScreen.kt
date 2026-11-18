package app.onebook.autoledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.onebook.autoledger.core.ActionType
import app.onebook.autoledger.core.ClassifyEngine
import app.onebook.autoledger.core.Rule
import app.onebook.autoledger.inbox.Formatters
import app.onebook.autoledger.ingest.AutoApply
import app.onebook.autoledger.state.AppContainer
import app.onebook.autoledger.ui.components.BackLink
import app.onebook.autoledger.ui.components.ButtonVariant
import app.onebook.autoledger.ui.components.LedgerButton
import app.onebook.autoledger.ui.components.LedgerCard
import app.onebook.autoledger.ui.components.LedgerConfirmDialog
import app.onebook.autoledger.ui.components.LedgerEmptyState
import app.onebook.autoledger.ui.components.LedgerScreen
import app.onebook.autoledger.ui.components.LedgerToggleRow
import app.onebook.autoledger.ui.theme.LedgerTheme
import kotlinx.coroutines.launch

@Composable
fun RulesScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val c = LedgerTheme.colors
    val scope = rememberCoroutineScope()
    val bootState by container.state.collectAsState()

    var rules by remember { mutableStateOf<List<Rule>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var deleteCandidate by remember { mutableStateOf<Rule?>(null) }

    suspend fun loadRules() {
        rules = container.repo.listRules()
        loading = false
    }

    LaunchedEffect(bootState.dataVersion) {
        loadRules()
    }

    LedgerScreen(
        top = { BackLink(onPress = onBack) },
        title = "Automatic rules",
        subtitle = "AutoLedger learns from your answers. Turn on automatic mode only for predictable recurring payments.",
    ) {
        if (!loading && rules.isEmpty()) {
            LedgerEmptyState(
                title = "No saved rules yet",
                message = "When you answer transactions and choose Remember this answer, your choices appear here.",
            )
            return@LedgerScreen
        }

        rules.forEach { r ->
            val canAuto = r.action in ClassifyEngine.AUTO_ACTIONS && r.currency == "INR" && r.maxAmountMinor != null
            val capText = if (r.currency == "INR" && r.maxAmountMinor != null) {
                "Up to ${Formatters.formatInr(r.maxAmountMinor)}"
            } else {
                null
            }
            val answerSummary = listOfNotNull(
                actionName(r.action),
                r.account?.let { Formatters.shortAccount(it) },
                r.party?.name,
                capText,
            ).joinToString(" \u00B7 ")

            LedgerCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = ruleTitle(r),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = c.ink,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = answerSummary,
                            fontSize = 13.5.sp,
                            color = c.muted,
                        )
                    }
                }
                if (canAuto) {
                    Spacer(Modifier.height(8.dp))
                    LedgerToggleRow(
                        title = "Do this automatically",
                        subtitle = "Creates drafts automatically for matching future payments.",
                        checked = r.auto,
                        onCheckedChange = { on ->
                            scope.launch {
                                AutoApply.switchAutomatic(container.repo, r, on, System.currentTimeMillis())
                                container.bumpData()
                                loadRules()
                            }
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                LedgerButton(
                    label = "Forget this rule",
                    variant = ButtonVariant.QUIET,
                    onClick = { deleteCandidate = r },
                )
            }
        }
    }

    val toDelete = deleteCandidate
    LedgerConfirmDialog(
        visible = toDelete != null,
        title = "Forget this rule?",
        message = "AutoLedger will stop suggesting or automatically applying this answer for ${toDelete?.let(::ruleTitle).orEmpty()}. Existing entries are not affected.",
        confirmLabel = "Forget rule",
        destructive = true,
        onConfirm = {
            val target = toDelete ?: return@LedgerConfirmDialog
            deleteCandidate = null
            scope.launch {
                AutoApply.forgetRule(container.repo, target.matchKey)
                container.bumpData()
                loadRules()
            }
        },
        onDismiss = { deleteCandidate = null },
    )
}

private fun actionName(type: ActionType): String = when (type) {
    ActionType.CUSTOMER_ADVANCE -> "Customer advance"
    ActionType.SUPPLIER_PAYMENT -> "Supplier payment"
    ActionType.SALARY -> "Salary"
    ActionType.EXPENSE -> "Expense"
    ActionType.OTHER_INCOME -> "Other income"
    ActionType.PERSONAL -> "Personal"
    ActionType.IGNORE -> "Ignore"
    ActionType.AGAINST_INVOICE -> "Against invoice"
    ActionType.BILL_PAID_BY_CARD -> "Bill paid by card"
    ActionType.TRANSFER -> "Transfer"
}

private fun ruleTitle(r: Rule): String =
    r.party?.name?.takeIf { it.isNotBlank() }
        ?: r.matchKey.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

