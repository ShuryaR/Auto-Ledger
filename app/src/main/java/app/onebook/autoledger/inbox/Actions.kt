package app.onebook.autoledger.inbox

import app.onebook.autoledger.core.AccountType
import app.onebook.autoledger.core.ActionType
import app.onebook.autoledger.core.Direction
import app.onebook.autoledger.core.PartyDoctype
import app.onebook.autoledger.core.PendingTxn

data class ActionItem(
    val type: ActionType,
    val label: String,
    val hint: String,
)

enum class Step {
    CHOOSE,
    AMOUNT,
    PARTY,
    ORDER,
    INVOICES,
    ACCOUNT,
    CONFIRM,
}

object Actions {
    private const val PE = "Creates a draft payment entry."
    private const val JE = "Creates a draft journal entry."

    private val CREDIT = listOf(
        ActionItem(ActionType.CUSTOMER_ADVANCE, "Customer advance", "Money from a customer before an invoice. $PE"),
        ActionItem(ActionType.AGAINST_INVOICE, "Payment against an invoice", "A customer paid one or more open invoices. $PE"),
        ActionItem(ActionType.OTHER_INCOME, "Other income", "Interest, refunds and other income. $JE"),
        ActionItem(ActionType.TRANSFER, "Transfer from my other account", "Money moved in from another of your accounts. $PE"),
        ActionItem(ActionType.PERSONAL, "Personal", "Your own money put into the business. $JE"),
        ActionItem(ActionType.IGNORE, "Ignore this message", "Nothing is added to ERPNext."),
    )

    private val BANK_DEBIT = listOf(
        ActionItem(ActionType.SUPPLIER_PAYMENT, "Supplier payment", "A payment or advance to a supplier. $PE"),
        ActionItem(ActionType.AGAINST_INVOICE, "Payment against a bill", "You paid one or more open supplier bills. $PE"),
        ActionItem(ActionType.EXPENSE, "Expense", "A cost with no bill in ERPNext. $JE"),
        ActionItem(ActionType.SALARY, "Salary/Employee payment", "Salary or other money paid to an employee. $PE"),
        ActionItem(ActionType.TRANSFER, "Transfer between my accounts", "Including paying the credit card bill. $PE"),
        ActionItem(ActionType.PERSONAL, "Personal", "Money you took out for yourself. $JE"),
        ActionItem(ActionType.IGNORE, "Ignore this message", "Nothing is added to ERPNext."),
    )

    private val CARD_DEBIT = listOf(
        ActionItem(ActionType.EXPENSE, "Expense", "A card spend with no bill in ERPNext. $JE"),
        ActionItem(ActionType.BILL_PAID_BY_CARD, "Bill paid by card", "You paid an open supplier bill with the card. $PE"),
        ActionItem(ActionType.PERSONAL, "Personal", "A personal spend on the business card. $JE"),
        ActionItem(ActionType.IGNORE, "Ignore this message", "Nothing is added to ERPNext."),
    )

    private val NOT_REMEMBERED = setOf(
        ActionType.AGAINST_INVOICE,
        ActionType.BILL_PAID_BY_CARD,
        ActionType.TRANSFER,
    )

    fun actionsFor(direction: Direction, kind: AccountType): List<ActionItem> {
        if (direction == Direction.IN) return CREDIT
        return if (kind == AccountType.CARD) CARD_DEBIT else BANK_DEBIT
    }

    fun actionLabel(type: ActionType, direction: Direction, kind: AccountType): String =
        actionsFor(direction, kind).firstOrNull { it.type == type }?.label ?: type.wire

    fun partyDoctypeFor(type: ActionType, direction: Direction): PartyDoctype? = when (type) {
        ActionType.CUSTOMER_ADVANCE -> PartyDoctype.CUSTOMER
        ActionType.AGAINST_INVOICE -> if (direction == Direction.IN) PartyDoctype.CUSTOMER else PartyDoctype.SUPPLIER
        ActionType.SUPPLIER_PAYMENT, ActionType.BILL_PAID_BY_CARD, ActionType.EXPENSE -> PartyDoctype.SUPPLIER
        ActionType.SALARY -> PartyDoctype.EMPLOYEE
        else -> null
    }

    fun nextStep(txn: PendingTxn, d: FlowDraft): Step {
        val a = d.action ?: return Step.CHOOSE
        if (a != ActionType.IGNORE && txn.currency != "INR" && d.inrAmountMinor == null) return Step.AMOUNT
        return when (a) {
            ActionType.CUSTOMER_ADVANCE -> {
                if (d.party == null) Step.PARTY
                else if (d.newParty != null || d.orders != null) Step.CONFIRM
                else Step.ORDER
            }
            ActionType.SUPPLIER_PAYMENT, ActionType.SALARY -> if (d.party != null) Step.CONFIRM else Step.PARTY
            ActionType.AGAINST_INVOICE, ActionType.BILL_PAID_BY_CARD -> {
                if (d.party == null) Step.PARTY
                else if (!d.invoices.isNullOrEmpty()) Step.CONFIRM
                else Step.INVOICES
            }
            ActionType.EXPENSE, ActionType.OTHER_INCOME -> if (d.account != null) Step.CONFIRM else Step.ACCOUNT
            ActionType.TRANSFER -> {
                val chosen = if (txn.direction == Direction.IN) d.fromAccount != null else d.toAccount != null
                if (chosen) Step.CONFIRM else Step.ACCOUNT
            }
            else -> Step.CONFIRM
        }
    }

    fun rememberShown(t: ActionType): Boolean = t !in NOT_REMEMBERED

    fun rememberDefault(t: ActionType): Boolean = rememberShown(t)
}
