package app.onebook.autoledger.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class PlanError(val code: String, message: String = code) : RuntimeException(message)

data class NewPartyPlan(
    val ref: PartyRef,
    val fieldsJson: String,
)

data class PlanContext(
    val company: String,
    val account: AccountMapping,
    val drawingsAccount: String,
    val inrAmountMinor: Long? = null,
    val today: String, // YYYY-MM-DD
    val newParty: NewPartyPlan? = null,
    val referenceOverride: String? = null,
)

object PlanEngine {
    fun txnReference(txn: ParsedTxn): String = txn.utr ?: "AL-${txn.rawHash}"
    fun txnReference(txn: PendingTxn): String = txn.utr ?: "AL-${txn.rawHash}"

    private fun parseIsoDate(iso: String): LocalDate {
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").matchEntire(iso)
            ?: throw PlanError("bad_date", "bad date $iso")
        return try {
            LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        } catch (e: Exception) {
            throw PlanError("bad_date", "bad date $iso")
        }
    }

    fun planAction(txn: PendingTxn, choice: ActionChoice, ctx: PlanContext): ActionPlan {
        if (choice is ActionChoice.Ignore) {
            return ActionPlan(steps = emptyList(), summary = emptyList(), needsInrAmount = false)
        }

        val todayDate = parseIsoDate(ctx.today)
        val occurredDate = parseIsoDate(txn.occurredOn)
        val age = ChronoUnit.DAYS.between(occurredDate, todayDate)
        if (age < -1L || age > 400L) {
            throw PlanError("date_out_of_range", "Transaction date ${txn.occurredOn} is outside the allowed range")
        }

        val dir = txn.direction
        fun need(required: Direction) {
            if (dir != required) {
                throw PlanError("bad_direction", "${choice.type.wire} needs a money-${required.wire} transaction")
            }
        }

        val foreign = txn.currency != "INR"
        if (foreign && ctx.inrAmountMinor == null) {
            return ActionPlan(
                steps = emptyList(),
                summary = listOf("Enter the INR amount for this ${txn.currency} ${Money.formatMoney(txn.amountMinor, false)} transaction"),
                needsInrAmount = true,
            )
        }

        val minor = if (foreign) ctx.inrAmountMinor!! else txn.amountMinor
        val amount = Money.minorToMajorDecimal(minor)
        val ref = ctx.referenceOverride?.takeIf { it.isNotBlank() } ?: txnReference(txn)
        val date = txn.occurredOn
        val who = txn.counterparty ?: txn.merchant
        val remarkParts = mutableListOf("AutoLedger")
        if (!who.isNullOrBlank()) remarkParts.add(who)
        if (foreign) {
            remarkParts.add("${txn.currency} ${Money.formatMoney(txn.amountMinor, false)} (INR value entered by user)")
        }
        val remarks = remarkParts.joinToString(" | ")
        val tail = "UTR/ref $ref, $date"

        fun doc(doctype: String, body: JSONObject): PlanStep =
            PlanStep(kind = "create_doc", doctype = doctype, bodyJson = body.toString())

        fun pe(configure: JSONObject.() -> Unit): PlanStep {
            val obj = JSONObject().apply {
                put("company", ctx.company)
                put("posting_date", date)
                put("mode_of_payment", ctx.account.modeOfPayment)
                put("paid_amount", amount)
                put("received_amount", amount)
                put("reference_no", ref)
                put("reference_date", date)
                put("remarks", remarks)
                configure()
            }
            return doc("Payment Entry", obj)
        }

        fun je(
            debitAccount: String,
            debitParty: PartyRef? = null,
            creditAccount: String,
        ): PlanStep {
            val accounts = JSONArray().apply {
                put(JSONObject().apply {
                    put("account", debitAccount)
                    if (debitParty != null) {
                        put("party_type", debitParty.doctype.wire)
                        put("party", debitParty.name)
                    }
                    put("debit_in_account_currency", amount)
                })
                put(JSONObject().apply {
                    put("account", creditAccount)
                    put("credit_in_account_currency", amount)
                })
            }
            val obj = JSONObject().apply {
                put("company", ctx.company)
                put("posting_date", date)
                put("voucher_type", "Journal Entry")
                put("cheque_no", ref)
                put("cheque_date", date)
                put("user_remark", remarks)
                put("accounts", accounts)
            }
            return doc("Journal Entry", obj)
        }

        val gl = ctx.account.glAccount
        val amt = Money.formatInr(minor)
        val step: PlanStep
        val line: String
        var party: PartyRef? = null

        when (choice) {
            is ActionChoice.CustomerAdvance -> {
                need(Direction.IN)
                val p = choice.party
                party = p
                val orders = choice.orders
                if (orders.isEmpty()) {
                    step = pe {
                        put("payment_type", "Receive")
                        put("party_type", "Customer")
                        put("party", p.name)
                        put("paid_to", gl)
                    }
                    line = "Payment Entry (draft): receive $amt from ${p.name} as advance, $tail"
                } else {
                    val total = orders.sumOf { it.allocatedMinor }
                    if (total > minor) throw PlanError("over_allocated", "Allocated more than the transaction amount")
                    val refs = JSONArray().apply {
                        orders.forEach { o ->
                            put(JSONObject().apply {
                                put("reference_doctype", "Sales Order")
                                put("reference_name", o.name)
                                put("allocated_amount", Money.minorToMajorDecimal(o.allocatedMinor))
                            })
                        }
                    }
                    step = pe {
                        put("payment_type", "Receive")
                        put("party_type", "Customer")
                        put("party", p.name)
                        put("paid_to", gl)
                        put("references", refs)
                    }
                    val forOrders = if (orders.size == 1) {
                        "for order ${orders[0].name}"
                    } else {
                        "for orders " + orders.joinToString(", ") { "${it.name} (${Money.formatInr(it.allocatedMinor)})" }
                    }
                    line = "Payment Entry (draft): receive $amt from ${p.name} as advance $forOrders, $tail"
                }
            }
            is ActionChoice.SupplierPayment -> {
                need(Direction.OUT)
                val p = choice.party
                party = p
                step = pe {
                    put("payment_type", "Pay")
                    put("party_type", "Supplier")
                    put("party", p.name)
                    put("paid_from", gl)
                }
                line = "Payment Entry (draft): pay $amt to ${p.name} (supplier), $tail"
            }
            is ActionChoice.AgainstInvoice -> {
                if (choice.invoices.isEmpty()) throw PlanError("no_invoices")
                val dt = choice.invoices[0].doctype
                if (choice.invoices.any { it.doctype != dt }) throw PlanError("mixed_invoices")
                val receive = dt == "Sales Invoice"
                need(if (receive) Direction.IN else Direction.OUT)
                val total = choice.invoices.sumOf { it.allocatedMinor }
                if (total > minor) throw PlanError("over_allocated", "Allocated more than the transaction amount")
                val p = choice.party
                party = p
                val refs = JSONArray().apply {
                    choice.invoices.forEach { inv ->
                        put(JSONObject().apply {
                            put("reference_doctype", inv.doctype)
                            put("reference_name", inv.name)
                            put("allocated_amount", Money.minorToMajorDecimal(inv.allocatedMinor))
                        })
                    }
                }
                step = if (receive) {
                    pe {
                        put("payment_type", "Receive")
                        put("party_type", "Customer")
                        put("party", p.name)
                        put("paid_to", gl)
                        put("references", refs)
                    }
                } else {
                    pe {
                        put("payment_type", "Pay")
                        put("party_type", "Supplier")
                        put("party", p.name)
                        put("paid_from", gl)
                        put("references", refs)
                    }
                }
                val names = choice.invoices.joinToString(", ") { "${it.name} (${Money.formatInr(it.allocatedMinor)})" }
                line = "Payment Entry (draft): ${if (receive) "receive" else "pay"} $amt ${if (receive) "from" else "to"} ${p.name} against $names, $tail"
            }
            is ActionChoice.BillPaidByCard -> {
                need(Direction.OUT)
                if (choice.invoice.allocatedMinor > minor) {
                    throw PlanError("over_allocated", "Allocated more than the transaction amount")
                }
                val p = choice.party
                party = p
                val refs = JSONArray().apply {
                    put(JSONObject().apply {
                        put("reference_doctype", "Purchase Invoice")
                        put("reference_name", choice.invoice.name)
                        put("allocated_amount", Money.minorToMajorDecimal(choice.invoice.allocatedMinor))
                    })
                }
                step = pe {
                    put("payment_type", "Pay")
                    put("party_type", "Supplier")
                    put("party", p.name)
                    put("paid_from", gl)
                    put("references", refs)
                }
                line = "Payment Entry (draft): pay $amt to ${p.name} by card against ${choice.invoice.name}, $tail"
            }
            is ActionChoice.Expense -> {
                need(Direction.OUT)
                party = choice.party
                step = je(
                    debitAccount = choice.expenseAccount,
                    debitParty = choice.party,
                    creditAccount = gl,
                )
                line = "Journal Entry (draft): expense $amt to ${choice.expenseAccount}" +
                    "${if (choice.party != null) " for ${choice.party.name}" else ""}, paid from $gl, $tail"
            }
            is ActionChoice.Salary -> {
                need(Direction.OUT)
                val p = choice.party
                party = p
                step = pe {
                    put("payment_type", "Pay")
                    put("party_type", "Employee")
                    put("party", p.name)
                    put("paid_from", gl)
                }
                line = "Payment Entry (draft): pay $amt to ${p.name} (employee), $tail"
            }
            is ActionChoice.OtherIncome -> {
                need(Direction.IN)
                step = je(debitAccount = gl, creditAccount = choice.incomeAccount)
                line = "Journal Entry (draft): other income $amt into $gl, credited to ${choice.incomeAccount}, $tail"
            }
            is ActionChoice.Transfer.Inbound -> {
                need(Direction.IN)
                val from = choice.fromAccount
                if (from.glAccount == gl) throw PlanError("same_account", "Cannot transfer from the same account")
                step = pe {
                    put("payment_type", "Internal Transfer")
                    put("paid_from", from.glAccount)
                    put("paid_to", gl)
                    put("mode_of_payment", from.modeOfPayment.ifBlank { ctx.account.modeOfPayment })
                }
                line = "Internal transfer: $amt from ${from.glAccount} into $gl, $tail"
            }
            is ActionChoice.Transfer.Outbound -> {
                need(Direction.OUT)
                val to = choice.toAccount
                if (to.glAccount == gl) throw PlanError("same_account", "Cannot transfer to the same account")
                step = pe {
                    put("payment_type", "Internal Transfer")
                    put("paid_from", gl)
                    put("paid_to", to.glAccount)
                }
                line = "Internal transfer: $amt from $gl into ${to.glAccount}, $tail"
            }
            is ActionChoice.Personal -> {
                step = if (dir == Direction.OUT) {
                    je(debitAccount = ctx.drawingsAccount, creditAccount = gl)
                } else {
                    je(debitAccount = gl, creditAccount = ctx.drawingsAccount)
                }
                val verb = if (dir == Direction.OUT) "withdrawal" else "deposit"
                val prep = if (dir == Direction.OUT) "from" else "into"
                line = "Journal Entry (draft): personal $verb $amt $prep $gl, booked to ${ctx.drawingsAccount}, $tail"
            }
            is ActionChoice.Ignore -> error("unreachable")
        }

        val steps = mutableListOf<PlanStep>()
        val summary = mutableListOf<String>()
        val np = ctx.newParty
        if (np != null && party != null && np.ref == party) {
            steps.add(PlanStep(kind = "create_party", doctype = party.doctype.wire, bodyJson = np.fieldsJson))
            summary.add("New ${party.doctype.wire} will be created: ${party.name}")
        }
        steps.add(step)
        summary.add(line)
        return ActionPlan(steps = steps, summary = summary, needsInrAmount = false)
    }
}
