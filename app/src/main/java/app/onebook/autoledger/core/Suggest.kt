package app.onebook.autoledger.core

import kotlin.math.min
import kotlin.math.round

object SuggestEngine {
    private const val MAX_SUGGESTIONS = 5
    private const val NAME_THRESHOLD = 0.6
    private val LEGAL_SUFFIXES = setOf(
        "pvt", "private", "ltd", "limited", "llp", "inc", "co", "company", "corp", "corporation", "the"
    )
    private val NOT_LEARNED = setOf(
        ActionType.AGAINST_INVOICE, ActionType.BILL_PAID_BY_CARD, ActionType.TRANSFER
    )
    private const val RUPEE = "INR"

    private val NON_LETTER_NUM = Regex("[^\\p{L}\\p{N}\\p{M}]")
    private val HAS_LETTER_NUM = Regex("[\\p{L}\\p{N}]")
    private val TOKEN_SPLIT = Regex("[^\\p{L}\\p{N}\\p{M}\\s]")
    private val WHITESPACE = Regex("\\s+")

    fun normalizeKey(s: String): String {
        val k = s.lowercase().replace(NON_LETTER_NUM, "")
        return if (HAS_LETTER_NUM.containsMatchIn(k)) k else ""
    }

    fun nameKey(txn: PendingTxn): String? {
        val cp = txn.counterparty?.let { normalizeKey(it) }.orEmpty()
        if (cp.isNotEmpty()) return "cp:${txn.direction.wire}:$cp"
        val m = txn.merchant?.let { normalizeKey(it) }.orEmpty()
        return if (m.isNotEmpty()) "m:${txn.direction.wire}:$m" else null
    }

    fun utrKey(txn: PendingTxn): String? = txn.utr?.let { "utr:$it" }

    fun ruleChoice(rule: Rule): PartialSuggestionChoice? {
        when (rule.action) {
            ActionType.CUSTOMER_ADVANCE, ActionType.SUPPLIER_PAYMENT, ActionType.SALARY -> {
                if (rule.party == null) return null
            }
            ActionType.EXPENSE, ActionType.OTHER_INCOME -> {
                if (rule.account == null) return null
            }
            ActionType.PERSONAL, ActionType.IGNORE -> Unit
            else -> return null
        }
        return PartialSuggestionChoice(
            type = rule.action,
            party = rule.party,
            expenseAccount = if (rule.action == ActionType.EXPENSE) rule.account else null,
            incomeAccount = if (rule.action == ActionType.OTHER_INCOME) rule.account else null,
        )
    }

    private fun tokens(s: String): Set<String> {
        val out = LinkedHashSet<String>()
        for (t in s.lowercase().replace(TOKEN_SPLIT, " ").split(WHITESPACE)) {
            if (t.isNotEmpty() && t !in LEGAL_SUFFIXES) out.add(t)
        }
        return out
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var inter = 0
        for (t in a) if (t in b) inter++
        return inter.toDouble() / (a.size + b.size - inter).toDouble()
    }

    private data class Ranked(
        val suggestion: Suggestion,
        val partyName: String,
        val partyDoctype: String,
    )

    fun suggest(
        txn: PendingTxn,
        rules: List<Rule>,
        openInvoices: List<OpenInvoice>,
        parties: List<PartyRef>,
    ): List<Suggestion> {
        val out = mutableListOf<Ranked>()
        val uKey = utrKey(txn)
        val nKey = nameKey(txn)

        for (r in rules) {
            val isUtr = uKey != null && r.matchKey == uKey
            if (!isUtr && !(nKey != null && r.matchKey == nKey)) continue
            val choice = ruleChoice(r) ?: continue
            val reason = if (isUtr) SuggestionReason.UTR else SuggestionReason.RULE
            val confidence = if (isUtr) 1.0 else min(0.99, 0.8 + 0.02 * r.hits)
            out.add(
                Ranked(
                    suggestion = Suggestion(choice = choice, reason = reason, confidence = confidence),
                    partyName = r.party?.name ?: "",
                    partyDoctype = r.party?.doctype?.wire ?: "",
                )
            )
        }

        val credit = txn.direction == Direction.IN
        val wanted = if (credit) "Sales Invoice" else "Purchase Invoice"
        val exact = openInvoices.filter { it.doctype == wanted && it.outstandingMinor == txn.amountMinor }
        if (exact.size == 1) {
            out.add(
                Ranked(
                    suggestion = Suggestion(
                        choice = PartialSuggestionChoice(
                            type = ActionType.AGAINST_INVOICE,
                            invoices = listOf(InvoiceAllocation(wanted, exact[0].name, txn.amountMinor)),
                        ),
                        reason = SuggestionReason.EXACT_INVOICE_AMOUNT,
                        confidence = 0.75,
                    ),
                    partyName = "",
                    partyDoctype = "",
                )
            )
        }

        if (!txn.counterparty.isNullOrBlank()) {
            val cpTokens = tokens(txn.counterparty)
            for (p in parties) {
                if (if (credit) p.doctype != PartyDoctype.CUSTOMER else p.doctype == PartyDoctype.CUSTOMER) continue
                val score = jaccard(cpTokens, tokens(p.name))
                if (score < NAME_THRESHOLD) continue
                val type = when {
                    credit -> ActionType.CUSTOMER_ADVANCE
                    p.doctype == PartyDoctype.EMPLOYEE -> ActionType.SALARY
                    else -> ActionType.SUPPLIER_PAYMENT
                }
                val conf = round(score * 0.7 * 100.0) / 100.0
                out.add(
                    Ranked(
                        suggestion = Suggestion(
                            choice = PartialSuggestionChoice(type = type, party = p),
                            reason = SuggestionReason.NAME_MATCH,
                            confidence = conf,
                        ),
                        partyName = p.name,
                        partyDoctype = p.doctype.wire,
                    )
                )
            }
        }

        out.sortWith(
            compareByDescending<Ranked> { it.suggestion.confidence }
                .thenBy { it.suggestion.reason.priority }
                .thenBy { it.partyName }
                .thenBy { it.partyDoctype }
        )

        val seen = HashSet<String>()
        val result = mutableListOf<Suggestion>()
        for (ranked in out) {
            val key = ranked.suggestion.choice.canonicalKey()
            if (!seen.add(key)) continue
            result.add(ranked.suggestion)
            if (result.size == MAX_SUGGESTIONS) break
        }
        return result
    }

    fun learnRule(
        txn: PendingTxn,
        choice: ActionChoice,
        existing: List<Rule>,
        raiseMax: Boolean = true,
    ): List<Rule> {
        if (choice.type in NOT_LEARNED) return existing
        val key = nameKey(txn) ?: utrKey(txn) ?: return existing

        val party: PartyRef? = when (choice) {
            is ActionChoice.CustomerAdvance -> choice.party
            is ActionChoice.SupplierPayment -> choice.party
            is ActionChoice.AgainstInvoice -> choice.party
            is ActionChoice.BillPaidByCard -> choice.party
            is ActionChoice.Expense -> choice.party
            is ActionChoice.Salary -> choice.party
            else -> null
        }
        val account: String? = when (choice) {
            is ActionChoice.Expense -> choice.expenseAccount
            is ActionChoice.OtherIncome -> choice.incomeAccount
            else -> null
        }
        val confirmed = if (raiseMax && txn.currency == RUPEE && txn.amountMinor > 0L) txn.amountMinor else null

        val idx = existing.indexOfFirst { it.matchKey == key && it.action == choice.type }
        val next = existing.toMutableList()
        var hits = 1
        var auto = false
        var maxAmount = confirmed

        if (idx >= 0) {
            val old = existing[idx]
            hits = old.hits + 1
            val same = old.party == party && old.account == account
            if (same) {
                auto = old.auto
                val oldMax = if (old.currency == RUPEE) old.maxAmountMinor else null
                maxAmount = when {
                    oldMax == null -> confirmed
                    confirmed == null -> oldMax
                    else -> maxOf(oldMax, confirmed)
                }
            }
        }

        val rule = Rule(
            matchKey = key,
            action = choice.type,
            party = party,
            account = account,
            hits = hits,
            lastUsed = txn.receivedAt,
            auto = auto,
            maxAmountMinor = maxAmount,
            currency = if (maxAmount != null) RUPEE else null,
        )
        if (idx >= 0) next[idx] = rule else next.add(rule)
        return next
    }
}
