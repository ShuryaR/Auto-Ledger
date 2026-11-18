package app.onebook.autoledger.core

import kotlin.math.abs

object Correlate {
    private const val NEFT_WINDOW_MS = 48L * 3_600_000L
    private const val CARD_WINDOW_MS = 2L * 24L * 3_600_000L
    private const val MIN_MERCHANT_LEN = 5
    private const val MIN_COMMON_PREFIX = 5

    private fun norm(s: String?): String =
        (s ?: "").lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun commonPrefix(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return i
    }

    private fun sameMerchant(a: String?, b: String?): Boolean {
        val x = norm(a)
        val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        val (short, long) = if (x.length <= y.length) x to y else y to x
        if (short.length >= MIN_MERCHANT_LEN && long.contains(short)) return true
        return commonPrefix(x, y) >= MIN_COMMON_PREFIX
    }

    private fun gap(a: PendingTxn, b: ParsedTxn): Long = abs(a.receivedAt - b.receivedAt)

    private fun pick(cands: List<PendingTxn>, inc: ParsedTxn): PendingTxn? =
        cands.sortedWith(compareBy<PendingTxn> { gap(it, inc) }.thenBy { it.id }).firstOrNull()

    private fun findNeftPartner(existing: List<PendingTxn>, inc: ParsedTxn): PendingTxn? {
        val partnerKind = when (inc.kind) {
            TxnKind.NEFT_CONFIRM -> TxnKind.CA_DEBIT
            TxnKind.CA_DEBIT -> TxnKind.NEFT_CONFIRM
            else -> return null
        }
        val pool = existing.filter {
            it.kind == partnerKind &&
                it.mergedCount == 1 &&
                it.direction == inc.direction &&
                it.accountType == inc.accountType
        }
        val byUtr = if (inc.utr != null) pick(pool.filter { it.utr == inc.utr }, inc) else null
        if (byUtr != null) return byUtr
        return pick(
            pool.filter {
                !(it.utr != null && inc.utr != null) &&
                    it.amountMinor == inc.amountMinor &&
                    gap(it, inc) <= NEFT_WINDOW_MS
            },
            inc,
        )
    }

    private fun findCardPartner(existing: List<PendingTxn>, inc: ParsedTxn): PendingTxn? {
        val partnerKind = when (inc.kind) {
            TxnKind.CARD_SPEND -> TxnKind.CARD_SI_PAID
            TxnKind.CARD_SI_PAID -> TxnKind.CARD_SPEND
            else -> return null
        }
        return pick(
            existing.filter {
                it.kind == partnerKind &&
                    it.mergedCount == 1 &&
                    it.direction == inc.direction &&
                    it.accountType == inc.accountType &&
                    it.amountMinor == inc.amountMinor &&
                    it.currency == inc.currency &&
                    gap(it, inc) <= CARD_WINDOW_MS &&
                    sameMerchant(it.merchant, inc.merchant)
            },
            inc,
        )
    }

    fun correlate(existing: List<PendingTxn>, incoming: ParsedTxn): Correlation {
        val same = existing.firstOrNull { it.rawHash == incoming.rawHash }
        if (same != null) return Correlation.DropDuplicate(ofId = same.id, reason = "same_sms")

        return when (incoming.kind) {
            TxnKind.NEFT_CONFIRM -> {
                val ex = findNeftPartner(existing, incoming)
                if (ex != null) {
                    val hasCounterparty = ex.counterparty == null && incoming.counterparty != null
                    val hasUtr = ex.utr == null && incoming.utr != null
                    if (!hasCounterparty && !hasUtr) {
                        Correlation.DropDuplicate(ofId = ex.id, reason = "empty_patch")
                    } else {
                        Correlation.Enrich(
                            targetId = ex.id,
                            patch = TxnPatch(
                                hasCounterparty = hasCounterparty,
                                counterparty = if (hasCounterparty) incoming.counterparty else null,
                                hasUtr = hasUtr,
                                utr = if (hasUtr) incoming.utr else null,
                            ),
                        )
                    }
                } else {
                    Correlation.Insert
                }
            }
            TxnKind.CA_DEBIT -> {
                val ex = findNeftPartner(existing, incoming)
                if (ex != null) {
                    Correlation.Enrich(
                        targetId = ex.id,
                        patch = TxnPatch(
                            kind = TxnKind.CA_DEBIT,
                            occurredOn = incoming.occurredOn,
                            hasOccurredTime = incoming.occurredTime != null,
                            occurredTime = incoming.occurredTime,
                            hasAccountLast4 = incoming.accountLast4 != null,
                            accountLast4 = incoming.accountLast4,
                            hasBalanceMinor = incoming.balanceMinor != null,
                            balanceMinor = incoming.balanceMinor,
                        ),
                    )
                } else {
                    Correlation.Insert
                }
            }
            else -> {
                val ex = findCardPartner(existing, incoming)
                if (ex != null) {
                    if (incoming.kind == TxnKind.CARD_SPEND) {
                        val hasLast4 = ex.accountLast4 == null && incoming.accountLast4 != null
                        val hasTime = ex.occurredTime == null && incoming.occurredTime != null
                        if (hasLast4 || hasTime) {
                            return Correlation.Enrich(
                                targetId = ex.id,
                                patch = TxnPatch(
                                    hasAccountLast4 = hasLast4,
                                    accountLast4 = if (hasLast4) incoming.accountLast4 else null,
                                    hasOccurredTime = hasTime,
                                    occurredTime = if (hasTime) incoming.occurredTime else null,
                                ),
                            )
                        }
                    }
                    Correlation.DropDuplicate(ofId = ex.id, reason = "paired")
                } else {
                    Correlation.Insert
                }
            }
        }
    }
}
