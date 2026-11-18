package app.onebook.autoledger.inbox

import app.onebook.autoledger.core.AccountMapping
import app.onebook.autoledger.core.ActionPlan
import app.onebook.autoledger.core.ActionType
import app.onebook.autoledger.core.Direction
import app.onebook.autoledger.core.OutboxEntry
import app.onebook.autoledger.core.OutboxStatus
import app.onebook.autoledger.core.PendingTxn
import app.onebook.autoledger.core.PlanStep
import app.onebook.autoledger.core.TxnState
import app.onebook.autoledger.data.Repo
import app.onebook.autoledger.ingest.IngestPipeline
import kotlin.math.abs

sealed interface CounterpartLink {
    data object None : CounterpartLink
    data class Linked(
        val reference: String,
        val txnId: String,
        val docName: String?,
        val counterLabel: String,
    ) : CounterpartLink
    data class Several(val count: Int) : CounterpartLink
}

data class TransferLeg(
    val paidFrom: String,
    val paidTo: String,
    val amount: Double,
    val reference: String,
)

data class CounterpartCandidate(
    val txn: PendingTxn,
    val reference: String,
    val docName: String?,
)

object Counterpart {
    const val COUNTERPART_WINDOW_MS = 3L * 86_400_000L
    const val SEVERAL_TRANSFERS_WARNING = "Several matching transfers were found. Check ERPNext for duplicates after creating."

    private fun transferStep(plan: ActionPlan): PlanStep? =
        plan.steps.firstOrNull {
            it.kind == "create_doc" &&
                it.doctype == "Payment Entry" &&
                it.bodyObject().optString("payment_type") == "Internal Transfer"
        }

    fun transferLegOf(plan: ActionPlan): TransferLeg? {
        val s = transferStep(plan) ?: return null
        val b = s.bodyObject()
        val paidFrom = b.optString("paid_from", "").takeIf { it.isNotEmpty() } ?: return null
        val paidTo = b.optString("paid_to", "").takeIf { it.isNotEmpty() } ?: return null
        val reference = b.optString("reference_no", "").takeIf { it.isNotEmpty() } ?: return null
        val amount = b.optDouble("paid_amount", Double.NaN)
        if (amount.isNaN()) return null
        return TransferLeg(paidFrom = paidFrom, paidTo = paidTo, amount = amount, reference = reference)
    }

    fun counterpartCandidates(
        txn: PendingTxn,
        counter: AccountMapping,
        own: TransferLeg,
        txns: List<PendingTxn>,
        items: List<OutboxEntry>,
        mappings: List<AccountMapping>,
    ): List<CounterpartCandidate> {
        if (txn.currency != "INR") return emptyList()
        val byTxn = items.groupBy { it.txnId }
        val out = mutableListOf<CounterpartCandidate>()
        for (t in txns) {
            if (t.id == txn.id || t.direction == txn.direction || t.currency != "INR" || t.amountMinor != txn.amountMinor) continue
            if (abs(t.receivedAt - txn.receivedAt) > COUNTERPART_WINDOW_MS) continue
            if (IngestPipeline.resolveMapping(mappings, t)?.bankAccount != counter.bankAccount) continue
            val mine = byTxn[t.id].orEmpty()
            val answered = t.state == TxnState.CREATED || t.state == TxnState.SUBMITTED
            val item = mine.asReversed().firstOrNull { o ->
                if (answered) o.status == OutboxStatus.DONE else (t.state == TxnState.NEW && o.status == OutboxStatus.PENDING)
            } ?: continue
            val leg = transferLegOf(item.plan) ?: continue
            if (leg.paidFrom != own.paidFrom || leg.paidTo != own.paidTo || leg.amount != own.amount) continue
            var docName: String? = null
            if (answered) {
                val pe = t.createdDocs.firstOrNull { it.doctype == "Payment Entry" }
                if (pe == null || pe.adopted) continue
                docName = pe.name
                val elsewhere = txns.any { o ->
                    o.id != t.id && o.id != txn.id &&
                        o.createdDocs.any { d -> d.doctype == "Payment Entry" && d.name == pe.name }
                }
                if (elsewhere) continue
            }
            val used = items.any { o ->
                o.txnId != t.id && o.txnId != txn.id && transferLegOf(o.plan)?.reference == leg.reference
            }
            if (used) continue
            out.add(CounterpartCandidate(txn = t, reference = leg.reference, docName = docName))
        }
        return out
    }

    suspend fun findCounterpartLeg(
        txn: PendingTxn,
        counter: AccountMapping?,
        ownPlan: ActionPlan,
        repo: Repo,
        mappings: List<AccountMapping>,
    ): CounterpartLink {
        val own = transferLegOf(ownPlan)
        if (counter == null || own == null || txn.currency != "INR") return CounterpartLink.None
        val txns = repo.listPending()
        val items = repo.listActiveOutbox()
        val hits = counterpartCandidates(txn, counter, own, txns, items, mappings)
        if (hits.isEmpty()) return CounterpartLink.None
        if (hits.size > 1) return CounterpartLink.Several(hits.size)
        val h = hits[0]
        return CounterpartLink.Linked(
            reference = h.reference,
            txnId = h.txn.id,
            docName = h.docName,
            counterLabel = Formatters.shortAccount(counter.bankAccount),
        )
    }

    fun counterMappingOf(
        d: FlowDraft,
        direction: Direction,
        mappings: List<AccountMapping>,
    ): AccountMapping? {
        if (d.action != ActionType.TRANSFER) return null
        if (direction == Direction.OUT) return d.toAccount
        val from = d.fromAccount ?: return null
        return mappings.firstOrNull { it.bankAccount == from.bankAccount }
    }
}
