package app.onebook.autoledger.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.onebook.autoledger.BuildConfig
import app.onebook.autoledger.core.TxnState
import app.onebook.autoledger.state.AppContainer
import app.onebook.autoledger.ui.components.BackLink
import app.onebook.autoledger.ui.components.BannerTone
import app.onebook.autoledger.ui.components.ButtonVariant
import app.onebook.autoledger.ui.components.LedgerBanner
import app.onebook.autoledger.ui.components.LedgerButton
import app.onebook.autoledger.ui.components.LedgerScreen
import app.onebook.autoledger.ui.components.LedgerTextField
import kotlinx.coroutines.launch

@Composable
fun DebugReceiverScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenInbox: () -> Unit,
) {
    if (!BuildConfig.DEBUG) {
        LedgerScreen(
            top = { BackLink(onPress = onBack) },
            title = "Unavailable",
            subtitle = "Developer SMS simulation is only available in debug builds.",
        ) {}
        return
    }

    val scope = rememberCoroutineScope()
    var sender by remember { mutableStateOf("VM-IDFCFB") }
    var body by remember {
        mutableStateOf(
            "Your A/c XX3455 is debited by INR 450.00 on 15/11/26 via UPI to ZOMATO. Avl Bal INR 42,100.50. Ref: 632019485112.",
        )
    }
    var statusMsg by remember { mutableStateOf<Pair<BannerTone, String>?>(null) }
    var busy by remember { mutableStateOf(false) }

    LedgerScreen(
        top = { BackLink(onPress = onBack) },
        title = "SMS receiver test",
        subtitle = "Debug-only tool that feeds a simulated SMS directly through SmsIngestManager and runs a foreground ingest cycle.",
        footer = {
            LedgerButton(
                label = "Simulate SMS & run cycle",
                enabled = !busy && sender.isNotBlank() && body.isNotBlank(),
                loading = busy,
                onClick = {
                    scope.launch {
                        busy = true
                        statusMsg = null
                        try {
                            container.sms.simulateIncoming(sender.trim(), body.trim())
                            container.runCycle()
                            val n = container.repo.countPending(TxnState.NEW)
                            statusMsg = BannerTone.OK to "Simulated SMS queued and cycled. Pending in Inbox: $n."
                        } catch (t: Throwable) {
                            statusMsg = BannerTone.ERROR to (t.message ?: "Simulation failed.")
                        } finally {
                            busy = false
                        }
                    }
                },
            )
        },
    ) {
        if (statusMsg != null) {
            LedgerBanner(
                tone = statusMsg!!.first,
                message = statusMsg!!.second,
                actionLabel = "Open Inbox",
                onAction = onOpenInbox,
            )
        }
        LedgerTextField(
            label = "Sender address",
            value = sender,
            onValueChange = { sender = it },
        )
        LedgerTextField(
            label = "SMS body",
            value = body,
            onValueChange = { body = it },
            singleLine = false,
        )
        Spacer(Modifier.height(4.dp))
        LedgerButton(
            label = "Load sample credit SMS",
            variant = ButtonVariant.SECONDARY,
            onClick = {
                body = "Your A/c XX3455 is credited with INR 12,500.00 on 15/11/26 by NEFT from ACME FOODS. UTR IDFBH26319001122. Bal INR 54,600.50."
            },
        )
    }
}
