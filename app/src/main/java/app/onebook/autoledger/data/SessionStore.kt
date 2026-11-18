package app.onebook.autoledger.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Session(
    val siteUrl: String,
    val apiKey: String,
    val apiSecret: String,
    val user: String,
    val roles: List<String> = emptyList(),
)

data class SessionSummary(
    val siteUrl: String,
    val user: String,
)

/**
 * Hardware-backed Android Keystore AES-GCM session storage.
 * Password is never stored. Encrypted ciphertext lives in MODE_PRIVATE SharedPreferences.
 */
class SessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(session: Session) {
        val payload = JSONObject()
            .put("siteUrl", session.siteUrl)
            .put("apiKey", session.apiKey)
            .put("apiSecret", session.apiSecret)
            .put("user", session.user)
            .toString()
        val encrypted = encrypt(payload.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(KEY_BLOB, encrypted).apply()
    }

    fun load(): Session? {
        val raw = prefs.getString(KEY_BLOB, null) ?: return null
        return try {
            val plain = String(decrypt(raw), Charsets.UTF_8)
            val o = JSONObject(plain)
            val siteUrl = o.optString("siteUrl", "")
            val apiKey = o.optString("apiKey", "")
            val apiSecret = o.optString("apiSecret", "")
            val user = o.optString("user", "")
            if (siteUrl.isEmpty() || apiKey.isEmpty() || apiSecret.isEmpty() || user.isEmpty()) null
            else Session(siteUrl = siteUrl, apiKey = apiKey, apiSecret = apiSecret, user = user)
        } catch (_: Throwable) {
            null
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_BLOB).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        gen.init(spec)
        return gen.generateKey()
    }

    private fun encrypt(plaintext: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        val combined = ByteArray(1 + iv.size + ciphertext.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(ciphertext, 0, combined, 1 + iv.size, ciphertext.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): ByteArray {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        val ivLen = combined[0].toInt() and 0xFF
        val iv = combined.copyOfRange(1, 1 + ivLen)
        val ciphertext = combined.copyOfRange(1 + ivLen, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "autoledger_session_aes"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFS_NAME = "autoledger_session_enc"
        private const val KEY_BLOB = "session"
    }
}
