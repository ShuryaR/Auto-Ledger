package app.onebook.autoledger.core

import org.json.JSONArray
import org.json.JSONObject

enum class AccountType(val wire: String) {
    BANK("bank"),
    CARD("card");

    companion object {
        fun fromWire(value: String?): AccountType = when (value) {
            "card" -> CARD
            else -> BANK
        }
    }
}

enum class Direction(val wire: String) {
    IN("in"),
    OUT("out");

    companion object {
        fun fromWire(value: String?): Direction = when (value) {
            "in" -> IN
            else -> OUT
        }
    }
}

enum class TxnKind(val wire: String) {
    CA_CREDIT("ca_credit"),
    CA_DEBIT("ca_debit"),
    NEFT_CONFIRM("neft_confirm"),
    CARD_SPEND("card_spend"),
    CARD_SI_PAID("card_si_paid");

    companion object {
        fun fromWire(value: String): TxnKind = entries.firstOrNull { it.wire == value }
            ?: throw IllegalArgumentException("Unknown TxnKind: $value")
    }
}

enum class TxnState(val wire: String) {
    NEW("new"),
    CREATED("created"),
    SUBMITTED("submitted"),
    IGNORED("ignored");

    companion object {
        fun fromWire(value: String?): TxnState = entries.firstOrNull { it.wire == value } ?: NEW
    }
}

enum class PartyDoctype(val wire: String) {
    CUSTOMER("Customer"),
    SUPPLIER("Supplier"),
    EMPLOYEE("Employee");

    companion object {
        fun fromWire(value: String): PartyDoctype = entries.firstOrNull { it.wire == value }
            ?: throw IllegalArgumentException("Unknown PartyDoctype: $value")
    }
}

data class PartyRef(
    val doctype: PartyDoctype,
    val name: String,
)

data class ParsedTxn(
    val kind: TxnKind,
    val direction: Direction,
    val amountMinor: Long,
    val currency: String,
    val occurredOn: String,
    val occurredTime: String? = null,
    val accountType: AccountType,
    val accountLast4: String? = null,
    val counterparty: String? = null,
    val utr: String? = null,
    val merchant: String? = null,
    val balanceMinor: Long? = null,
    val sender: String,
    val receivedAt: Long,
    val rawHash: String,
)

data class CreatedDoc(
    val doctype: String,
    val name: String,
    val docstatus: Int? = null, // 0=Draft, 1=Submitted, 2=Cancelled, -1=Gone
    val adopted: Boolean = false,
)

data class PendingTxn(
    val id: String,
    val kind: TxnKind,
    val direction: Direction,
    val amountMinor: Long,
    val currency: String,
    val occurredOn: String,
    val occurredTime: String? = null,
    val accountType: AccountType,
    val accountLast4: String? = null,
    val counterparty: String? = null,
    val utr: String? = null,
    val merchant: String? = null,
    val balanceMinor: Long? = null,
    val sender: String,
    val receivedAt: Long,
    val rawHash: String,
    val state: TxnState = TxnState.NEW,
    val mergedCount: Int = 1,
    val createdDocs: List<CreatedDoc> = emptyList(),
    val error: String? = null,
) {
    fun toParsedTxn(): ParsedTxn = ParsedTxn(
        kind = kind,
        direction = direction,
        amountMinor = amountMinor,
        currency = currency,
        occurredOn = occurredOn,
        occurredTime = occurredTime,
        accountType = accountType,
        accountLast4 = accountLast4,
        counterparty = counterparty,
        utr = utr,
        merchant = merchant,
        balanceMinor = balanceMinor,
        sender = sender,
        receivedAt = receivedAt,
        rawHash = rawHash,
    )
}

data class TxnPatch(
    val kind: TxnKind? = null,
    val occurredOn: String? = null,
    val hasOccurredTime: Boolean = false,
    val occurredTime: String? = null,
    val hasAccountLast4: Boolean = false,
    val accountLast4: String? = null,
    val hasCounterparty: Boolean = false,
    val counterparty: String? = null,
    val hasUtr: Boolean = false,
    val utr: String? = null,
    val hasBalanceMinor: Boolean = false,
    val balanceMinor: Long? = null,
)

sealed interface Correlation {
    data object Insert : Correlation
    data class Enrich(val targetId: String, val patch: TxnPatch) : Correlation
    data class DropDuplicate(val ofId: String, val reason: String) : Correlation
}

data class AccountMapping(
    val bankAccount: String,
    val glAccount: String,
    val kind: AccountType,
    val senderIds: List<String>,
    val last4: List<String>,
    val modeOfPayment: String,
)

data class OpenInvoice(
    val doctype: String, // "Sales Invoice" | "Purchase Invoice"
    val name: String,
    val outstandingMinor: Long,
    val dueDate: String = "",
)

data class OpenOrder(
    val name: String,
    val totalMinor: Long,
    val advanceMinor: Long,
    val remainingMinor: Long,
    val deliveryDate: String? = null,
    val orderDate: String? = null,
)

data class InvoiceAllocation(
    val doctype: String, // "Sales Invoice" | "Purchase Invoice"
    val name: String,
    val allocatedMinor: Long,
)

data class OrderAllocation(
    val name: String,
    val allocatedMinor: Long,
)

enum class ActionType(val wire: String) {
    CUSTOMER_ADVANCE("customer_advance"),
    SUPPLIER_PAYMENT("supplier_payment"),
    AGAINST_INVOICE("against_invoice"),
    BILL_PAID_BY_CARD("bill_paid_by_card"),
    EXPENSE("expense"),
    SALARY("salary"),
    OTHER_INCOME("other_income"),
    TRANSFER("transfer"),
    PERSONAL("personal"),
    IGNORE("ignore");

    companion object {
        fun fromWire(value: String): ActionType = entries.firstOrNull { it.wire == value }
            ?: throw IllegalArgumentException("Unknown ActionType: $value")
    }
}

sealed interface ActionChoice {
    val type: ActionType

    data class CustomerAdvance(
        val party: PartyRef,
        val orders: List<OrderAllocation> = emptyList(),
    ) : ActionChoice {
        override val type = ActionType.CUSTOMER_ADVANCE
    }

    data class SupplierPayment(
        val party: PartyRef,
    ) : ActionChoice {
        override val type = ActionType.SUPPLIER_PAYMENT
    }

    data class AgainstInvoice(
        val party: PartyRef,
        val invoices: List<InvoiceAllocation>,
    ) : ActionChoice {
        override val type = ActionType.AGAINST_INVOICE
    }

    data class BillPaidByCard(
        val party: PartyRef,
        val invoice: InvoiceAllocation, // doctype = "Purchase Invoice"
    ) : ActionChoice {
        override val type = ActionType.BILL_PAID_BY_CARD
    }

    data class Expense(
        val expenseAccount: String,
        val party: PartyRef? = null,
    ) : ActionChoice {
        override val type = ActionType.EXPENSE
    }

    data class Salary(
        val party: PartyRef,
    ) : ActionChoice {
        override val type = ActionType.SALARY
    }

    data class OtherIncome(
        val incomeAccount: String,
    ) : ActionChoice {
        override val type = ActionType.OTHER_INCOME
    }

    sealed interface Transfer : ActionChoice {
        override val type get() = ActionType.TRANSFER

        data class Outbound(val toAccount: AccountMapping) : Transfer
        data class Inbound(val fromAccount: AccountMapping) : Transfer
    }

    data object Personal : ActionChoice {
        override val type = ActionType.PERSONAL
    }

    data object Ignore : ActionChoice {
        override val type = ActionType.IGNORE
    }
}

data class PartialSuggestionChoice(
    val type: ActionType,
    val party: PartyRef? = null,
    val expenseAccount: String? = null,
    val incomeAccount: String? = null,
    val invoices: List<InvoiceAllocation>? = null,
) {
    fun canonicalKey(): String = JSONObject().apply {
        put("type", type.wire)
        party?.let {
            put("party", JSONObject().apply {
                put("doctype", it.doctype.wire)
                put("name", it.name)
            })
        }
        expenseAccount?.let { put("expenseAccount", it) }
        incomeAccount?.let { put("incomeAccount", it) }
        invoices?.let { list ->
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("doctype", it.doctype)
                    put("name", it.name)
                    put("allocatedMinor", it.allocatedMinor)
                })
            }
            put("invoices", arr)
        }
    }.toString()
}

data class Rule(
    val matchKey: String,
    val action: ActionType,
    val party: PartyRef? = null,
    val account: String? = null,
    val hits: Int = 1,
    val lastUsed: Long = 0L,
    val auto: Boolean = false,
    val maxAmountMinor: Long? = null,
    val currency: String? = null,
)

enum class SuggestionReason(val priority: Int) {
    UTR(0),
    RULE(1),
    EXACT_INVOICE_AMOUNT(2),
    NAME_MATCH(3),
}

data class Suggestion(
    val choice: PartialSuggestionChoice,
    val reason: SuggestionReason,
    val confidence: Double,
)

data class PlanStep(
    val kind: String, // "create_party" | "create_doc"
    val doctype: String,
    val bodyJson: String,
) {
    fun bodyObject(): JSONObject = JSONObject(bodyJson)
}

data class ActionPlan(
    val steps: List<PlanStep>,
    val summary: List<String>,
    val needsInrAmount: Boolean,
)

data class ProgressDoc(
    val doctype: String,
    val name: String,
    val adopted: Boolean = false,
)

data class OutboxProgress(
    val stepIndex: Int = 0,
    val created: List<ProgressDoc> = emptyList(),
    val attempting: Int? = null,
)

enum class OutboxStatus(val wire: String) {
    PENDING("pending"),
    DONE("done"),
    FAILED("failed"),
    SUPERSEDED("superseded");

    companion object {
        fun fromWire(value: String?): OutboxStatus = entries.firstOrNull { it.wire == value } ?: PENDING
    }
}

data class OutboxEntry(
    val seq: Long = 0L,
    val id: String,
    val txnId: String,
    val plan: ActionPlan,
    val progress: OutboxProgress = OutboxProgress(),
    val attempts: Int = 0,
    val status: OutboxStatus = OutboxStatus.PENDING,
)
