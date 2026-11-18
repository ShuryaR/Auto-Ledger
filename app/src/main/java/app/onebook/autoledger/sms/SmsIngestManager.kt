package app.onebook.autoledger.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.io.File

internal const val TAG = "SmsIngest"

internal object Storage {
    fun store(ctx: Context) = FileKeyValueStore(File(ctx.noBackupFilesDir, "sms-ingest"))
    fun queue(ctx: Context) = SmsQueue(store(ctx), System::currentTimeMillis, SmsQueue.MAX_ITEMS)
    fun patterns(ctx: Context) = SenderPatterns(store(ctx))

    @Volatile
    private var housekept = false

    fun housekeeping(ctx: Context, now: Long = System.currentTimeMillis()) {
        if (housekept) return
        housekept = true
        try {
            synchronized(SmsQueue.LOCK) { Quarantine.pruneAll(store(ctx), now) }
        } catch (t: Throwable) {
            Log.w(TAG, "quarantine prune failed: ${t.javaClass.simpleName}")
        }
    }
}

object EngineProvider {
    private const val ASSET = "templates/idfc.json"
    private val lazy = LazyEngine()

    private fun overrideDir(ctx: Context) = File(ctx.filesDir, "templates")

    fun get(ctx: Context): LoadedEngine = lazy.get({ load(ctx) }) {
        if (!it.usable) Log.e(TAG, "template engine unusable: ${it.reason}")
    }

    fun setOverride(ctx: Context, json: String): Boolean {
        val bundled = readAsset(ctx)
        if (!EngineLoader.acceptsOverride(json, bundled)) return false
        lazy.update { FileKeyValueStore(overrideDir(ctx)).write("idfc.json", json) }
        return true
    }

    fun clearOverride(ctx: Context) {
        lazy.update { File(overrideDir(ctx), "idfc.json").delete() }
    }

    private fun readAsset(ctx: Context): String? = try {
        ctx.assets.open(ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (_: Throwable) {
        null
    }

    private fun load(ctx: Context): LoadedEngine {
        val selfTest = TemplateEngine.selfTest()
        val override = try {
            FileKeyValueStore(overrideDir(ctx)).read("idfc.json")
        } catch (_: Throwable) {
            null
        }
        val bundled = readAsset(ctx)
        return EngineLoader.choose(override, bundled, selfTest)
    }
}

object Perms {
    fun granted(ctx: Context, p: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun notificationsAllowed(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33) granted(ctx, Manifest.permission.POST_NOTIFICATIONS)
        else NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    fun wanted(): Array<String> {
        val base = arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
        return if (Build.VERSION.SDK_INT >= 33) base + Manifest.permission.POST_NOTIFICATIONS else base
    }
}

internal object Ingestor {
    fun ingest(ctx: Context, parts: List<SmsPart>) {
        val filter = SenderFilter(Storage.patterns(ctx).get())
        if (!filter.hasPatterns) return
        val loaded = EngineProvider.get(ctx)
        val classifier = MessageClassifier(filter, loaded.engine, loaded.usable)
        val queue = Storage.queue(ctx)
        for (g in MultipartGrouper.group(parts)) {
            val d = classifier.classify(g.sender, g.body)
            if (d !is Decision.Store) continue
            val item = queue.append(g.sender, g.body, g.receivedAt, d.kind)
            val parse = d.parse ?: continue
            try {
                NotificationPoster.post(ctx, item, parse)
            } catch (t: Throwable) {
                Log.w(TAG, "notification failed: ${t.javaClass.simpleName}")
            }
        }
    }
}

data class PermissionStatus(
    val receiveSms: Boolean,
    val readSms: Boolean,
    val notifications: Boolean,
) {
    val allGranted: Boolean get() = receiveSms && readSms && notifications
}

data class EngineHealth(
    val usable: Boolean,
    val reason: String,
    val source: String,
    val version: Int?,
    val droppedFromQueue: Long,
)

data class SmsIngestRecord(
    val id: String,
    val sender: String,
    val body: String,
    val receivedAt: Long,
    val kind: String,
)

data class BackfillScanResult(
    val messages: List<SmsIngestRecord>,
    val truncated: Boolean,
)

class SmsIngestManager(private val context: Context) {
    private val appCtx = context.applicationContext

    init {
        Storage.housekeeping(appCtx)
    }

    fun permissionStatus(): PermissionStatus = PermissionStatus(
        receiveSms = Perms.granted(appCtx, Manifest.permission.RECEIVE_SMS),
        readSms = Perms.granted(appCtx, Manifest.permission.READ_SMS),
        notifications = Perms.notificationsAllowed(appCtx),
    )

    fun setSenderPatterns(patterns: List<String>) {
        Storage.patterns(appCtx).set(patterns)
    }

    fun getSenderPatterns(): List<String> = Storage.patterns(appCtx).get()

    fun setTemplates(json: String): Boolean = EngineProvider.setOverride(appCtx, json)

    fun clearTemplatesOverride() = EngineProvider.clearOverride(appCtx)

    fun bundledTemplatesJson(): String? = try {
        appCtx.assets.open("templates/idfc.json").use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (_: Throwable) {
        null
    }

    fun engine(): TemplateEngine? = EngineProvider.get(appCtx).engine

    fun engineHealth(): EngineHealth {
        val loaded = EngineProvider.get(appCtx)
        return EngineHealth(
            usable = loaded.usable,
            reason = loaded.reason,
            source = loaded.source,
            version = loaded.version,
            droppedFromQueue = Storage.queue(appCtx).droppedCount(),
        )
    }

    fun peekQueue(): List<SmsIngestRecord> = Storage.queue(appCtx).peek().map {
        SmsIngestRecord(it.id, it.sender, it.body, it.receivedAt, it.kind)
    }

    fun ackQueue(ids: List<String>) {
        Storage.queue(appCtx).ack(ids)
    }

    fun simulateIncoming(sender: String, body: String, receivedAt: Long = System.currentTimeMillis()) {
        Ingestor.ingest(appCtx, listOf(SmsPart(sender, body, receivedAt)))
    }

    fun backfill(days: Int, cap: Int = BackfillQuery.CAP): List<SmsIngestRecord> =
        backfillScan(days = days, cap = cap).messages

    fun backfillScan(
        days: Int,
        patternsOverride: List<String>? = null,
        cap: Int = BackfillQuery.CAP,
    ): BackfillScanResult {
        if (!Perms.granted(appCtx, Manifest.permission.READ_SMS)) return BackfillScanResult(emptyList(), false)
        if (!BackfillQuery.reads(days)) return BackfillScanResult(emptyList(), false)
        val patterns = patternsOverride ?: Storage.patterns(appCtx).get()
        val filter = SenderFilter(patterns)
        if (!filter.hasPatterns) return BackfillScanResult(emptyList(), false)

        val loaded = EngineProvider.get(appCtx)
        val classifier = MessageClassifier(filter, loaded.engine, loaded.usable)
        val now = System.currentTimeMillis()
        val bounds = BackfillQuery.bounds(days, now, Telephony.Sms.DATE)
        val result = ArrayList<SmsIngestRecord>()
        var truncated = false
        val projection = arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        val sortOrder = "${Telephony.Sms.DATE} DESC"

        appCtx.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            bounds.selection,
            bounds.args,
            sortOrder,
        )?.use { cursor ->
            val idxId = cursor.getColumnIndex(Telephony.Sms._ID)
            val idxAddr = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
            val idxBody = cursor.getColumnIndex(Telephony.Sms.BODY)
            val idxDate = cursor.getColumnIndex(Telephony.Sms.DATE)
            var matched = 0
            var inspected = 0
            while (cursor.moveToNext()) {
                if (inspected >= cap || matched >= cap) {
                    truncated = true
                    break
                }
                inspected++
                val id = if (idxId >= 0) cursor.getString(idxId) ?: "" else ""
                val sender = if (idxAddr >= 0) cursor.getString(idxAddr) ?: "" else ""
                val body = if (idxBody >= 0) cursor.getString(idxBody) ?: "" else ""
                val date = if (idxDate >= 0) cursor.getLong(idxDate) else 0L
                val d = classifier.classify(sender, body)
                if (d is Decision.Store) {
                    matched++
                    result.add(
                        SmsIngestRecord(
                            id = "bf-$id-$date",
                            sender = sender,
                            body = body,
                            receivedAt = date,
                            kind = d.kind,
                        )
                    )
                }
            }
        }
        return BackfillScanResult(messages = result, truncated = truncated)
    }
}
