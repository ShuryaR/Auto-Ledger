package app.onebook.autoledger.home

import app.onebook.autoledger.core.AccountMapping
import app.onebook.autoledger.core.AccountType
import app.onebook.autoledger.core.Direction
import app.onebook.autoledger.core.PendingTxn
import app.onebook.autoledger.core.TrustEngine
import app.onebook.autoledger.core.TxnState
import app.onebook.autoledger.data.NewSourceInfo
import app.onebook.autoledger.data.Repo
import app.onebook.autoledger.erp.ErpAuthError
import app.onebook.autoledger.erp.ErpClient
import app.onebook.autoledger.erp.ErpDocs
import app.onebook.autoledger.erp.ErpReports
import app.onebook.autoledger.inbox.Formatters
import app.onebook.autoledger.ingest.CycleSummary
import app.onebook.autoledger.ingest.DrainStopReason
import app.onebook.autoledger.ingest.IngestPipeline
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToLong

data class HomeFigures(
    val balances: Map<String, Long>,
    val receivableMinor: Long?,
    val payableMinor: Long?,
)

data class HomeCache(
    val at: Long,
    val site: String,
    val balances: Map<String, Long>,
    val receivableMinor: Long,
    val payableMinor: Long,
)

data class UnansweredSummary(
    val count: Int,
    val sources: List<NewSourceInfo>,
)

data class HomeInputs(
    val mappings: List<AccountMapping>,
    val pending: List<PendingTxn>,
    val unanswered: UnansweredSummary? = null,
    val figures: HomeFigures? = null,
    val figuresAt: Long? = null,
    val figuresStale: Boolean = false,
    val lastSyncedAt: Long? = null,
    val now: Long,
)

data class HomeBankAccount(
    val name: String,
    val glAccount: String,
    val minor: Long?,
    val text: String,
)

data class HomeBankTotal(
    val minor: Long?,
    val text: String,
)

data class HomeBankSummary(
    val accounts: List<HomeBankAccount>,
    val total: HomeBankTotal?,
)

data class HomeTile(
    val label: String,
    val text: String,
)

data class HomeDraftsSummary(
    val count: Int,
    val inCount: Int,
    val inMinor: Long,
    val outCount: Int,
    val outMinor: Long,
    val summaryText: String,
    val otherCurrencyCount: Int,
    val footnote: String?,
)

data class HomeModel(
    val hasFigures: Boolean,
    val bank: HomeBankSummary,
    val receivable: HomeTile,
    val payable: HomeTile,
    val drafts: HomeDraftsSummary,
    val unclassifiedCount: Int,
    val smsBalanceMinor: Long?,
    val trust: TrustEngine.Trust,
    val trustLine: String?,
    val asOfLabel: String?,
    val couldNotRefresh: Boolean,
    val lastSyncedText: String,
)

data class AuthProblem(
    val status: Int,
    val deniedDoctype: String? = null,
)

data class LoadedFigures(
    val figures: HomeFigures,
    val at: Long,
    val stale: Boolean,
    val auth: AuthProblem?,
)

enum class HomeLoadStatus { LOADING, READY, ERROR }

data class HomeUiState(
    val status: HomeLoadStatus = HomeLoadStatus.LOADING,
    val model: HomeModel? = null,
    val company: String? = null,
    val auth: AuthProblem? = null,
    val refreshing: Boolean = false,
)

object HomeLogic {
    const val DASH = "\u2014"
    const val HOME_CACHE_KEY = "home_cache"
    const val LAST_SYNCED_KEY = "last_synced_at"
    private val HOME_STATES = listOf(TxnState.CREATED, TxnState.SUBMITTED, TxnState.IGNORED)

    fun bankDisplayName(name: String): String {
        val seen = HashSet<String>()
        val parts = name.split(" - ")
            .map { it.trim() }
            .filter { p ->
                if (p.isEmpty()) return@filter false
                val k = p.lowercase()
                seen.add(k)
            }
        return if (parts.isNotEmpty()) parts.joinToString(", ") else name
    }

    private fun inrText(m: Long?): String = if (m == null) DASH else Formatters.formatInr(m)

    fun wholeText(m: Long?): String {
        if (m == null) return DASH
        val a = abs(m)
        if (a > 0L && a < 50L) return "under \u20B91"
        val sign = if (m < 0L) -1L else 1L
        val rounded = sign * ((a / 100.0).roundToLong()) * 100L
        return Formatters.formatInr(rounded).replace(Regex("\\.\\d\\d$"), "")
    }

    private fun tile(m: Long?, positive: String, negative: String): HomeTile {
        if (m != null && m < 0L) return HomeTile(label = negative, text = wholeText(-m))
        return HomeTile(label = positive, text = wholeText(m))
    }

    fun cycleCountsAsSynced(s: CycleSummary?): Boolean =
        s != null &&
            !s.skippedSiteMismatch &&
            s.failedSteps.isEmpty() &&
            s.erpReached &&
            s.poll?.needsLogin != true &&
            (s.drain?.stoppedBecause == DrainStopReason.EMPTY || s.drain?.stoppedBecause == DrainStopReason.LIMIT)

    fun relativeTime(now: Long, then: Long): String {
        val s = maxOf(0L, (now - then) / 1000L)
        if (s < 60L) return "just now"
        fun plural(n: Long, u: String) = "$n $u${if (n == 1L) "" else "s"} ago"
        if (s < 3600L) return plural(s / 60L, "minute")
        if (s < 86400L) return plural(s / 3600L, "hour")
        return plural(s / 86400L, "day")
    }

    fun buildHomeModel(i: HomeInputs): HomeModel {
        val banks = i.mappings.filter { it.kind == AccountType.BANK }
        val accounts = banks.map { m ->
            val minor = i.figures?.balances?.get(m.glAccount)
            HomeBankAccount(
                name = bankDisplayName(m.bankAccount),
                glAccount = m.glAccount,
                minor = minor,
                text = inrText(minor),
            )
        }
        val totalMinor = if (accounts.size > 1 && accounts.all { it.minor != null }) {
            accounts.sumOf { it.minor!! }
        } else {
            null
        }
        val total = if (accounts.size > 1) HomeBankTotal(minor = totalMinor, text = inrText(totalMinor)) else null

        val created = i.pending.filter { it.state == TxnState.CREATED }
        val inr = created.filter { it.currency == "INR" }
        val otherCurrencyCount = created.size - inr.size
        val ins = inr.filter { it.direction == Direction.IN }
        val outs = inr.filter { it.direction == Direction.OUT }
        val inMinor = ins.sumOf { it.amountMinor }
        val outMinor = outs.sumOf { it.amountMinor }
        val summaryText = listOfNotNull(
            if (ins.isNotEmpty()) "${Formatters.formatInr(inMinor)} in" else null,
            if (outs.isNotEmpty()) "${Formatters.formatInr(outMinor)} out" else null,
        ).joinToString(" \u00B7 ")
        val unclassifiedCount = i.pending.count { it.state == TxnState.NEW } + (i.unanswered?.count ?: 0)

        var pair: Pair<Long, Long>? = null
        if (!i.figuresStale) {
            val rowsOf = HashMap<AccountMapping, MutableList<PendingTxn>>()
            val unsettled = HashSet<AccountMapping>()
            for (t in i.pending) {
                if (t.accountType != AccountType.BANK) continue
                val m = IngestPipeline.resolveMapping(i.mappings, t) ?: continue
                rowsOf.getOrPut(m) { mutableListOf() }.add(t)
                if (t.state == TxnState.NEW || t.state == TxnState.CREATED) unsettled.add(m)
            }
            for (src in i.unanswered?.sources.orEmpty()) {
                if (src.accountType != AccountType.BANK) continue
                val m = IngestPipeline.resolveMapping(i.mappings, src.accountType, src.sender, src.accountLast4)
                if (m != null) unsettled.add(m)
            }
            for (idx in banks.indices) {
                val m = banks[idx]
                val computed = accounts[idx].minor ?: continue
                if (m in unsettled) continue
                val mine = rowsOf[m].orEmpty()
                val newest = mine
                    .filter { it.currency == "INR" && it.balanceMinor != null }
                    .maxByOrNull { it.receivedAt } ?: continue
                val smsBal = newest.balanceMinor ?: continue
                val cand = computed to smsBal
                if (computed != smsBal) {
                    pair = cand
                    break
                }
                if (pair == null) pair = cand
            }
        }

        val trust = TrustEngine.buildTrust(
            TrustEngine.TrustInput(
                lastSyncedAt = i.lastSyncedAt,
                now = i.now,
                unclassifiedCount = unclassifiedCount,
                pendingDrafts = TrustEngine.PendingDraftsSummary(created.size, inMinor + outMinor),
                computedBankBalanceMinor = pair?.first,
                smsBalanceMinor = pair?.second,
            )
        )

        val trustLine = if (banks.size != 1 || pair == null) {
            null
        } else if (pair.first == pair.second) {
            "Matches the balance in the latest bank message"
        } else {
            "Last bank message shows ${Formatters.formatInr(pair.second)}"
        }

        return HomeModel(
            hasFigures = i.figures != null,
            bank = HomeBankSummary(accounts = accounts, total = total),
            receivable = tile(i.figures?.receivableMinor, "To receive", "Advances received"),
            payable = tile(i.figures?.payableMinor, "To pay", "Advances paid"),
            drafts = HomeDraftsSummary(
                count = created.size,
                inCount = ins.size,
                inMinor = inMinor,
                outCount = outs.size,
                outMinor = outMinor,
                summaryText = summaryText,
                otherCurrencyCount = otherCurrencyCount,
                footnote = if (otherCurrencyCount > 0) "plus $otherCurrencyCount in other currencies" else null,
            ),
            unclassifiedCount = unclassifiedCount,
            smsBalanceMinor = pair?.second,
            trust = trust,
            trustLine = trustLine,
            asOfLabel = if (i.figuresStale && i.figuresAt != null) "as of ${relativeTime(i.now, i.figuresAt)}" else null,
            couldNotRefresh = i.figuresStale,
            lastSyncedText = if (i.lastSyncedAt == null) "Not synced yet" else "Last synced ${relativeTime(i.now, i.lastSyncedAt)}",
        )
    }

    fun serializeHomeCache(c: HomeCache): String {
        val balObj = JSONObject()
        for ((k, v) in c.balances) balObj.put(k, v)
        return JSONObject()
            .put("at", c.at)
            .put("site", c.site)
            .put("balances", balObj)
            .put("receivableMinor", c.receivableMinor)
            .put("payableMinor", c.payableMinor)
            .toString()
    }

    fun parseHomeCache(raw: String?, site: String): HomeCache? {
        if (raw.isNullOrBlank()) return null
        return try {
            val o = JSONObject(raw)
            if (o.optString("site", "") != site) return null
            if (!o.has("at") || !o.has("receivableMinor") || !o.has("payableMinor")) return null
            val at = o.getLong("at")
            val receivableMinor = o.getLong("receivableMinor")
            val payableMinor = o.getLong("payableMinor")
            val balObj = o.optJSONObject("balances") ?: return null
            val balances = LinkedHashMap<String, Long>()
            val keys = balObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = balObj.opt(k)
                if (v !is Number) return null
                balances[k] = v.toLong()
            }
            HomeCache(
                at = at,
                site = site,
                balances = balances,
                receivableMinor = receivableMinor,
                payableMinor = payableMinor,
            )
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun loadFigures(
        client: ErpClient?,
        siteUrl: String,
        company: String,
        mappings: List<AccountMapping>,
        getCache: suspend () -> String?,
        saveCache: suspend (String) -> Unit,
        now: () -> Long,
    ): LoadedFigures {
        return try {
            if (client == null) throw IllegalStateException("Not signed in")
            val banks = mappings.filter { it.kind == AccountType.BANK }
            val (amounts, rp) = coroutineScope {
                val bankDeferreds = banks.map { m ->
                    async { ErpReports.getBankBalance(client, m.glAccount, company) }
                }
                val rpDeferred = async { ErpReports.getReceivablesPayables(client, company) }
                bankDeferreds.awaitAll() to rpDeferred.await()
            }
            val balances = LinkedHashMap<String, Long>()
            banks.forEachIndexed { idx, m -> balances[m.glAccount] = amounts[idx] }
            val at = now()
            try {
                saveCache(
                    serializeHomeCache(
                        HomeCache(
                            at = at,
                            site = siteUrl,
                            balances = balances,
                            receivableMinor = rp.receivableMinor,
                            payableMinor = rp.payableMinor,
                        )
                    )
                )
            } catch (_: Throwable) {
                // Best effort
            }
            LoadedFigures(
                figures = HomeFigures(balances, rp.receivableMinor, rp.payableMinor),
                at = at,
                stale = false,
                auth = null,
            )
        } catch (e: Throwable) {
            val cache = try {
                parseHomeCache(getCache(), siteUrl)
            } catch (_: Throwable) {
                null
            }
            if (cache == null) throw e
            val auth = if (e is ErpAuthError) AuthProblem(e.status, e.deniedDoctype) else null
            LoadedFigures(
                figures = HomeFigures(cache.balances, cache.receivableMinor, cache.payableMinor),
                at = cache.at,
                stale = true,
                auth = auth,
            )
        }
    }

    suspend fun loadHomeSnapshot(
        repo: Repo,
        client: ErpClient?,
        siteUrl: String?,
        companyKey: String,
        now: () -> Long,
    ): HomeUiState {
        if (siteUrl == null) {
            return HomeUiState(status = HomeLoadStatus.LOADING)
        }
        return try {
            val mappings = repo.listMappings()
            val pending = repo.listPending(HOME_STATES)
            val newCount = repo.countPending(TxnState.NEW)
            val sources = repo.listNewSources()
            val synced = repo.getSetting(LAST_SYNCED_KEY)?.toLongOrNull()
            val company = repo.getSetting(companyKey)

            if (company.isNullOrBlank()) {
                val model = buildHomeModel(
                    HomeInputs(
                        mappings = mappings,
                        pending = pending,
                        unanswered = UnansweredSummary(newCount, sources),
                        figures = null,
                        figuresAt = null,
                        figuresStale = false,
                        lastSyncedAt = synced,
                        now = now(),
                    )
                )
                return HomeUiState(
                    status = HomeLoadStatus.READY,
                    model = model,
                    company = null,
                    auth = null,
                    refreshing = false,
                )
            }

            val fetched = try {
                loadFigures(
                    client = client,
                    siteUrl = siteUrl,
                    company = company,
                    mappings = mappings,
                    getCache = { repo.getSetting(HOME_CACHE_KEY) },
                    saveCache = { repo.setSetting(HOME_CACHE_KEY, it) },
                    now = now,
                )
            } catch (e: Throwable) {
                val auth = if (e is ErpAuthError) AuthProblem(e.status, e.deniedDoctype) else null
                return HomeUiState(
                    status = HomeLoadStatus.ERROR,
                    model = null,
                    company = company,
                    auth = auth,
                    refreshing = false,
                )
            }

            val model = buildHomeModel(
                HomeInputs(
                    mappings = mappings,
                    pending = pending,
                    unanswered = UnansweredSummary(newCount, sources),
                    figures = fetched.figures,
                    figuresAt = fetched.at,
                    figuresStale = fetched.stale,
                    lastSyncedAt = synced,
                    now = now(),
                )
            )
            HomeUiState(
                status = HomeLoadStatus.READY,
                model = model,
                company = company,
                auth = fetched.auth,
                refreshing = false,
            )
        } catch (e: Throwable) {
            val auth = if (e is ErpAuthError) AuthProblem(e.status, e.deniedDoctype) else null
            HomeUiState(status = HomeLoadStatus.ERROR, auth = auth)
        }
    }
}
