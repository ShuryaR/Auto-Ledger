package app.onebook.autoledger.inbox

import app.onebook.autoledger.core.AccountMapping
import app.onebook.autoledger.core.AccountType
import app.onebook.autoledger.core.ActionChoice
import app.onebook.autoledger.core.ActionPlan
import app.onebook.autoledger.core.ActionType
import app.onebook.autoledger.core.Direction
import app.onebook.autoledger.core.InvoiceAllocation
import app.onebook.autoledger.core.NewPartyPlan
import app.onebook.autoledger.core.OrderAllocation
import app.onebook.autoledger.core.PartyDoctype
import app.onebook.autoledger.core.PartyRef
import app.onebook.autoledger.core.PendingTxn
import app.onebook.autoledger.core.PlanContext
import app.onebook.autoledger.core.PlanEngine
import app.onebook.autoledger.core.PlanError
import app.onebook.autoledger.erp.ErpDocs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import kotlin.math.roundToLong

data class InvoicePick(
    val doctype: String, // "Sales Invoice" | "Purchase Invoice"
    val name: String,
    val allocatedMinor: Long,
)

data class OrderPick(
    val name: String,
    val allocatedMinor: Long,
)

data class NewParty(
    val ref: PartyRef,
    val fields: Map<String, String>,
    val labels: Map<String, String> = emptyMap(),
)

data class TransferSource(
    val bankAccount: String,
    val glAccount: String,
    val modeOfPayment: String? = null,
)

data class FlowParty(
    val ref: PartyRef,
    val displayName: String? = null,
) {
    val doctype: PartyDoctype get() = ref.doctype
    val name: String get() = ref.name
}

data class FlowDraft(
    val mapping: AccountMapping? = null,
    val action: ActionType? = null,
    val party: FlowParty? = null,
    val newParty: NewParty? = null,
    val invoices: List<InvoicePick>? = null,
    val orders: List<OrderPick>? = null,
    val account: String? = null,
    val toAccount: AccountMapping? = null,
    val fromAccount: TransferSource? = null,
    val suggestedAccount: String? = null,
    val inrAmountMinor: Long? = null,
)

data class FlowSettings(
    val company: String,
    val drawingsAccount: String,
    val today: String,
)

data class PlanExtras(
    val referenceOverride: String? = null,
)

data class AllocationRow(
    val name: String,
    val limitMinor: Long,
    val allocatedMinor: Long,
)

data class AllocationCheck(
    val ok: Boolean,
    val totalMinor: Long,
    val errors: Map<String, String>,
    val total: String? = null,
)

data class ForeignCheck(
    val text: String,
    val warning: String?,
)

data class PlanAndChoice(
    val plan: ActionPlan,
    val choice: ActionChoice,
)

data class FlowStoreState(
    val drafts: Map<String, FlowDraft> = emptyMap(),
    val sending: Set<String> = emptySet(),
    val flash: String? = null,
)

class FlowStore {
    private val _state = MutableStateFlow(FlowStoreState())
    val state: StateFlow<FlowStoreState> = _state.asStateFlow()

    fun draftOf(id: String): FlowDraft = _state.value.drafts[id] ?: FlowDraft()

    fun isSending(id: String): Boolean = id in _state.value.sending

    fun start(id: String, draft: FlowDraft) {
        _state.update { it.copy(drafts = it.drafts + (id to draft)) }
    }

    fun patch(id: String, transform: (FlowDraft) -> FlowDraft) {
        _state.update { cur ->
            val prev = cur.drafts[id] ?: FlowDraft()
            cur.copy(drafts = cur.drafts + (id to transform(prev)))
        }
    }

    fun beginSend(id: String) {
        _state.update { it.copy(sending = it.sending + id) }
    }

    fun endSend(id: String) {
        _state.update { it.copy(sending = it.sending - id) }
    }

    fun clear(id: String) {
        _state.update { it.copy(drafts = it.drafts - id) }
    }

    fun clearAll() {
        _state.update { it.copy(drafts = emptyMap(), flash = null) }
    }

    fun setFlash(message: String?) {
        _state.update { it.copy(flash = message) }
    }
}

object FlowLogic {
    const val IGNORE_NOTE = "Nothing will be added to ERPNext. AutoLedger will not ask about this message again."
    const val ORDERS_OVER_PAYMENT = "That is more than this payment. Lower the amount for an order."

    private val NAME_FIELD = mapOf(
        PartyDoctype.CUSTOMER to "customer_name",
        PartyDoctype.SUPPLIER to "supplier_name",
        PartyDoctype.EMPLOYEE to "first_name",
    )

    private val HIDDEN_FIELDS = setOf(
        "customer_name",
        "supplier_name",
        "first_name",
        "employee_name",
        "naming_series",
    )

    private val LAYOUT_TYPES = setOf(
        "Section Break",
        "Column Break",
        "Tab Break",
        "HTML",
        "Button",
        "Heading",
        "Table",
        "Table MultiSelect",
        "Fold",
    )

    val PLAUSIBLE_INR_PER_UNIT: Map<String, Pair<Int, Int>> = mapOf(
        "USD" to (60 to 120),
        "EUR" to (70 to 140),
        "GBP" to (80 to 160),
        "AED" to (15 to 35),
        "SGD" to (50 to 100),
        "AUD" to (40 to 90),
        "CAD" to (45 to 95),
    )

    fun parseRupees(text: String): Long? {
        val s = text.replace(Regex("[,\\s\u20B9]"), "")
        val m = Regex("^(\\d+)(?:\\.(\\d{0,2}))?$").matchEntire(s) ?: return null
        val whole = m.groupValues[1].toLongOrNull() ?: return null
        val frac = m.groupValues[2].padEnd(2, '0').toLongOrNull() ?: return null
        if (whole > (Long.MAX_VALUE - frac) / 100L) return null
        val minor = whole * 100L + frac
        return minor.takeIf { it > 0L }
    }

    fun toPaise(minor: Long): String {
        val absVal = kotlin.math.abs(minor)
        return "${absVal / 100L}.${(absVal % 100L).toString().padStart(2, '0')}"
    }

    fun effectiveAmountMinor(txn: PendingTxn, inrAmountMinor: Long?): Long? =
        if (txn.currency == "INR") txn.amountMinor else inrAmountMinor

    fun extraFields(required: List<ErpDocs.RequiredFieldInfo>): List<ErpDocs.RequiredFieldInfo> =
        required.filter { it.fieldname !in HIDDEN_FIELDS && it.fieldtype !in LAYOUT_TYPES }

    fun buildNewParty(
        doctype: PartyDoctype,
        name: String,
        extra: Map<String, String>,
        required: List<ErpDocs.RequiredFieldInfo> = emptyList(),
    ): NewParty {
        val trimmed = name.trim()
        val fields = LinkedHashMap<String, String>()
        fields[NAME_FIELD[doctype]!!] = trimmed
        val labels = LinkedHashMap<String, String>()
        for ((k, v) in extra) {
            val tv = v.trim()
            if (tv.isEmpty()) continue
            fields[k] = tv
            val lbl = required.firstOrNull { it.fieldname == k }?.label
            if (lbl != null) labels[k] = lbl
        }
        return NewParty(ref = PartyRef(doctype, trimmed), fields = fields, labels = labels)
    }

    fun validateNewParty(
        name: String,
        extra: Map<String, String>,
        required: List<ErpDocs.RequiredFieldInfo>,
    ): Map<String, String> {
        val errors = LinkedHashMap<String, String>()
        if (name.trim().isEmpty()) errors["name"] = "Enter a name."
        val dateRegex = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        for (f in extraFields(required)) {
            val v = (extra[f.fieldname] ?: "").trim()
            if (v.isEmpty()) {
                errors[f.fieldname] = "ERPNext needs ${f.label}."
            } else if (f.fieldtype == "Date" && !dateRegex.matches(v)) {
                errors[f.fieldname] = "Enter the date as YYYY-MM-DD, for example 1990-01-31."
            }
        }
        return errors
    }

    fun buildChoice(txn: PendingTxn, d: FlowDraft): ActionChoice {
        val action = d.action ?: throw PlanError("missing_choice")
        return when (action) {
            ActionType.CUSTOMER_ADVANCE -> {
                val p = d.party?.ref ?: throw PlanError("missing_choice")
                val orders = d.orders.orEmpty().map { OrderAllocation(it.name, it.allocatedMinor) }
                ActionChoice.CustomerAdvance(party = p, orders = orders)
            }
            ActionType.SUPPLIER_PAYMENT -> {
                val p = d.party?.ref ?: throw PlanError("missing_choice")
                ActionChoice.SupplierPayment(party = p)
            }
            ActionType.SALARY -> {
                val p = d.party?.ref ?: throw PlanError("missing_choice")
                ActionChoice.Salary(party = p)
            }
            ActionType.AGAINST_INVOICE -> {
                val p = d.party?.ref ?: throw PlanError("missing_choice")
                val invs = d.invoices.orEmpty()
                if (invs.isEmpty()) throw PlanError("missing_choice")
                ActionChoice.AgainstInvoice(
                    party = p,
                    invoices = invs.map { InvoiceAllocation(it.doctype, it.name, it.allocatedMinor) },
                )
            }
            ActionType.BILL_PAID_BY_CARD -> {
                val p = d.party?.ref ?: throw PlanError("missing_choice")
                val invs = d.invoices.orEmpty()
                if (invs.size != 1) throw PlanError("missing_choice")
                val inv = invs[0]
                ActionChoice.BillPaidByCard(
                    party = p,
                    invoice = InvoiceAllocation(inv.doctype, inv.name, inv.allocatedMinor),
                )
            }
            ActionType.EXPENSE -> {
                val acc = d.account ?: throw PlanError("missing_choice")
                ActionChoice.Expense(expenseAccount = acc, party = d.party?.ref)
            }
            ActionType.OTHER_INCOME -> {
                val acc = d.account ?: throw PlanError("missing_choice")
                ActionChoice.OtherIncome(incomeAccount = acc)
            }
            ActionType.TRANSFER -> {
                if (txn.direction == Direction.IN) {
                    val src = d.fromAccount ?: throw PlanError("missing_choice")
                    ActionChoice.Transfer.Inbound(
                        fromAccount = AccountMapping(
                            bankAccount = src.bankAccount,
                            glAccount = src.glAccount,
                            kind = AccountType.BANK,
                            senderIds = emptyList(),
                            last4 = emptyList(),
                            modeOfPayment = src.modeOfPayment ?: "",
                        )
                    )
                } else {
                    val dest = d.toAccount ?: throw PlanError("missing_choice")
                    ActionChoice.Transfer.Outbound(toAccount = dest)
                }
            }
            ActionType.PERSONAL -> ActionChoice.Personal
            ActionType.IGNORE -> ActionChoice.Ignore
        }
    }

    fun buildContext(
        txn: PendingTxn,
        d: FlowDraft,
        s: FlowSettings,
        extra: PlanExtras = PlanExtras(),
    ): PlanContext {
        val mapping = d.mapping ?: throw PlanError("missing_account")
        if (s.company.isBlank()) throw PlanError("missing_company")
        val newPartyPlan = d.newParty?.let { np ->
            val fieldsObj = JSONObject()
            for ((k, v) in np.fields) fieldsObj.put(k, v)
            NewPartyPlan(ref = np.ref, fieldsJson = fieldsObj.toString())
        }
        return PlanContext(
            company = s.company,
            account = mapping,
            drawingsAccount = s.drawingsAccount,
            inrAmountMinor = if (txn.currency != "INR") d.inrAmountMinor else null,
            today = s.today,
            newParty = newPartyPlan,
            referenceOverride = if (d.action == ActionType.TRANSFER && !extra.referenceOverride.isNullOrBlank()) {
                extra.referenceOverride
            } else {
                null
            },
        )
    }

    fun planFor(
        txn: PendingTxn,
        d: FlowDraft,
        s: FlowSettings,
        extra: PlanExtras = PlanExtras(),
    ): PlanAndChoice {
        if (d.action == ActionType.IGNORE) {
            return PlanAndChoice(
                plan = ActionPlan(steps = emptyList(), summary = listOf(IGNORE_NOTE), needsInrAmount = false),
                choice = ActionChoice.Ignore,
            )
        }
        val ctx = buildContext(txn, d, s, extra)
        val choice = buildChoice(txn, d)
        if (choice is ActionChoice.Personal && s.drawingsAccount.isBlank()) {
            throw PlanError("missing_drawings")
        }
        val plan = PlanEngine.planAction(txn, choice, ctx)
        if (plan.needsInrAmount) throw PlanError("missing_amount")
        return PlanAndChoice(plan = plan, choice = choice)
    }

    fun defaultAllocation(amountMinor: Long, limitMinor: Long, otherAllocations: List<Long>): Long {
        val left = amountMinor - otherAllocations.sum()
        return maxOf(0L, minOf(limitMinor, left))
    }

    fun validateAllocations(amountMinor: Long, rows: List<AllocationRow>): AllocationCheck {
        val errors = LinkedHashMap<String, String>()
        var totalMinor = 0L
        for (r in rows) {
            totalMinor += r.allocatedMinor
            if (r.allocatedMinor <= 0L) {
                errors[r.name] = "Enter an amount above zero."
            } else if (r.allocatedMinor > r.limitMinor) {
                errors[r.name] = "More than the ${Formatters.formatInr(r.limitMinor)} still due on ${r.name}."
            }
        }
        val totalErr = when {
            rows.isEmpty() -> "Choose at least one invoice."
            totalMinor > amountMinor ->
                "You allocated ${Formatters.formatInr(totalMinor)}, more than the ${Formatters.formatInr(amountMinor)} of this transaction. Lower an amount."
            else -> null
        }
        return AllocationCheck(
            ok = totalErr == null && errors.isEmpty(),
            totalMinor = totalMinor,
            errors = errors,
            total = totalErr,
        )
    }

    fun chooseOrders(
        amountMinor: Long,
        rows: List<AllocationRow>,
    ): Pair<AllocationCheck, List<OrderPick>?> {
        val errors = LinkedHashMap<String, String>()
        var totalMinor = 0L
        for (r in rows) {
            totalMinor += r.allocatedMinor
            if (r.allocatedMinor <= 0L) {
                errors[r.name] = "Enter an amount above zero."
            } else if (r.allocatedMinor > r.limitMinor) {
                errors[r.name] = "More than the ${Formatters.formatInr(r.limitMinor)} left to pay on this order."
            }
        }
        val totalErr = when {
            rows.isEmpty() -> "Choose an order, or tap Skip if this is not for an order."
            totalMinor > amountMinor -> ORDERS_OVER_PAYMENT
            else -> null
        }
        val check = AllocationCheck(
            ok = totalErr == null && errors.isEmpty(),
            totalMinor = totalMinor,
            errors = errors,
            total = totalErr,
        )
        val picked = if (check.ok) rows.map { OrderPick(it.name, it.allocatedMinor) } else null
        return check to picked
    }

    fun skipOrders(): List<OrderPick> = emptyList()

    fun foreignAmountCheck(amountMinor: Long, currency: String, inrMinor: Long): ForeignCheck {
        val ratePaise = ((inrMinor.toDouble() * 100.0) / amountMinor.toDouble()).roundToLong()
        val text = "${Formatters.plainAmount(amountMinor, currency)}, entered as ${Formatters.formatInr(inrMinor)} (${Formatters.formatInr(ratePaise)} per $currency)"
        val band = PLAUSIBLE_INR_PER_UNIT[currency]
        val slip = band != null && (ratePaise < band.first * 100L || ratePaise > band.second * 100L)
        val warning = if (slip) {
            "That is ${Formatters.formatInr(ratePaise)} per $currency, which looks unusual. Check the rupee amount on your card statement before creating."
        } else {
            null
        }
        return ForeignCheck(text = text, warning = warning)
    }
}
