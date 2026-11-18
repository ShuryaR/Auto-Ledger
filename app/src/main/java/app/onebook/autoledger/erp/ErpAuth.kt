package app.onebook.autoledger.erp

import app.onebook.autoledger.data.Session
import org.json.JSONObject

object ErpAuth {
    const val LOGOUT_TIMEOUT_MS = 5000L

    private fun rolesOf(doc: JSONObject): List<String> {
        val arr = doc.optJSONArray("roles") ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val role = row.optString("role", "")
            if (role.isNotEmpty()) out.add(role)
        }
        return out
    }

    suspend fun loginWithPassword(
        siteUrl: String,
        username: String,
        password: String,
        allowHttp: Boolean,
        confirmRotate: suspend () -> Boolean,
    ): Session {
        val base = ErpHttp.normalizeSiteUrl(siteUrl, allowHttp = allowHttp)
        val jar = ErpHttp.newSessionCookieJar()
        val cookieClient = ErpHttp.baseClient.newBuilder().cookieJar(jar).build()
        val jsonHeaders = mapOf("Content-Type" to "application/json", "Accept" to "application/json")

        val (loginStatus, loginText) = ErpHttp.execute(
            client = cookieClient,
            url = "$base/api/method/login",
            method = "POST",
            headers = jsonHeaders,
            jsonBody = JSONObject().put("usr", username).put("pwd", password).toString(),
        )
        val loginObj = try {
            JSONObject(loginText)
        } catch (_: Exception) {
            throw ErpAuthError("Unexpected login response", loginStatus)
        }
        if (loginObj.optString("message") != "Logged In") {
            val msgObj = loginObj.optJSONObject("message")
            val is2fa = loginObj.has("verification") || loginObj.has("tmp_id") ||
                (msgObj != null && (msgObj.has("verification") || msgObj.has("tmp_id")))
            if (is2fa) throw TwoFactorRequired()
            throw ErpAuthError("Unexpected login response", loginStatus)
        }

        val docObj: JSONObject = try {
            val (_, userText) = ErpHttp.execute(
                client = cookieClient,
                url = "$base/api/resource/User/${ErpHttp.encodeSegment(username)}",
                method = "GET",
                headers = jsonHeaders,
            )
            JSONObject(userText).optJSONObject("data") ?: throw ErpHttp.unexpectedResponse()
        } catch (e: ErpAuthError) {
            throw SessionCookieMissing(e.status)
        }

        if (docObj.has("api_key") && !docObj.isNull("api_key") && docObj.optString("api_key").isNotEmpty()) {
            if (!confirmRotate()) throw RotateDeclined()
        }

        val keysObj: JSONObject = try {
            val (_, keyText) = ErpHttp.execute(
                client = cookieClient,
                url = "$base/api/method/frappe.core.doctype.user.user.generate_keys",
                method = "POST",
                headers = jsonHeaders,
                jsonBody = JSONObject().put("user", username).toString(),
            )
            JSONObject(keyText).optJSONObject("message") ?: throw ErpHttp.unexpectedResponse()
        } catch (e: ErpAuthError) {
            if (e.status == 403) throw KeyGenerationNotPermitted()
            throw e
        }

        val apiKey = keysObj.optString("api_key", "")
        val apiSecret = keysObj.optString("api_secret", "")
        if (apiKey.isEmpty() || apiSecret.isEmpty()) throw ErpHttp.unexpectedResponse()

        // Best-effort clear session cookie server-side; token auth will be used going forward.
        try {
            ErpHttp.execute(
                client = cookieClient,
                url = "$base/api/method/logout",
                method = "POST",
                headers = jsonHeaders,
                jsonBody = "{}",
                timeoutMs = LOGOUT_TIMEOUT_MS,
            )
        } catch (_: Throwable) {
            // Best-effort only.
        }

        return Session(
            siteUrl = base,
            apiKey = apiKey,
            apiSecret = apiSecret,
            user = username,
            roles = rolesOf(docObj),
        )
    }

    suspend fun loginWithKeys(
        siteUrl: String,
        apiKey: String,
        apiSecret: String,
        allowHttp: Boolean,
    ): Session {
        val base = ErpHttp.normalizeSiteUrl(siteUrl, allowHttp = allowHttp)
        val client = ErpClient(siteUrl = base, apiKey = apiKey, apiSecret = apiSecret, allowHttp = allowHttp)
        val r = client.getObject("/api/method/frappe.auth.get_logged_user")
        val user = r.opt("message") as? String
        if (user.isNullOrEmpty()) throw ErpHttp.unexpectedResponse()
        var roles: List<String> = emptyList()
        try {
            val d = client.getObject("/api/resource/User/${ErpHttp.encodeSegment(user)}")
            val doc = d.optJSONObject("data")
            if (doc != null) roles = rolesOf(doc)
        } catch (_: ErpAuthError) {
            // Optional for restricted users
        }
        return Session(
            siteUrl = base,
            apiKey = apiKey,
            apiSecret = apiSecret,
            user = user,
            roles = roles,
        )
    }

    suspend fun logoutSession(siteUrl: String, allowHttp: Boolean) {
        try {
            val base = ErpHttp.normalizeSiteUrl(siteUrl, allowHttp = allowHttp)
            ErpHttp.execute(
                client = ErpHttp.baseClient,
                url = "$base/api/method/logout",
                method = "POST",
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                jsonBody = "{}",
                timeoutMs = LOGOUT_TIMEOUT_MS,
            )
        } catch (_: Throwable) {
            // Best-effort logout
        }
    }
}
