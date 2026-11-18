package app.onebook.autoledger.sms

import org.json.JSONArray
import org.json.JSONObject

data class QueuedSms(
    val id: String,
    val sender: String,
    val body: String,
    val receivedAt: Long,
    val kind: String,
    val seq: Long,
)

class SmsQueue(
    private val store: KeyValueStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxItems: Int = MAX_ITEMS,
) {
    private class Doc(var seq: Long, val items: MutableList<QueuedSms>)

    fun append(sender: String, body: String, receivedAt: Long, kind: String): QueuedSms = synchronized(LOCK) {
        val doc = load()
        doc.seq += 1
        val item = QueuedSms("${clock()}-${doc.seq}", sender, body, receivedAt, kind, doc.seq)
        doc.items.add(item)
        val over = doc.items.size - maxItems
        if (over > 0) {
            repeat(over) { doc.items.removeAt(0) }
            store.write(DROPPED_KEY, (readDropped() + over).toString())
        }
        save(doc)
        item
    }

    fun peek(): List<QueuedSms> = synchronized(LOCK) { load().items.toList() }

    fun ack(ids: Collection<String>) = synchronized(LOCK) {
        if (ids.isEmpty()) return@synchronized
        val doc = load()
        val gone = ids.toSet()
        if (doc.items.removeAll { it.id in gone }) save(doc)
    }

    fun droppedCount(): Long = synchronized(LOCK) { readDropped() }

    private fun readDropped(): Long {
        val s = store.read(DROPPED_KEY) ?: return 0L
        return s.trim().toLongOrNull() ?: run {
            store.quarantine(DROPPED_KEY, clock())
            0L
        }
    }

    private fun load(): Doc {
        val raw = store.read(KEY) ?: return Doc(0L, ArrayList())
        return try {
            val o = JSONObject(raw)
            val arr = o.getJSONArray("items")
            val items = ArrayList<QueuedSms>(arr.length())
            for (i in 0 until arr.length()) {
                val it = arr.getJSONObject(i)
                val id = it.getString("id")
                items.add(
                    QueuedSms(
                        id = id,
                        sender = it.getString("sender"),
                        body = it.getString("body"),
                        receivedAt = it.getLong("receivedAt"),
                        kind = it.getString("kind"),
                        seq = if (it.has("seq")) it.getLong("seq") else id.substringAfterLast('-').toLongOrNull() ?: 0L,
                    )
                )
            }
            Doc(o.getLong("seq"), items)
        } catch (_: Exception) {
            val now = clock()
            store.quarantine(KEY, now)
            Doc(NotificationText.notificationId(now / 1000).toLong(), ArrayList())
        }
    }

    private fun save(doc: Doc) {
        val arr = JSONArray()
        for (it in doc.items) {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("sender", it.sender)
                    .put("body", it.body)
                    .put("receivedAt", it.receivedAt)
                    .put("kind", it.kind)
                    .put("seq", it.seq)
            )
        }
        store.write(KEY, JSONObject().put("v", 1).put("seq", doc.seq).put("items", arr).toString())
    }

    companion object {
        const val KEY = "queue.json"
        const val DROPPED_KEY = "dropped"
        const val MAX_ITEMS = 500
        val LOCK = Any()
    }
}
