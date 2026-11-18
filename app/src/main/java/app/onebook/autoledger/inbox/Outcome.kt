package app.onebook.autoledger.inbox

import app.onebook.autoledger.core.ActionPlan
import app.onebook.autoledger.core.PlanError
import app.onebook.autoledger.erp.ErpHttp
import app.onebook.autoledger.erp.ErpValidationError
import app.onebook.autoledger.outbox.RunResult

sealed interface Outcome {
    data class Created(val docs: List<Pair<String, String>>) : Outcome
    data object Login : Outcome
    data class Offline(val message: String) : Outcome
    data class Error(
        val message: String,
        val missingFields: List<String> = emptyList(),
        val doctype: String? = null,
        val closedPeriod: Boolean = false,
    ) : Outcome
    data object Busy : Outcome
    data object Gone : Outcome
}

object Outcomes {
    const val UNKNOWN_FAILURE =
        "Something went wrong. Your answer may not have been saved; check the Inbox and Activity before trying again."
    const val GONE_MESSAGE = "This message is no longer in your Inbox."
    const val OFFLINE_MESSAGE = "No connection. Your answer is saved and will be sent when you are back online."

    private val PLAN_MESSAGES = mapOf(
        "date_out_of_range" to "This transaction is dated outside the usual range. Check the date in ERPNext.",
        "bad_direction" to "This answer does not fit the direction of the money. Go back and choose another answer.",
        "over_allocated" to "The amounts add up to more than this transaction. Lower an amount or remove an item.",
        "mixed_invoices" to "Choose only sales invoices or only purchase invoices, not both. Go back and change the invoices.",
        "no_invoices" to "Choose at least one invoice. Go back to the invoice list.",
        "same_account" to "Money cannot move from an account to itself. Go back and choose your other account.",
        "missing_account" to "Choose which of your accounts this message belongs to first. Go back to the transaction.",
        "missing_choice" to "Some answers are missing. Go back to complete them.",
        "missing_drawings" to "Set your Drawings account in Settings first (Run setup again).",
        "missing_company" to "Choose your company in Settings first (Run setup again).",
        "missing_amount" to "Enter the amount in rupees first. Go back to the amount step.",
        "bad_amount" to "The amount is not valid. Go back and enter it again.",
        "bad_date" to "The date in the bank message could not be read. Create this entry in ERPNext instead.",
    )
    private const val GENERIC = "This answer could not be prepared. Go back and check it."

    fun planErrorMessage(e: Throwable): String {
        if (e is PlanError) return PLAN_MESSAGES[e.code] ?: GENERIC
        return GENERIC
    }

    fun readableFailure(error: Exception, txnError: String?): String? {
        if (txnError.isNullOrBlank()) return null
        val valErr = error as? ErpValidationError
        if (valErr != null) {
            if (!valErr.missingFields.isNullOrEmpty()) {
                return if (txnError.contains(valErr.missingFields.joinToString(", "))) txnError else null
            }
            if (valErr.closedPeriod) {
                return if (txnError.contains("accounting period is closed")) txnError else null
            }
        }
        val msg = error.message?.trim().orEmpty()
        return if (msg.isNotEmpty() && txnError.contains(msg)) txnError else null
    }

    fun failedDoctype(message: String, plan: ActionPlan): String? {
        for (s in plan.steps) {
            val what = if (s.kind == "create_party") "the new ${s.doctype}" else "the ${s.doctype}"
            if (message.contains("Could not create $what.")) return s.doctype
        }
        return plan.steps.firstOrNull { it.kind == "create_doc" }?.doctype
    }

    fun outcomeOf(r: RunResult, readable: String?, plan: ActionPlan): Outcome = when (r) {
        is RunResult.Created -> Outcome.Created(r.docs)
        RunResult.NeedsLogin -> Outcome.Login
        is RunResult.Failed -> {
            if (r.retryable) {
                Outcome.Offline(OFFLINE_MESSAGE)
            } else {
                val valErr = r.error as? ErpValidationError
                val message = readable ?: r.error.message ?: UNKNOWN_FAILURE
                Outcome.Error(
                    message = message,
                    missingFields = valErr?.missingFields.orEmpty(),
                    doctype = failedDoctype(message, plan),
                    closedPeriod = valErr?.closedPeriod == true,
                )
            }
        }
    }

    private fun slug(doctype: String): String =
        doctype.trim().lowercase().replace(Regex("\\s+"), "-")

    fun erpDocUrl(site: String, doctype: String, name: String): String =
        "${site.trimEnd('/')}/app/${slug(doctype)}/${ErpHttp.encodeSegment(name)}"

    fun erpNewDocUrl(site: String, doctype: String): String =
        "${site.trimEnd('/')}/app/${slug(doctype)}/new"
}
