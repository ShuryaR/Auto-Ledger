package app.onebook.autoledger.state

import app.onebook.autoledger.erp.ErpAuthError
import app.onebook.autoledger.erp.ErpHttp
import app.onebook.autoledger.erp.ErpNetworkError
import app.onebook.autoledger.erp.KeyGenerationNotPermitted
import app.onebook.autoledger.erp.RotateDeclined
import app.onebook.autoledger.erp.SessionCookieMissing
import app.onebook.autoledger.erp.TwoFactorRequired

data class LoginProblem(
    val message: String,
    val offerKeys: Boolean = false,
    val switchToKeys: Boolean = false,
    val hint: Boolean = false,
)

sealed interface CheckSiteUrlResult {
    data class Ok(val url: String) : CheckSiteUrlResult
    data class Invalid(val message: String) : CheckSiteUrlResult
}

object LoginErrors {
    fun describeLoginError(e: Throwable): LoginProblem = when (e) {
        is TwoFactorRequired -> LoginProblem(
            message = "This account uses two-factor sign-in, which password sign-in does not support. Sign in with an API key instead.",
            offerKeys = true,
        )
        is KeyGenerationNotPermitted -> LoginProblem(
            message = "This user cannot create an API key. Ask an administrator to generate one in ERPNext (open the user, then API Access, then Generate Keys), then paste the API key and secret here.",
            offerKeys = true,
            switchToKeys = true,
        )
        is RotateDeclined -> LoginProblem(
            message = "Nothing was changed. To keep your existing API key working, sign in with a dedicated \"AutoLedger\" user instead.",
            offerKeys = false,
            hint = true,
        )
        is SessionCookieMissing -> LoginProblem(
            message = "You signed in, but the session cookie was not kept. Try again, or sign in with an API key.",
            offerKeys = true,
        )
        is ErpAuthError -> LoginProblem(
            message = "Wrong username or password, or this user has no permission to sign in.",
            offerKeys = false,
        )
        is ErpNetworkError -> LoginProblem(
            message = "Cannot reach the site. Check the address and your internet connection.",
            offerKeys = false,
        )
        else -> LoginProblem(
            message = "Sign-in failed. Check the details and try again.",
            offerKeys = false,
        )
    }

    fun checkSiteUrl(input: String, allowHttp: Boolean): CheckSiteUrlResult {
        return try {
            CheckSiteUrlResult.Ok(ErpHttp.normalizeSiteUrl(input, allowHttp = allowHttp))
        } catch (e: Throwable) {
            val m = e.message.orEmpty()
            if (m == "Use an https:// address.") {
                CheckSiteUrlResult.Invalid("Use an https:// address. Plain http:// is only allowed in test builds.")
            } else {
                CheckSiteUrlResult.Invalid(m.ifEmpty { "That does not look like a valid site address." })
            }
        }
    }
}
