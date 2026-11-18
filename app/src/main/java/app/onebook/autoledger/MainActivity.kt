package app.onebook.autoledger

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import app.onebook.autoledger.ui.navigation.AutoLedgerApp
import app.onebook.autoledger.ui.theme.AutoLedgerTheme

class MainActivity : ComponentActivity() {
    private var deepLinkInboxSignal by mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)

        val app = application as AutoLedgerApplication
        setContent {
            AutoLedgerTheme {
                AutoLedgerApp(
                    container = app.container,
                    deepLinkToInboxSignal = deepLinkInboxSignal,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "autoledger" && data.host == "inbox") {
            deepLinkInboxSignal++
        }
    }
}
