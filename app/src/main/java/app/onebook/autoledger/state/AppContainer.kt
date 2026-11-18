package app.onebook.autoledger.state

import android.content.Context
import app.onebook.autoledger.BuildConfig
import app.onebook.autoledger.core.OutboxStatus
import app.onebook.autoledger.data.AutoLedgerDatabase
import app.onebook.autoledger.data.Repo
import app.onebook.autoledger.data.Session
import app.onebook.autoledger.data.SessionStore
import app.onebook.autoledger.data.SessionSummary
import app.onebook.autoledger.erp.ErpAuth
import app.onebook.autoledger.erp.ErpClient
import app.onebook.autoledger.home.HomeLogic
import app.onebook.autoledger.inbox.FlowStore
import app.onebook.autoledger.inbox.InboxErp
import app.onebook.autoledger.inbox.makeInboxErp
import app.onebook.autoledger.ingest.AutoApply
import app.onebook.autoledger.ingest.CycleSummary
import app.onebook.autoledger.ingest.DupCheck
import app.onebook.autoledger.ingest.OutboxDrainer
import app.onebook.autoledger.ingest.TemplatesManager
import app.onebook.autoledger.sms.SmsIngestManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class GateTarget {
    LOGIN,
    SETUP,
    HOME,
}

data class SiteSwitchPreview(
    val switching: Boolean,
    val queuedActions: Int,
)

data class AppBootState(
    val bootReady: Boolean = false,
    val bootFailed: Boolean = false,
    val session: SessionSummary? = null,
    val setupDone: Boolean = false,
    val needsLogin: Boolean = false,
    val engineUnusable: Boolean = false,
    val dataVersion: Long = 0L,
    val homeRefreshVersion: Long = 0L,
) {
    val signedIn: Boolean get() = session != null

    fun gateTarget(): GateTarget = when {
        session == null -> GateTarget.LOGIN
        setupDone -> GateTarget.HOME
        else -> GateTarget.SETUP
    }
}

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val db: AutoLedgerDatabase = AutoLedgerDatabase.getInstance(appContext)
    val repo: Repo = Repo(db)
    val sessionStore: SessionStore = SessionStore(appContext)
    val sms: SmsIngestManager = SmsIngestManager(appContext)
    val flowStore: FlowStore = FlowStore()

    private val _state = MutableStateFlow(AppBootState())
    val state: StateFlow<AppBootState> = _state.asStateFlow()

    @Volatile
    private var currentSession: Session? = null

    @Volatile
    private var startedCycle: Boolean = false

    @Volatile
    var client: ErpClient? = null
        private set

    @Volatile
    var erp: InboxErp? = null
        private set

    val allowHttp: Boolean = BuildConfig.DEBUG

    val cycleRunner = CycleRunner(
        scope = scope,
        run = {
            val s = currentSession ?: return@CycleRunner CycleSummary()
            if (!siteMatches(s.siteUrl)) {
                return@CycleRunner CycleSummary(skippedSiteMismatch = true)
            }
            val activeErp = erp ?: makeInboxErp(buildClient(s))
            app.onebook.autoledger.ingest.IngestPipeline.runForegroundCycle(
                repo = repo,
                sms = sms,
                erp = activeErp,
                now = System::currentTimeMillis,
            )
        },
        after = { summary ->
            try {
                if (HomeLogic.cycleCountsAsSynced(summary)) {
                    try {
                        repo.setSetting(HomeLogic.LAST_SYNCED_KEY, System.currentTimeMillis().toString())
                    } catch (_: Throwable) {
                        // Best effort
                    }
                }
                refreshBanners()
            } finally {
                bumpData()
                bumpHome()
            }
        },
        background = { summary ->
            val reached = summary != null && summary.erpReached && !summary.skippedSiteMismatch
            if (!reached) return@CycleRunner
            val s = currentSession ?: return@CycleRunner
            if (!siteMatches(s.siteUrl)) return@CycleRunner
            val activeClient = client ?: buildClient(s)
            val dupChecked = try {
                DupCheck.runDupCheck(repo, activeClient)
            } catch (_: Throwable) {
                false
            }
            if (dupChecked) {
                val activeErp = erp ?: makeInboxErp(activeClient)
                try {
                    if (siteMatches(s.siteUrl)) {
                        AutoApply.runAutoApply(repo, activeErp, System::currentTimeMillis)
                    }
                } catch (_: Throwable) {
                    // Best effort
                }
            }
            bumpData()
            refreshBanners()
            bumpHome()
        },
    )

    fun boot() {
        scope.launch {
            try {
                val loadedSession = withContext(Dispatchers.IO) {
                    try {
                        sessionStore.load()
                    } catch (_: Throwable) {
                        null
                    }
                }
                val done = repo.getSetting(SETUP_DONE_KEY) == "1"
                applySession(loadedSession)
                _state.update {
                    it.copy(
                        bootReady = true,
                        bootFailed = false,
                        session = loadedSession?.let { s -> SessionSummary(s.siteUrl, s.user) },
                        setupDone = done,
                    )
                }
                refreshBanners()
                if (loadedSession != null && done) {
                    startInitialCycle()
                }
            } catch (_: Throwable) {
                _state.update { it.copy(bootReady = true, bootFailed = true) }
            }
        }
    }

    fun onForeground() {
        val st = _state.value
        if (!st.bootReady || !st.signedIn || !st.setupDone) return
        cycleRunner.triggerAsync()
    }

    private fun startInitialCycle() {
        if (!startedCycle) {
            startedCycle = true
            cycleRunner.triggerAsync()
            scope.launch {
                TemplatesManager.maybeRefreshTemplates(
                    url = TemplatesManager.TEMPLATES_URL,
                    repo = repo,
                    sms = sms,
                    now = System::currentTimeMillis,
                )
            }
        }
    }

    private fun buildClient(s: Session): ErpClient =
        ErpClient(siteUrl = s.siteUrl, apiKey = s.apiKey, apiSecret = s.apiSecret, allowHttp = allowHttp)

    private fun applySession(s: Session?) {
        currentSession = s
        if (s == null) {
            client = null
            erp = null
        } else {
            val c = buildClient(s)
            client = c
            erp = makeInboxErp(c)
        }
    }

    private suspend fun siteMatches(siteUrl: String): Boolean =
        repo.getSetting(SITE_URL_KEY) == siteUrl

    suspend fun refreshBanners() {
        try {
            val needsLogin = !repo.getSetting(OutboxDrainer.NEEDS_LOGIN_SINCE_KEY).isNullOrEmpty()
            val health = try {
                !sms.engineHealth().usable
            } catch (_: Throwable) {
                _state.value.engineUnusable
            }
            _state.update { it.copy(needsLogin = needsLogin, engineUnusable = health) }
        } catch (_: Throwable) {
            // Keep existing banner flags
        }
    }

    fun bumpData() {
        _state.update { it.copy(dataVersion = it.dataVersion + 1L) }
    }

    fun bumpHome() {
        _state.update { it.copy(homeRefreshVersion = it.homeRefreshVersion + 1L) }
    }

    suspend fun previewSiteSwitch(siteUrl: String): SiteSwitchPreview {
        val prev = repo.getSetting(SITE_URL_KEY)
        if (prev.isNullOrBlank() || prev == siteUrl) {
            return SiteSwitchPreview(switching = false, queuedActions = 0)
        }
        var queued = 0
        for (t in repo.listPending()) {
            queued += repo.listOutbox(t.id).count { it.status == OutboxStatus.PENDING }
        }
        return SiteSwitchPreview(switching = true, queuedActions = queued)
    }

    suspend fun signIn(s: Session) {
        val prevSite = repo.getSetting(SITE_URL_KEY)
        if (!prevSite.isNullOrBlank() && prevSite != s.siteUrl) {
            repo.resetForNewSite()
            flowStore.clearAll()
            _state.update { it.copy(setupDone = false) }
        }
        sessionStore.save(s)
        applySession(s)
        if (prevSite.isNullOrBlank() || prevSite != s.siteUrl) {
            repo.setSetting(SITE_URL_KEY, s.siteUrl)
        }
        repo.setSetting(OutboxDrainer.NEEDS_LOGIN_SINCE_KEY, "")
        _state.update {
            it.copy(
                session = SessionSummary(s.siteUrl, s.user),
                needsLogin = false,
            )
        }
        bumpData()
        bumpHome()
        if (_state.value.setupDone) {
            startedCycle = true
            cycleRunner.triggerAsync()
        }
    }

    suspend fun signOut() {
        val was = currentSession
        if (was != null) {
            scope.launch {
                ErpAuth.logoutSession(siteUrl = was.siteUrl, allowHttp = allowHttp)
            }
        }
        sessionStore.clear()
        applySession(null)
        flowStore.clearAll()
        startedCycle = false
        _state.update {
            it.copy(
                session = null,
                needsLogin = false,
            )
        }
    }

    suspend fun completeSetup() {
        currentSession?.let { repo.setSetting(SITE_URL_KEY, it.siteUrl) }
        repo.setSetting(SETUP_DONE_KEY, "1")
        _state.update { it.copy(setupDone = true) }
        startedCycle = true
        cycleRunner.triggerAsync()
    }

    suspend fun runCycle() {
        startedCycle = true
        cycleRunner.trigger()
    }

    companion object {
        const val SETUP_DONE_KEY = "setupDone"
        const val SITE_URL_KEY = "site_url"
        const val COMPANY_KEY = "company"
        const val DRAWINGS_KEY = "drawingsAccount"
        const val DEFAULT_EXPENSE_KEY = "defaultExpenseAccount"
    }
}
