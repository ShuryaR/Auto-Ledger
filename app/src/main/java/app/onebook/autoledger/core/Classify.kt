package app.onebook.autoledger.core

object ClassifyEngine {
    val AUTO_ACTIONS: Set<ActionType> = setOf(
        ActionType.EXPENSE,
        ActionType.SUPPLIER_PAYMENT,
        ActionType.OTHER_INCOME,
        ActionType.PERSONAL,
        ActionType.IGNORE,
    )

    const val AUTO_MAX_FACTOR = 2L
    private const val RUPEE = "INR"

    data class SimilarGroup(
        val key: String,
        val items: List<PendingTxn>,
        val suggestion: PartialSuggestionChoice?,
    )

    private fun neededDirection(action: ActionType): Direction? = when (action) {
        ActionType.EXPENSE, ActionType.SUPPLIER_PAYMENT, ActionType.SALARY, ActionType.BILL_PAID_BY_CARD -> Direction.OUT
        ActionType.OTHER_INCOME, ActionType.CUSTOMER_ADVANCE -> Direction.IN
        else -> null
    }

    fun groupSimilar(items: List<PendingTxn>, rules: List<Rule>): List<SimilarGroup> {
        val byKey = LinkedHashMap<String, MutableList<PendingTxn>>()
        for (t in items) {
            val k = SuggestEngine.nameKey(t) ?: continue
            byKey.getOrPut(k) { mutableListOf() }.add(t)
        }
        val out = mutableListOf<SimilarGroup>()
        for ((key, list) in byKey) {
            if (list.size < 2) continue
            val rule = rules.firstOrNull { it.matchKey == key && SuggestEngine.ruleChoice(it) != null }
            out.add(SimilarGroup(key = key, items = list, suggestion = rule?.let { SuggestEngine.ruleChoice(it) }))
        }
        out.sortWith(compareByDescending<SimilarGroup> { it.items.size }.thenBy { it.key })
        return out
    }

    fun canAutoApply(txn: PendingTxn, rule: Rule, dupFlag: Boolean): Boolean {
        if (dupFlag) return false
        if (!rule.auto) return false
        if (rule.action !in AUTO_ACTIONS) return false
        if (SuggestEngine.ruleChoice(rule) == null) return false
        if (txn.state != TxnState.NEW) return false
        val key = SuggestEngine.nameKey(txn) ?: return false
        if (key != rule.matchKey) return false
        val dir = neededDirection(rule.action)
        if (dir != null && dir != txn.direction) return false
        if (txn.currency != RUPEE || rule.currency != RUPEE) return false
        val max = rule.maxAmountMinor ?: return false
        if (max <= 0L || txn.amountMinor <= 0L) return false
        return txn.amountMinor <= AUTO_MAX_FACTOR * max
    }
}
