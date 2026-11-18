package app.onebook.autoledger.erp

open class ErpAuthError(
    message: String,
    val status: Int,
    val deniedDoctype: String? = null,
) : RuntimeException(message)

class ErpValidationError(
    message: String,
    val status: Int,
    val missingFields: List<String>? = null,
    val closedPeriod: Boolean = false,
) : RuntimeException(message)

class ErpNetworkError(
    message: String,
    val status: Int? = null,
) : RuntimeException(message)

class TwoFactorRequired : RuntimeException(
    "This account uses two-factor login. Create an API key in ERPNext (User > API Access) and paste it instead."
)

class KeyGenerationNotPermitted : RuntimeException(
    "Your ERPNext user is not allowed to create API keys. Ask an administrator for a key, or sign in as a System Manager."
)

class RotateDeclined : RuntimeException(
    "Sign-in cancelled: your existing API key was left unchanged."
)

class SessionCookieMissing(status: Int) : ErpAuthError(
    "Signed in, but the session cookie was not kept. Try again or paste an API key.",
    status,
)

class NotDraftError(
    val doctype: String,
    val name: String,
    val docstatus: Int,
) : RuntimeException(
    "This entry is no longer a draft in ERPNext, so it cannot be deleted from the app."
)
