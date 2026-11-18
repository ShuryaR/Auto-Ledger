package app.onebook.autoledger.inbox

import app.onebook.autoledger.core.ActionType
import app.onebook.autoledger.core.Direction
import app.onebook.autoledger.core.PartyRef
import app.onebook.autoledger.core.PendingTxn
import app.onebook.autoledger.core.Suggestion
import app.onebook.autoledger.core.SuggestionReason

enum class ChipTone {
    ACCENT,
    WARN,
    PLAIN,
}

data class ChipView(
    val text: String,
    val tone: ChipTone,
)

data class SuggestionCardText(
    val title: String,
    val subtitle: String,
)

object SuggestionsUi {
    private fun briefly(s: Suggestion, direction: Direction): String {
        val c = s.choice
        return when (c.type) {
            ActionType.CUSTOMER_ADVANCE -> "customer advance"
            ActionType.SUPPLIER_PAYMENT -> "supplier payment"
            ActionType.SALARY -> "salary"
            ActionType.EXPENSE -> Formatters.shortAccount(c.expenseAccount ?: "expense")
            ActionType.OTHER_INCOME -> "other income"
            ActionType.PERSONAL -> "personal"
            ActionType.IGNORE -> "ignore"
            ActionType.AGAINST_INVOICE ->
                if (direction == Direction.IN) "payment against an invoice" else "payment against a bill"
            ActionType.BILL_PAID_BY_CARD -> "bill paid by card"
            ActionType.TRANSFER -> "transfer"
        }
    }

    fun chipFor(txn: PendingTxn, suggestions: List<Suggestion>): ChipView {
        if (txn.currency != "INR") return ChipView("Enter the rupee amount", ChipTone.WARN)
        val top = suggestions.firstOrNull() ?: return ChipView("Who is this?", ChipTone.PLAIN)
        val prefix = if (top.reason == SuggestionReason.RULE) "You did this before" else "Suggested"
        return ChipView("$prefix: ${briefly(top, txn.direction)}", ChipTone.ACCENT)
    }

    fun usableSuggestions(txn: PendingTxn, suggestions: List<Suggestion>, drawings: Boolean): List<Suggestion> {
        val allowed = Actions.actionsFor(txn.direction, txn.accountType).mapTo(HashSet()) { it.type }
        return suggestions.filter { x ->
            x.choice.type in allowed && (x.choice.type != ActionType.PERSONAL || drawings)
        }
    }

    private fun reasonText(r: SuggestionReason): String = when (r) {
        SuggestionReason.RULE -> "You did this before."
        SuggestionReason.UTR -> "Same reference as before."
        SuggestionReason.EXACT_INVOICE_AMOUNT -> "Same amount as an open invoice."
        SuggestionReason.NAME_MATCH -> "Matched by name."
    }

    private fun createsText(t: ActionType): String = when (t) {
        ActionType.IGNORE -> "Nothing is added to ERPNext."
        ActionType.EXPENSE, ActionType.OTHER_INCOME, ActionType.PERSONAL -> "Creates a draft journal entry."
        else -> "Creates a draft payment entry."
    }

    fun suggestionCard(s: Suggestion, party: FlowParty? = null): SuggestionCardText {
        val c = s.choice
        val p = c.party ?: party?.ref
        val who = if (p != null) {
            if (party != null && party.name == p.name && !party.displayName.isNullOrBlank()) party.displayName else p.name
        } else {
            ""
        }
        val title = when (c.type) {
            ActionType.CUSTOMER_ADVANCE -> "Advance from $who"
            ActionType.SUPPLIER_PAYMENT -> "Payment to $who"
            ActionType.SALARY -> "Salary to $who"
            ActionType.EXPENSE -> "Expense: ${Formatters.shortAccount(c.expenseAccount ?: "")}"
            ActionType.OTHER_INCOME -> "Other income: ${Formatters.shortAccount(c.incomeAccount ?: "")}"
            ActionType.PERSONAL -> "Personal"
            ActionType.IGNORE -> "Ignore this message"
            ActionType.AGAINST_INVOICE -> {
                val names = c.invoices.orEmpty().joinToString(", ") { it.name }
                if (who.isNotEmpty()) "$who: against $names" else "Against $names"
            }
            else -> "Suggested answer"
        }
        return SuggestionCardText(title = title, subtitle = "${reasonText(s.reason)} ${createsText(c.type)}")
    }

    fun draftFromSuggestion(s: Suggestion, party: PartyRef? = null): FlowDraft {
        val c = s.choice
        val p = c.party ?: if (c.type == ActionType.AGAINST_INVOICE) party else null
        val acc = c.expenseAccount ?: c.incomeAccount
        val inv = c.invoices?.map { InvoicePick(it.doctype, it.name, it.allocatedMinor) }
        return FlowDraft(
            action = c.type,
            party = p?.let { FlowParty(it) },
            account = acc,
            invoices = inv,
        )
    }

    fun searchTerm(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val words = name.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 && !Regex("^\\d+$").matches(it) }
        return words.maxByOrNull { it.length }
    }
}
