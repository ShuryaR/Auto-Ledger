package app.onebook.autoledger.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

object SimilarEngine {
    const val SIMILAR_DAYS = 3L
    private val EPOCH = LocalDate.of(1970, 1, 1)

    data class BookedEntry(
        val doctype: String,
        val name: String,
        val postingDate: String,
        val amountMinor: Long,
        val direction: Direction?,
        val party: String?,
        val reference: String?,
        val docstatus: Int, // 0 or 1
    )

    enum class MatchStrength { STRONG, WEAK }
    enum class MatchWhy { REFERENCE, DETAILS }

    data class SimilarHit(
        val entry: BookedEntry,
        val strength: MatchStrength,
        val why: MatchWhy,
    )

    fun dayNumber(iso: String): Long? {
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").matchEntire(iso) ?: return null
        return try {
            val dt = LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            ChronoUnit.DAYS.between(EPOCH, dt)
        } catch (_: Exception) {
            null
        }
    }

    private fun rank(a: SimilarHit, gapA: Long, b: SimilarHit, gapB: Long): Int {
        val refA = if (a.why == MatchWhy.REFERENCE) 0 else 1
        val refB = if (b.why == MatchWhy.REFERENCE) 0 else 1
        if (refA != refB) return refA - refB
        if (gapA != gapB) return if (gapA < gapB) -1 else 1
        val strA = if (a.strength == MatchStrength.STRONG) 0 else 1
        val strB = if (b.strength == MatchStrength.STRONG) 0 else 1
        return strA - strB
    }

    data class Prepared(
        val e: BookedEntry,
        val order: Int,
        val day: Long?,
        val party: String,
        val ref: String,
    )

    class BookedIndex(
        val byAmount: Map<Long, List<Prepared>>,
        val byRef: Map<String, List<Prepared>>,
        var comparisons: Int = 0,
    )

    fun indexBooked(entries: List<BookedEntry>): BookedIndex {
        val byAmount = HashMap<Long, MutableList<Prepared>>()
        val byRef = HashMap<String, MutableList<Prepared>>()
        entries.forEachIndexed { order, e ->
            if (e.docstatus != 0 && e.docstatus != 1) return@forEachIndexed
            val ref = e.reference?.trim().orEmpty()
            val p = Prepared(
                e = e,
                order = order,
                day = dayNumber(e.postingDate),
                party = e.party?.let { SuggestEngine.normalizeKey(it) }.orEmpty(),
                ref = ref,
            )
            byAmount.getOrPut(e.amountMinor) { mutableListOf() }.add(p)
            if (ref.isNotEmpty()) {
                byRef.getOrPut(ref) { mutableListOf() }.add(p)
            }
        }
        return BookedIndex(byAmount = byAmount, byRef = byRef)
    }

    fun findSimilar(
        txn: PendingTxn,
        entries: List<BookedEntry>,
        exclude: ((BookedEntry) -> Boolean)? = null,
    ): SimilarHit? = findSimilarIn(txn, indexBooked(entries), exclude)

    fun findSimilarIn(
        txn: PendingTxn,
        index: BookedIndex,
        exclude: ((BookedEntry) -> Boolean)? = null,
    ): SimilarHit? {
        val utr = txn.utr?.trim().orEmpty()
        val byRef = if (utr.isNotEmpty()) index.byRef[utr].orEmpty() else emptyList()
        val byAmount = index.byAmount[txn.amountMinor].orEmpty()
        if (byRef.isEmpty() && byAmount.isEmpty()) return null

        val candidates: List<Prepared> = when {
            byRef.isEmpty() -> byAmount
            byAmount.isEmpty() -> byRef
            else -> {
                val seen = HashSet<Int>()
                (byRef + byAmount).filter { seen.add(it.order) }.sortedBy { it.order }
            }
        }

        val own = txn.createdDocs.mapTo(HashSet()) { "${it.doctype}\u0000${it.name}" }
        val ownRef = "AL-${txn.rawHash}"
        val names = listOfNotNull(txn.counterparty, txn.merchant)
            .map { SuggestEngine.normalizeKey(it) }
            .filter { it.isNotEmpty() }
        val txnDay = dayNumber(txn.occurredOn)
        val rupees = txn.currency == "INR"

        var bestHit: SimilarHit? = null
        var bestGap = Long.MAX_VALUE

        for (p in candidates) {
            index.comparisons++
            val e = p.e
            if ("${e.doctype}\u0000${e.name}" in own || p.ref == ownRef) continue
            if (e.direction != null && e.direction != txn.direction) continue
            if (exclude?.invoke(e) == true) continue

            val gap = if (txnDay != null && p.day != null) abs(txnDay - p.day) else Long.MAX_VALUE
            val isRef = utr.isNotEmpty() && p.ref == utr
            val strength: MatchStrength? = when {
                isRef -> MatchStrength.STRONG
                rupees && e.amountMinor == txn.amountMinor && gap <= SIMILAR_DAYS -> {
                    when {
                        p.party.isEmpty() || names.isEmpty() -> MatchStrength.WEAK
                        p.party in names -> if (gap == 0L) MatchStrength.STRONG else MatchStrength.WEAK
                        else -> null
                    }
                }
                else -> null
            }
            if (strength == null) continue

            val hit = SimilarHit(entry = e, strength = strength, why = if (isRef) MatchWhy.REFERENCE else MatchWhy.DETAILS)
            if (bestHit == null || rank(hit, gap, bestHit, bestGap) < 0) {
                bestHit = hit
                bestGap = gap
            }
        }
        return bestHit
    }
}
