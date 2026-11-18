package app.onebook.autoledger.ingest

import app.onebook.autoledger.data.Repo
import app.onebook.autoledger.erp.ErpHttp
import app.onebook.autoledger.sms.SmsIngestManager
import app.onebook.autoledger.sms.TemplateEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

object TemplatesManager {
    const val TEMPLATES_JSON_KEY = "templates_json"
    const val TEMPLATES_VERSION_KEY = "templates_version"
    const val TEMPLATES_CHECKED_AT_KEY = "templates_checked_at"
    const val TEMPLATES_CHECK_INTERVAL_MS = 24L * 3600_000L
    const val REFRESH_TIMEOUT_MS = 10_000L
    const val REFRESH_MAX_BYTES = 256 * 1024

    // Kept empty by default (matching src/state/config.ts): bundled templates/idfc.json is active.
    const val TEMPLATES_URL = ""

    enum class RefreshOutcome { SKIPPED, UPDATED, UNCHANGED, FAILED }

    suspend fun loadEngine(repo: Repo, sms: SmsIngestManager): TemplateEngine? {
        val bundled = sms.bundledTemplatesJson()
        val bundledEngine = bundled?.let { TemplateEngine.fromJson(it) }
        val overrideRaw = try {
            repo.getSetting(TEMPLATES_JSON_KEY)
        } catch (_: Throwable) {
            null
        }
        if (overrideRaw != null) {
            val ov = TemplateEngine.fromJson(overrideRaw)
            if (ov != null && TemplateEngine.smokeCheck(ov) && (bundledEngine == null || ov.version >= bundledEngine.version)) {
                return ov
            }
        }
        return bundledEngine ?: sms.engine()
    }

    suspend fun activeTemplateVersion(repo: Repo, sms: SmsIngestManager): Int =
        loadEngine(repo, sms)?.version ?: 1

    suspend fun maybeRefreshTemplates(
        url: String = TEMPLATES_URL,
        repo: Repo,
        sms: SmsIngestManager,
        now: () -> Long = System::currentTimeMillis,
    ): RefreshOutcome {
        if (url.isBlank()) return RefreshOutcome.SKIPPED
        val last = repo.getSetting(TEMPLATES_CHECKED_AT_KEY)?.toLongOrNull()
        val current = now()
        if (last != null && last > 0L && current - last < TEMPLATES_CHECK_INTERVAL_MS) {
            return RefreshOutcome.SKIPPED
        }
        try {
            repo.setSetting(TEMPLATES_CHECKED_AT_KEY, current.toString())
        } catch (_: Throwable) {
            // Best effort before fetch
        }
        return refreshTemplates(url, repo, sms)
    }

    suspend fun refreshTemplates(
        url: String,
        repo: Repo,
        sms: SmsIngestManager,
    ): RefreshOutcome {
        if (!url.startsWith("https://", ignoreCase = true)) return RefreshOutcome.FAILED
        return try {
            val text = fetchHttpsText(url) ?: return RefreshOutcome.FAILED
            val candidate = TemplateEngine.fromJson(text) ?: return RefreshOutcome.FAILED
            val active = activeTemplateVersion(repo, sms)
            if (candidate.version == active) return RefreshOutcome.UNCHANGED
            if (candidate.version < active) return RefreshOutcome.FAILED
            if (!TemplateEngine.smokeCheck(candidate)) return RefreshOutcome.FAILED
            if (!sms.setTemplates(text)) return RefreshOutcome.FAILED
            repo.setSetting(TEMPLATES_JSON_KEY, text)
            repo.setSetting(TEMPLATES_VERSION_KEY, candidate.version.toString())
            RefreshOutcome.UPDATED
        } catch (_: Throwable) {
            RefreshOutcome.FAILED
        }
    }

    private suspend fun fetchHttpsText(url: String): String? = withContext(Dispatchers.IO) {
        val client = ErpHttp.baseClient.newBuilder()
            .callTimeout(REFRESH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .connectTimeout(REFRESH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(REFRESH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        val req = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return@withContext null
            if (!res.request.url.isHttps) return@withContext null
            val contentLength = res.header("Content-Length")?.toLongOrNull()
            if (contentLength != null && contentLength > REFRESH_MAX_BYTES) return@withContext null
            val bytes = res.body?.bytes() ?: return@withContext null
            if (bytes.size > REFRESH_MAX_BYTES) return@withContext null
            bytes.toString(Charsets.UTF_8)
        }
    }
}
