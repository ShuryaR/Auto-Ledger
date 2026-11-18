package app.onebook.autoledger.sms

import org.json.JSONArray

class SenderPatterns(
    private val store: KeyValueStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun get(): List<String> = synchronized(SmsQueue.LOCK) {
        val raw = store.read(KEY) ?: return@synchronized emptyList()
        try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getString(it) }
        } catch (_: Exception) {
            store.quarantine(KEY, clock())
            emptyList()
        }
    }

    fun set(patterns: List<String>) = synchronized(SmsQueue.LOCK) {
        val clean = patterns.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        store.write(KEY, JSONArray(clean).toString())
    }

    companion object {
        const val KEY = "sender_patterns.json"
    }
}

data class LoadedEngine(
    val engine: TemplateEngine?,
    val usable: Boolean,
    val reason: String,
    val source: String, // "bundled" | "override"
    val version: Int?,
)

class LazyEngine {
    @Volatile
    private var cached: LoadedEngine? = null
    private val lock = Any()

    fun get(load: () -> LoadedEngine, onLoaded: (LoadedEngine) -> Unit = {}): LoadedEngine =
        cached ?: synchronized(lock) {
            cached ?: load().also {
                cached = it
                onLoaded(it)
            }
        }

    fun <T> update(change: () -> T): T = synchronized(lock) {
        try {
            change()
        } finally {
            cached = null
        }
    }
}

object EngineLoader {
    fun acceptsOverride(overrideJson: String, bundledJson: String?): Boolean {
        val ov = TemplateEngine.fromJson(overrideJson) ?: return false
        if (!TemplateEngine.smokeCheck(ov)) return false
        val b = bundledJson?.let { TemplateEngine.fromJson(it) } ?: return true
        return ov.version >= b.version
    }

    fun choose(overrideJson: String?, bundledJson: String?, selfTestOk: Boolean): LoadedEngine {
        val ov = overrideJson?.let { TemplateEngine.fromJson(it) }
        val b = bundledJson?.let { TemplateEngine.fromJson(it) }
        if (!selfTestOk) {
            val e = b ?: ov ?: return LoadedEngine(
                engine = null,
                usable = false,
                reason = "no usable template set and selfTest failed; SMS are stored unparsed",
                source = "bundled",
                version = null,
            )
            return LoadedEngine(
                engine = e,
                usable = false,
                reason = "selfTest failed: regex named groups unsupported on this device; SMS are stored unparsed",
                source = if (e === b) "bundled" else "override",
                version = e.version,
            )
        }
        if (ov != null && (b == null || ov.version >= b.version)) {
            return LoadedEngine(
                engine = ov,
                usable = true,
                reason = if (b == null) "bundled templates invalid; using override" else "ok",
                source = "override",
                version = ov.version,
            )
        }
        if (b == null) {
            return LoadedEngine(
                engine = null,
                usable = false,
                reason = "no usable template set: bundled templates missing or invalid; SMS are stored unparsed",
                source = "bundled",
                version = null,
            )
        }
        val reason = when {
            overrideJson == null -> "ok"
            ov == null -> "override templates invalid; using bundled"
            else -> "override version ${ov.version} is older than bundled ${b.version}; using bundled"
        }
        return LoadedEngine(b, true, reason, "bundled", b.version)
    }
}
