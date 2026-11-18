package app.onebook.autoledger.erp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object ErpHttp {
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun normalizeSiteUrl(input: String, allowHttp: Boolean): String {
        var s = input.trim()
        if (s.isEmpty()) throw IllegalArgumentException("Enter your ERPNext site address.")
        if (!Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE).containsMatchIn(s)) {
            s = "https://$s"
        }
        val u = try {
            URL(s)
        } catch (_: Exception) {
            throw IllegalArgumentException("That does not look like a valid site address.")
        }
        val proto = u.protocol.lowercase()
        if (proto == "http" && !allowHttp) throw IllegalArgumentException("Use an https:// address.")
        if (proto != "https" && proto != "http") throw IllegalArgumentException("Use an https:// address.")
        val port = if (u.port != -1 && u.port != u.defaultPort) ":${u.port}" else ""
        return "$proto://${u.host}$port"
    }

    fun encodeSegment(s: String): String =
        URLEncoder.encode(s, StandardCharsets.UTF_8.name()).replace("+", "%20")

    fun unexpectedResponse(): ErpNetworkError = ErpNetworkError("Unexpected response from server")

    private fun stripTags(s: String): String =
        s.replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .trim()

    private fun serverMessages(body: JSONObject): List<String> {
        val raw = body.opt(" _server_messages".trim()) as? String ?: return emptyList()
        return try {
            val outer = JSONArray(raw)
            val out = mutableListOf<String>()
            for (i in 0 until outer.length()) {
                val entry = outer.get(i)
                val obj = when (entry) {
                    is String -> try { JSONObject(entry) } catch (_: Exception) { null }
                    is JSONObject -> entry
                    else -> null
                }
                val msg = obj?.opt("message") as? String
                if (msg != null) out.add(msg)
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun missingFieldsFrom(messages: List<String>): List<String>? {
        val out = LinkedHashSet<String>()
        val valMissing = Regex("Value missing for [^:]+:\\s*(.+)$", RegexOption.IGNORE_CASE)
        val mandReq = Regex("Mandatory fields? required[^:]*:\\s*(.+)$", RegexOption.IGNORE_CASE)
        for (raw in messages) {
            val m = stripTags(raw)
            val r1 = valMissing.find(m)
            if (r1 != null) {
                out.add(r1.groupValues[1].trim())
                continue
            }
            val r2 = mandReq.find(m)
            if (r2 != null) {
                r2.groupValues[1].split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { out.add(it) }
            }
        }
        return if (out.isEmpty()) null else out.toList()
    }

    private fun deniedDoctypeFrom(body: JSONObject, messages: List<String>): String? {
        val docRx = Regex("(?:for document|Insufficient Permission for)\\s*<strong>([^<]+)</strong>", RegexOption.IGNORE_CASE)
        for (m in messages) {
            val r = docRx.find(m)
            if (r != null) return r.groupValues[1]
        }
        val em = body.opt("_error_message") as? String
        if (em != null) {
            val r = Regex("No permission for (.+)$", RegexOption.IGNORE_CASE).find(em)
            if (r != null) return r.groupValues[1].trim()
        }
        return null
    }

    fun errorFromResponse(status: Int, text: String): RuntimeException {
        if (status >= 500) {
            return ErpNetworkError("The ERPNext server had a problem (HTTP $status).", status)
        }
        val b = try {
            JSONObject(text)
        } catch (_: Exception) {
            JSONObject()
        }
        val messages = serverMessages(b)
        val text1 = messages.map { stripTags(it) }.firstOrNull { it.isNotEmpty() }
            ?: (b.opt("message") as? String)?.let { stripTags(it) }
            ?: ""

        if (status == 401) return ErpAuthError("ERPNext did not accept the credentials.", 401)
        if (status == 403) {
            val doctype = deniedDoctypeFrom(b, messages)
            val msg = if (doctype != null) {
                "Your ERPNext user is not allowed to do this on $doctype. Ask your ERPNext administrator for access."
            } else {
                "Your ERPNext user is not allowed to do this. Ask your ERPNext administrator for access."
            }
            return ErpAuthError(msg, 403, doctype)
        }
        val excType = b.optString("exc_type", "")
        val closed = excType == "ClosedAccountingPeriod"
        val missing = if (excType == "MandatoryError" || excType.isEmpty()) missingFieldsFrom(messages) else null
        val fallback = (b.opt("exception") as? String)?.let {
            stripTags(it.replace(Regex("^[\\w.]+:\\s*"), ""))
        } ?: ""
        val message = text1.ifEmpty { fallback.ifEmpty { "ERPNext rejected the request (HTTP $status)." } }
        return ErpValidationError(message, status, missing, closed)
    }

    suspend fun execute(
        client: OkHttpClient,
        url: String,
        method: String,
        headers: Map<String, String>,
        jsonBody: String? = null,
        timeoutMs: Long = 30_000L,
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val c = if (timeoutMs == 30_000L) client else client.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val reqBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        val body = jsonBody?.toRequestBody(JSON_MEDIA)
            ?: if (method == "POST" || method == "PUT") "{}".toRequestBody(JSON_MEDIA) else null
        reqBuilder.method(method, body)
        try {
            c.newCall(reqBuilder.build()).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (res.code in 200..299) {
                    res.code to text
                } else {
                    throw errorFromResponse(res.code, text)
                }
            }
        } catch (e: RuntimeException) {
            if (e is ErpAuthError || e is ErpValidationError || e is ErpNetworkError) throw e
            throw ErpNetworkError("Could not reach the ERPNext site. Check your connection and try again.")
        } catch (_: IOException) {
            throw ErpNetworkError("Could not reach the ERPNext site. Check your connection and try again.")
        }
    }

    fun newSessionCookieJar(): CookieJar = object : CookieJar {
        private val store = ConcurrentHashMap<String, List<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val cur = store[url.host].orEmpty().associateBy { it.name }.toMutableMap()
            cookies.forEach { cur[it.name] = it }
            store[url.host] = cur.values.toList()
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            return store[url.host].orEmpty().filter { it.expiresAt >= now }
        }
    }
}

class ErpClient(
    siteUrl: String,
    apiKey: String,
    apiSecret: String,
    allowHttp: Boolean = false,
) {
    val siteUrl: String = ErpHttp.normalizeSiteUrl(siteUrl, allowHttp = allowHttp)
    private val authHeader: String = "token $apiKey:$apiSecret"

    private suspend fun sendRaw(
        method: String,
        path: String,
        params: Map<String, Any?>? = null,
        jsonBody: String? = null,
    ): String {
        val urlBuilder = StringBuilder(siteUrl).append(path)
        if (!params.isNullOrEmpty()) {
            val query = params.entries
                .filter { it.value != null }
                .joinToString("&") { (k, v) ->
                    val encodedVal = when (v) {
                        is String -> URLEncoder.encode(v, StandardCharsets.UTF_8.name())
                        else -> URLEncoder.encode(v.toString(), StandardCharsets.UTF_8.name())
                    }
                    "${URLEncoder.encode(k, StandardCharsets.UTF_8.name())}=$encodedVal"
                }
            if (query.isNotEmpty()) urlBuilder.append('?').append(query)
        }
        val headers = LinkedHashMap<String, String>().apply {
            put("Authorization", authHeader)
            put("Accept", "application/json")
            if (jsonBody != null) put("Content-Type", "application/json")
        }
        val (_, text) = ErpHttp.execute(
            client = ErpHttp.baseClient,
            url = urlBuilder.toString(),
            method = method,
            headers = headers,
            jsonBody = jsonBody,
        )
        return text
    }

    suspend fun getObject(path: String, params: Map<String, Any?>? = null): JSONObject {
        val text = sendRaw("GET", path, params)
        if (text.isBlank()) throw ErpHttp.unexpectedResponse()
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            throw ErpNetworkError("ERPNext returned an unreadable response.")
        }
    }

    suspend fun postObject(path: String, body: JSONObject): JSONObject? {
        val text = sendRaw("POST", path, null, body.toString())
        if (text.isBlank()) return null
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            throw ErpNetworkError("ERPNext returned an unreadable response.")
        }
    }

    suspend fun delete(path: String) {
        sendRaw("DELETE", path)
    }

    suspend fun callAny(method: String, args: JSONObject = JSONObject()): Any {
        val text = sendRaw("POST", "/api/method/$method", null, args.toString())
        if (text.isBlank()) throw ErpHttp.unexpectedResponse()
        val obj = try {
            JSONObject(text)
        } catch (_: Exception) {
            throw ErpNetworkError("ERPNext returned an unreadable response.")
        }
        if (!obj.has("message") || obj.isNull("message")) throw ErpHttp.unexpectedResponse()
        return obj.get("message")
    }
}
