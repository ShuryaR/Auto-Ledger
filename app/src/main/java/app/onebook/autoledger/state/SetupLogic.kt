package app.onebook.autoledger.state

import app.onebook.autoledger.core.AccountMapping
import app.onebook.autoledger.core.AccountType
import app.onebook.autoledger.erp.ErpDocs
import app.onebook.autoledger.ingest.BackfillResult
import app.onebook.autoledger.ingest.IngestPipeline

data class AccountDraft(
    val bankAccount: String,
    val glAccount: String,
    val supported: Boolean,
    val selected: Boolean,
    val saved: Boolean,
    val kind: AccountType,
    val senders: String,
    val last4: String,
    val modeOfPayment: String,
)

sealed interface Last4Result {
    data class Ok(val value: List<String>) : Last4Result
    data class Err(val error: String) : Last4Result
}

data class ValidateDraftsResult(
    val mappings: List<AccountMapping>,
    val errors: Map<String, String>,
    val none: Boolean,
)

data class BackfillOption(
    val days: Int,
    val label: String,
)

object SetupLogic {
    const val IDFC_SENDER = "IDFCFB"
    const val NOT_SUPPORTED = "This bank is not supported yet"
    const val TRUNCATED_NOTICE = "Showing the newest 5,000 messages. Older ones were not read."

    val BACKFILL_OPTIONS = listOf(
        BackfillOption(7, "1 week"),
        BackfillOption(30, "1 month"),
        BackfillOption(90, "3 months"),
        BackfillOption(180, "6 months"),
        BackfillOption(IngestPipeline.ALL_TIME_DAYS, "All time"),
    )

    fun parseLast4(text: String): Last4Result {
        val parts = text.split(Regex("[,\\s]+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) {
            return Last4Result.Err("Enter the last 4 digits of the account or card number.")
        }
        val fourDigits = Regex("^\\d{4}$")
        val out = mutableListOf<String>()
        for (p in parts) {
            if (!fourDigits.matches(p)) {
                return Last4Result.Err("Each entry must be exactly 4 digits. Separate several with commas.")
            }
            if (p !in out) out.add(p)
        }
        return Last4Result.Ok(out)
    }

    fun parseSenders(text: String): List<String> =
        text.split(Regex("[,\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun defaultKind(name: String): AccountType =
        if (name.contains("card", ignoreCase = true)) AccountType.CARD else AccountType.BANK

    fun isSupportedBank(name: String): Boolean = name.contains("idfc", ignoreCase = true)

    fun pickDefaultMode(modes: List<ErpDocs.ModeOfPaymentInfo>, kind: AccountType): String {
        if (kind == AccountType.CARD) {
            val card = modes.firstOrNull { it.name.contains("card", ignoreCase = true) }
            if (card != null) return card.name
        }
        return (
            modes.firstOrNull { it.name == "Wire Transfer" }
                ?: modes.firstOrNull { it.type == "Bank" }
                ?: modes.firstOrNull()
            )?.name.orEmpty()
    }

    fun buildDrafts(
        accounts: List<ErpDocs.BankAccountInfo>,
        existing: List<AccountMapping>,
        modes: List<ErpDocs.ModeOfPaymentInfo>,
    ): List<AccountDraft> = accounts.map { a ->
        val m = existing.firstOrNull { it.bankAccount == a.name }
        val supported = isSupportedBank(a.bank ?: a.name)
        if (m != null) {
            AccountDraft(
                bankAccount = a.name,
                glAccount = m.glAccount.ifBlank { a.glAccount },
                supported = true,
                selected = true,
                saved = true,
                kind = m.kind,
                senders = m.senderIds.joinToString(", "),
                last4 = m.last4.joinToString(", "),
                modeOfPayment = m.modeOfPayment,
            )
        } else {
            val kind = defaultKind(a.name)
            AccountDraft(
                bankAccount = a.name,
                glAccount = a.glAccount,
                supported = supported,
                selected = false,
                saved = false,
                kind = kind,
                senders = if (supported) IDFC_SENDER else "",
                last4 = "",
                modeOfPayment = pickDefaultMode(modes, kind),
            )
        }
    }

    fun validateDrafts(drafts: List<AccountDraft>): ValidateDraftsResult {
        val mappings = mutableListOf<AccountMapping>()
        val errors = LinkedHashMap<String, String>()
        val chosen = drafts.filter { it.selected }
        val owner = HashMap<String, String>()
        for (d in chosen) {
            val l4 = parseLast4(d.last4)
            val senderIds = parseSenders(d.senders)
            when {
                l4 is Last4Result.Err -> errors[d.bankAccount] = l4.error
                senderIds.isEmpty() -> errors[d.bankAccount] = "Enter the sender id the bank uses, for example IDFCFB."
                d.modeOfPayment.isBlank() -> errors[d.bankAccount] = "Choose a default mode of payment."
                l4 is Last4Result.Ok -> {
                    val clash = l4.value.firstOrNull { owner.containsKey("${d.kind.wire}:$it") }
                    if (clash != null) {
                        val existingOwner = owner["${d.kind.wire}:$clash"]
                        errors[d.bankAccount] = "Digits $clash are already used by $existingOwner. Each card or account number can belong to one account only."
                    } else {
                        for (x in l4.value) owner["${d.kind.wire}:$x"] = d.bankAccount
                        mappings.add(
                            AccountMapping(
                                bankAccount = d.bankAccount,
                                glAccount = d.glAccount,
                                kind = d.kind,
                                senderIds = senderIds,
                                last4 = l4.value,
                                modeOfPayment = d.modeOfPayment,
                            )
                        )
                    }
                }
            }
        }
        return ValidateDraftsResult(mappings = mappings, errors = errors, none = chosen.isEmpty())
    }

    fun windowDisclosure(days: Int): String {
        if (days == IngestPipeline.ALL_TIME_DAYS) {
            return "It reads all your messages from the banks you set up in the last step, however old, and never messages from anyone else. You can change it later in Settings."
        }
        return "It reads only text messages from the bank senders you set up in the last step, and only from the period you choose below. You can change it later in Settings."
    }

    fun backfillLabel(days: Int): String =
        (BACKFILL_OPTIONS.firstOrNull { it.days == days }
            ?: BACKFILL_OPTIONS.first { it.days == IngestPipeline.DEFAULT_BACKFILL_DAYS }).label

    fun foundMessage(r: BackfillResult): String {
        val n = r.ingest.inserted + r.ingest.enriched + r.ingest.duplicates
        val base = if (n == 0) "No transactions found" else "Found $n ${if (n == 1) "transaction" else "transactions"}"
        return if (r.truncated) "$base. $TRUNCATED_NOTICE" else base
    }
}
