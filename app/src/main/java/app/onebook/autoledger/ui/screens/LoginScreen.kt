package app.onebook.autoledger.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.onebook.autoledger.BuildConfig
import app.onebook.autoledger.data.Session
import app.onebook.autoledger.erp.ErpAuth
import app.onebook.autoledger.state.AppContainer
import app.onebook.autoledger.state.CheckSiteUrlResult
import app.onebook.autoledger.state.LoginErrors
import app.onebook.autoledger.state.LoginProblem
import app.onebook.autoledger.state.SiteSwitchPreview
import app.onebook.autoledger.ui.components.BackLink
import app.onebook.autoledger.ui.components.BannerTone
import app.onebook.autoledger.ui.components.ButtonVariant
import app.onebook.autoledger.ui.components.LedgerBanner
import app.onebook.autoledger.ui.components.LedgerButton
import app.onebook.autoledger.ui.components.LedgerConfirmDialog
import app.onebook.autoledger.ui.components.LedgerScreen
import app.onebook.autoledger.ui.components.LedgerTextField
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    container: AppContainer,
    initialSiteUrl: String = "",
    initialUser: String = "",
    canGoBack: Boolean = false,
    onBack: () -> Unit = {},
    onSignedIn: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val allowHttp = BuildConfig.DEBUG

    var site by remember(initialSiteUrl) { mutableStateOf(initialSiteUrl) }
    var user by remember(initialUser) { mutableStateOf(initialUser) }
    var password by remember { mutableStateOf("") }
    var useKeys by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf("") }
    var apiSecret by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<LoginProblem?>(null) }

    var rotateDeferred by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    var switchDialog by remember { mutableStateOf<Pair<SiteSwitchPreview, CompletableDeferred<Boolean>>?>(null) }

    val urlCheck = remember(site) {
        if (site.trim().isEmpty()) null else LoginErrors.checkSiteUrl(site, allowHttp)
    }

    fun clearError() {
        problem = null
    }

    suspend fun confirmSiteSwitch(preview: SiteSwitchPreview): Boolean {
        if (!preview.switching) return true
        val deferred = CompletableDeferred<Boolean>()
        switchDialog = preview to deferred
        return try {
            deferred.await()
        } finally {
            switchDialog = null
        }
    }

    suspend fun confirmRotate(): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        rotateDeferred = deferred
        return try {
            deferred.await()
        } finally {
            rotateDeferred = null
        }
    }

    fun submit() {
        val cleanUrl = when (val check = LoginErrors.checkSiteUrl(site, allowHttp)) {
            is CheckSiteUrlResult.Invalid -> {
                problem = LoginProblem(check.message)
                return
            }
            is CheckSiteUrlResult.Ok -> check.url
        }
        if (useKeys) {
            if (apiKey.trim().isEmpty() || apiSecret.trim().isEmpty()) {
                problem = LoginProblem("Enter both your API key and API secret.")
                return
            }
        } else {
            if (user.trim().isEmpty() || password.isEmpty()) {
                problem = LoginProblem("Enter your email or username and password.")
                return
            }
        }

        scope.launch {
            busy = true
            problem = null
            try {
                val preview = container.previewSiteSwitch(cleanUrl)
                if (!confirmSiteSwitch(preview)) return@launch

                val session: Session = if (useKeys) {
                    ErpAuth.loginWithKeys(
                        siteUrl = cleanUrl,
                        apiKey = apiKey.trim(),
                        apiSecret = apiSecret.trim(),
                        allowHttp = allowHttp,
                    )
                } else {
                    ErpAuth.loginWithPassword(
                        siteUrl = cleanUrl,
                        username = user.trim(),
                        password = password,
                        allowHttp = allowHttp,
                        confirmRotate = { confirmRotate() },
                    )
                }
                container.signIn(session)
                onSignedIn()
            } catch (e: Throwable) {
                problem = LoginErrors.describeLoginError(e)
            } finally {
                busy = false
            }
        }
    }

    LedgerScreen(
        top = if (canGoBack) {
            { BackLink(onPress = onBack) }
        } else null,
        title = "Sign in to ERPNext",
        subtitle = "AutoLedger creates draft entries only. Your login stays on this phone.",
        footer = {
            LedgerButton(
                label = "Sign in",
                onClick = { submit() },
                enabled = !busy,
                loading = busy,
            )
        },
    ) {
        if (problem != null) {
            LedgerBanner(
                tone = BannerTone.ERROR,
                message = problem!!.message,
                actionLabel = if (problem!!.offerKeys && !useKeys) "Use API key & secret" else null,
                onAction = if (problem!!.offerKeys && !useKeys) {
                    {
                        useKeys = true
                        problem = null
                    }
                } else null,
            )
        }

        if (urlCheck is CheckSiteUrlResult.Ok && urlCheck.url.startsWith("http://", ignoreCase = true)) {
            LedgerBanner(
                tone = BannerTone.WARN,
                message = "Using HTTP in debug build. Release builds require HTTPS.",
            )
        }

        LedgerTextField(
            label = "ERPNext site address",
            value = site,
            onValueChange = {
                site = it
                clearError()
            },
            placeholder = "https://erp.yourcompany.com",
            hint = "Example: https://erp.example.com",
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Next,
            ),
        )

        if (!useKeys) {
            LedgerTextField(
                label = "Email or username",
                value = user,
                onValueChange = {
                    user = it
                    clearError()
                },
                placeholder = "you@company.com",
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Next,
                ),
            )
            LedgerTextField(
                label = "Password",
                value = password,
                onValueChange = {
                    password = it
                    clearError()
                },
                secret = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (!busy) submit() }),
            )
        } else {
            LedgerTextField(
                label = "API key",
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    clearError()
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Next,
                ),
            )
            LedgerTextField(
                label = "API secret",
                value = apiSecret,
                onValueChange = {
                    apiSecret = it
                    clearError()
                },
                secret = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (!busy) submit() }),
            )
        }

        Spacer(Modifier.height(4.dp))
        LedgerButton(
            label = if (useKeys) "Sign in with password instead" else "Use API key & secret",
            onClick = {
                useKeys = !useKeys
                problem = null
            },
            variant = ButtonVariant.QUIET,
        )
    }

    LedgerConfirmDialog(
        visible = rotateDeferred != null,
        title = "Replace existing API key?",
        message = "This ERPNext user already has an API key. Signing in here generates a new one, which signs out any other app using the old key.",
        confirmLabel = "Replace key & sign in",
        cancelLabel = "Cancel",
        onConfirm = { rotateDeferred?.complete(true) },
        onDismiss = { rotateDeferred?.complete(false) },
    )

    val pendingSwitch = switchDialog
    if (pendingSwitch != null) {
        val (preview, deferred) = pendingSwitch
        val queueNote = if (preview.queuedActions > 0) {
            " ${preview.queuedActions} queued answer${if (preview.queuedActions == 1) "" else "s"} waiting to be sent will also be cleared."
        } else {
            ""
        }
        LedgerConfirmDialog(
            visible = true,
            title = "Switch ERPNext site?",
            message = "Signing in to a different ERPNext site clears the transactions, rules and account settings saved on this phone for the old site.$queueNote",
            confirmLabel = "Switch site",
            cancelLabel = "Cancel",
            destructive = true,
            onConfirm = { deferred.complete(true) },
            onDismiss = { deferred.complete(false) },
        )
    }
}
