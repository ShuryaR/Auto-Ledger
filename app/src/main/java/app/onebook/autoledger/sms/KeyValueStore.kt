package app.onebook.autoledger.sms

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

interface KeyValueStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun rename(key: String, newKey: String): Boolean
    fun keys(): List<String>
    fun delete(key: String)
}

fun KeyValueStore.quarantine(key: String, stamp: Long): String? {
    val name = "$key${Quarantine.MARK}$stamp"
    if (!rename(key, name)) return null
    Quarantine.prune(this, stamp)
    return name
}

object Quarantine {
    const val MARK = ".corrupt-"
    private const val TMP_SUFFIX = ".tmp"
    const val MAX_AGE_MS = 7L * 86_400_000L
    private val NAME = Regex("^(.+)\\.corrupt-(\\d{1,19})$")

    fun pruneAll(store: KeyValueStore, now: Long): Int {
        var deleted = 0
        for (k in store.keys()) {
            if (k.endsWith(TMP_SUFFIX)) {
                store.delete(k)
                deleted++
            }
        }
        return deleted + prune(store, now)
    }

    fun prune(store: KeyValueStore, now: Long, maxAgeMs: Long = MAX_AGE_MS): Int {
        val byKey = HashMap<String, MutableList<Pair<Long, String>>>()
        for (k in store.keys()) {
            val m = NAME.matchEntire(k) ?: continue
            val stamp = m.groupValues[2].toLongOrNull() ?: continue
            byKey.getOrPut(m.groupValues[1]) { ArrayList() }.add(stamp to k)
        }
        var deleted = 0
        for (list in byKey.values) {
            list.sortByDescending { it.first }
            list.forEachIndexed { i, (stamp, name) ->
                if (i > 0 || now - stamp > maxAgeMs) {
                    store.delete(name)
                    deleted++
                }
            }
        }
        return deleted
    }
}

class FileKeyValueStore(private val dir: File) : KeyValueStore {
    override fun read(key: String): String? {
        val f = File(dir, key)
        return if (f.isFile) f.readText(Charsets.UTF_8) else null
    }

    override fun write(key: String, value: String) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        val tmp = File(dir, "$key.tmp")
        FileOutputStream(tmp).use {
            it.write(value.toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        Files.move(
            tmp.toPath(),
            File(dir, key).toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    override fun rename(key: String, newKey: String): Boolean {
        val f = File(dir, key)
        if (!f.exists()) return false
        Files.move(f.toPath(), File(dir, newKey).toPath(), StandardCopyOption.REPLACE_EXISTING)
        return true
    }

    override fun keys(): List<String> = dir.listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList()

    override fun delete(key: String) {
        File(dir, key).delete()
    }
}
