package app.onebook.autoledger.core

object TrustEngine {
    const val STALE_AFTER_MS = 24L * 60L * 60L * 1000L
    const val MISMATCH_REASON = "Bank balance differs from your latest bank message"

    data class PendingDraftsSummary(val count: Int, val totalMinor: Long)

    data class TrustInput(
        val lastSyncedAt: Long?,
        val now: Long,
        val unclassifiedCount: Int,
        val pendingDrafts: PendingDraftsSummary,
        val computedBankBalanceMinor: Long?,
        val smsBalanceMinor: Long?,
    )

    data class Trust(
        val incomplete: Boolean,
        val reasons: List<String>,
        val mismatch: Boolean,
    )

    fun buildTrust(i: TrustInput): Trust {
        val reasons = mutableListOf<String>()
        var incomplete = false
        if (i.lastSyncedAt == null) {
            incomplete = true
            reasons.add("Not synced yet")
        } else if (i.now - i.lastSyncedAt > STALE_AFTER_MS) {
            incomplete = true
            reasons.add("Not synced for more than a day")
        }
        if (i.unclassifiedCount > 0) {
            incomplete = true
            reasons.add(
                if (i.unclassifiedCount == 1) "1 message needs an answer"
                else "${i.unclassifiedCount} messages need an answer"
            )
        }
        val mismatch = i.computedBankBalanceMinor != null &&
            i.smsBalanceMinor != null &&
            i.computedBankBalanceMinor != i.smsBalanceMinor
        if (mismatch) reasons.add(MISMATCH_REASON)
        return Trust(incomplete = incomplete, reasons = reasons, mismatch = mismatch)
    }
}
