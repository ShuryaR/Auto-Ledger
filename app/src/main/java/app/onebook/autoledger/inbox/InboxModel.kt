package app.onebook.autoledger.inbox

import app.onebook.autoledger.core.AccountMapping
import app.onebook.autoledger.core.AccountType
import app.onebook.autoledger.core.ActionChoice
import app.onebook.autoledger.core.ActionPlan
import app.onebook.autoledger.core.ActionType
import app.onebook.autoledger.core.ClassifyEngine
import app.onebook.autoledger.core.Direction
import app.onebook.autoledger.core.PartialSuggestionChoice
import app.onebook.autoledger.core.PendingTxn
import app.onebook.autoledger.core.Rule
import app.onebook.autoledger.core.SuggestEngine
import app.onebook.autoledger.core.TxnKind
import app.onebook.autoledger.ingest.IngestPipeline

data class InboxInputs(
    val pending: List<PendingTxn>,
    val mappings: List<AccountMapping>,
    val rules: List<Rule>,
    val createdCount: Int,
    val unreadCount: Int,
    val unknownAccounts: List<String>,
    val lastSyncedAt: Long?,
    val now: Long,
    val queuedIds: List<String>,
    val filter: InboxFilter? = null,
    val chips: ChipCache? = null,
    val autoFailed: Map<String, String> = emptyMap(),
    val autoHeld: Map<String, String> = emptyMap(),
    val autoDoneCount: Int = 0,
)

class ChipCache {
    private data class Cached(val version: String, val chip: ChipView)
    private val map = HashMap<String, Cached>()

    fun get(t: PendingTxn, rules: List<Rule>, version: String): ChipView {
        val hit = map[t.id]
        if (hit != null && hit.version == version) return hit.chip
        var chip = SuggestionsUi.chipFor(t, SuggestEngine.suggest(t, rules, emptyList(), emptyList()))
        if (chip.text == "Who is this?" && !t.merchant.isNullOrBlank() && t.counterparty.isNullOrBlank()) {
            chip = chip.copy(text = "What was this?")
        }
        map[t.id] = Cached(version, chip)
        return chip
    }

    fun prune(ids: Set<String>) {
        val it = map.keys.iterator()
        while (it.hasNext()) {
            if (it.next() !in ids) it.remove()
        }
    }

    val size: Int get() = map.size
}

sealed interface InboxItem {
    val key: String
    data class Header(override val key: String, val title: String) : InboxItem
    data class Row(override val key: String, val row: InboxRow) : InboxItem
}

data class InboxRow(
    val id: String,
    val title: String,
    val subtitle: String,
    val amountText: String,
    val amountA11y: String,
    val direction: Direction,
    val kindIcon: InboxKind,
)

data class InboxSection(
    val title: String,
    val rows: List<InboxRow>,
)

data class UnknownAccountItem(
    val last4: String,
    val text: String,
)

data class AccountsNotice(
    val text: String,
    val last4s: List<String>,
)

data class AutoFailedItem(
    val id: String,
    val text: String,
)

data class GroupCard(
    val key: String,
    val count: Int,
    val title: String,
    val answer: String,
)

data class InboxModel(
    val empty: Boolean,
    val noMatch: Boolean,
    val accounts: List<String>,
    val countLabel: String,
    val trustLine: String,
    val rows: List<InboxRow>,
    val chipOf: (String) -> ChipView,
    val sections: List<InboxSection>,
    val unreadText: String?,
    val unknownAccounts: List<UnknownAccountItem>,
    val accountsNotice: AccountsNotice?,
    val groups: List<GroupCard>,
    val autoFailed: List<AutoFailedItem>,
    val autoDoneText: String?,
)

object InboxModelBuilder {
    const val LAST_SYNCED_KEY = "lastSyncedAt"

    private val QUEUED_CHIP = ChipView("Answer saved, waiting to send", ChipTone.PLAIN)
    private val AUTO_FAILED_CHIP = ChipView("Not done automatically. Open it to answer.", ChipTone.WARN)
    private val AUTO_HELD_CHIP = ChipView("Looks like another payment: check before answering", ChipTone.WARN)

    private val DOCTYPE_NOTE = Regex("\\s*\\((?:Payment Entry|Journal Entry|Purchase Invoice|Sales Invoice)\\)")
    private const val DOC_ID = "[A-Z][A-Z0-9]*(?:[-/][A-Z0-9]+)*[-/]\\d+"
    private val LEADING_DOC_ID = Regex("^$DOC_ID(?:,\\s*$DOC_ID)*(?![\\w/-])")
    private val ENTRY_DOC_ID = Regex("(\\b[Aa]n entry)\\s+$DOC_ID(?![\\w/-])")
    private val ANY_DOC_ID = Regex("\\s*\\b$DOC_ID(?![\\w/-]),?")

    fun rulesVersion(rules: List<Rule>): String =
        rules.joinToString(";") { "${it.matchKey}|${it.action.wire}|${it.account ?: ""}|${it.party?.name ?: ""}|${it.hits}|${it.lastUsed}" }

    fun pageItems(sections: List<InboxSection>, rowLimit: Int): List<InboxItem> {
        val out = mutableListOf<InboxItem>()
        var left = rowLimit
        for (s in sections) {
            if (left <= 0) break
            out.add(InboxItem.Header("h:${s.title}", s.title))
            for (r in s.rows.take(left)) {
                out.add(InboxItem.Row(r.id, r))
            }
            left -= s.rows.size
        }
        return out
    }

    fun kindIconOf(t: PendingTxn): InboxKind = when {
        t.kind == TxnKind.CARD_SPEND || t.kind == TxnKind.CARD_SI_PAID -> InboxKind.CARD
        t.counterparty?.contains("@") == true -> InboxKind.UPI
        else -> InboxKind.BANK
    }

    fun newestFirst(xs: List<PendingTxn>): List<PendingTxn> =
        xs.sortedWith { a, b ->
            val cmp = b.receivedAt.compareTo(a.receivedAt)
            if (cmp != 0) cmp else if (a.id < b.id) 1 else if (a.id > b.id) -1 else 0
        }

    fun build(i: InboxInputs): InboxModel {
        val today = Formatters.istToday(i.now)
        val yesterday = Formatters.istToday(i.now - 86_400_000L)
        val queued = i.queuedIds.toHashSet()
        val titles = arrayOf("Today", "Yesterday", "Earlier")
        val buckets = Array(3) { mutableListOf<PendingTxn>() }
        val shown = if (i.filter != null) InboxFilters.filterPending(i.pending, i.filter, today) else i.pending
        for (t in newestFirst(shown)) {
            val b = when (t.occurredOn) {
                today -> 0
                yesterday -> 1
                else -> 2
            }
            buckets[b].add(t)
        }
        fun toRow(t: PendingTxn): InboxRow {
            val mapping = IngestPipeline.resolveMapping(i.mappings, t)
            return InboxRow(
                id = t.id,
                title = Formatters.titleOf(t),
                subtitle = listOf(
                    Formatters.kindLabel(t.kind),
                    Formatters.accountLabel(t, mapping),
                    Formatters.whenLabel(t, today),
                ).joinToString(" \u00B7 "),
                amountText = Formatters.signedAmount(t),
                amountA11y = Formatters.amountA11y(t),
                direction = t.direction,
                kindIcon = kindIconOf(t),
            )
        }

        val chips = i.chips ?: ChipCache()
        val version = rulesVersion(i.rules)
        val byId = i.pending.associateBy { it.id }
        val chipOf: (String) -> ChipView = { id ->
            when {
                id in queued -> QUEUED_CHIP
                i.autoFailed.containsKey(id) -> AUTO_FAILED_CHIP
                i.autoHeld.containsKey(id) -> AUTO_HELD_CHIP
                else -> byId[id]?.let { chips.get(it, i.rules, version) } ?: ChipView("Who is this?", ChipTone.PLAIN)
            }
        }
        val sections = buckets.mapIndexedNotNull { idx, g ->
            if (g.isEmpty()) null else InboxSection(titles[idx], g.map(::toRow))
        }
        val rows = sections.flatMap { it.rows }
        val n = rows.size
        val drafts = "${i.createdCount} draft${if (i.createdCount == 1) "" else "s"} waiting for your accountant"
        val synced = if (i.lastSyncedAt == null) "Not synced yet" else "Synced ${Formatters.relativeAgo(i.now, i.lastSyncedAt)}"

        val unreadText = if (i.unreadCount > 0) {
            "${i.unreadCount} message${if (i.unreadCount == 1) "" else "s"} could not be read"
        } else {
            null
        }
        val accountsNotice = if (i.unknownAccounts.isEmpty()) {
            null
        } else {
            AccountsNotice(
                last4s = i.unknownAccounts,
                text = "${i.unknownAccounts.size} account${if (i.unknownAccounts.size == 1) " is" else "s are"} not set up: ${i.unknownAccounts.joinToString(", ")}",
            )
        }
        val autoDoneText = if (i.autoDoneCount > 0) {
            "${if (i.autoDoneCount == 1) "1 payment was" else "${i.autoDoneCount} payments were"} done automatically. See Activity."
        } else {
            null
        }

        return InboxModel(
            empty = i.pending.isEmpty(),
            noMatch = i.pending.isNotEmpty() && n == 0,
            accounts = i.pending.mapNotNull { it.accountLast4?.takeIf { l -> l.isNotBlank() } }.distinct().sorted(),
            countLabel = if (n == 0) "Nothing to answer" else "$n to answer",
            trustLine = "$synced \u00B7 $drafts",
            rows = rows,
            chipOf = chipOf,
            sections = sections,
            unreadText = unreadText,
            unknownAccounts = i.unknownAccounts.map { l -> UnknownAccountItem(l, "Unrecognised account ending $l: add it in Settings") },
            accountsNotice = accountsNotice,
            groups = groupCards(shown.filter { it.id !in queued }, i.rules),
            autoFailed = newestFirst(i.pending.filter { i.autoFailed.containsKey(it.id) && it.id !in queued }).map { t ->
                AutoFailedItem(t.id, "${Formatters.titleOf(t)}, ${Formatters.plainAmount(t)}: ${plainFailure(i.autoFailed[t.id].orEmpty())}")
            },
            autoDoneText = autoDoneText,
        )
    }

    fun bulkActions(items: List<PendingTxn>): List<ActionItem> {
        if (items.isEmpty()) return emptyList()
        val lists = items.map { Actions.actionsFor(it.direction, it.accountType) }
        return lists[0].filter { a ->
            a.type in ClassifyEngine.AUTO_ACTIONS && lists.all { l -> l.any { x -> x.type == a.type } }
        }
    }

    fun answerText(c: PartialSuggestionChoice, direction: Direction, kind: AccountType): String {
        val label = Actions.actionLabel(c.type, direction, kind)
        val account = c.expenseAccount ?: c.incomeAccount
        if (account != null) {
            return if (c.party != null) "$label, ${Formatters.shortAccount(account)}, for ${c.party!!.name}"
            else "$label, ${Formatters.shortAccount(account)}"
        }
        if (c.party != null) return "$label, ${c.party!!.name}"
        return label
    }

    fun answerText(c: ActionChoice, direction: Direction, kind: AccountType): String {
        val partial = when (c) {
            is ActionChoice.Expense -> PartialSuggestionChoice(c.type, party = c.party, expenseAccount = c.expenseAccount)
            is ActionChoice.OtherIncome -> PartialSuggestionChoice(c.type, incomeAccount = c.incomeAccount)
            is ActionChoice.SupplierPayment -> PartialSuggestionChoice(c.type, party = c.party)
            is ActionChoice.CustomerAdvance -> PartialSuggestionChoice(c.type, party = c.party)
            is ActionChoice.Salary -> PartialSuggestionChoice(c.type, party = c.party)
            else -> PartialSuggestionChoice(c.type)
        }
        return answerText(partial, direction, kind)
    }

    private fun groupTotal(items: List<PendingTxn>): String? {
        val cur = items.firstOrNull()?.currency ?: return null
        if (items.any { it.currency != cur }) return null
        return Formatters.plainAmount(items.sumOf { it.amountMinor }, cur)
    }

    fun groupTitle(items: List<PendingTxn>): String {
        val t = items[0]
        val head = "${items.size} payment${if (items.size == 1) "" else "s"} ${if (t.direction == Direction.IN) "from" else "to"} ${Formatters.titleOf(t)}"
        val sum = groupTotal(items)
        return if (sum != null) "$head, $sum in total" else head
    }

    fun groupCards(items: List<PendingTxn>, rules: List<Rule>): List<GroupCard> {
        return ClassifyEngine.groupSimilar(items, rules).map { g ->
            val allowed = bulkActions(g.items).mapTo(HashSet()) { it.type }
            val s = g.suggestion?.takeIf { it.type in allowed }
            val first = g.items[0]
            GroupCard(
                key = g.key,
                count = g.items.size,
                title = groupTitle(g.items),
                answer = if (s != null) "Suggested: ${answerText(s, first.direction, first.accountType)}" else "Choose what these were",
            )
        }
    }

    fun amountRange(items: List<PendingTxn>): String? {
        val inr = items.filter { it.currency == "INR" }.map { it.amountMinor }
        if (inr.isEmpty()) return null
        val lo = inr.min()
        val hi = inr.max()
        return if (lo == hi) Formatters.formatInr(lo) else "${Formatters.formatInr(lo)} to ${Formatters.formatInr(hi)}"
    }

    private fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"

    fun bulkSummary(r: BulkResult): String {
        val parts = mutableListOf("${r.done.size} done")
        if (r.queued.isNotEmpty()) parts.add("${r.queued.size} saved to send later")
        if (r.failed.isNotEmpty()) parts.add(plural(r.failed.size, "needs your attention", "need your attention"))
        if (r.skipped.isNotEmpty()) parts.add(plural(r.skipped.size, "was already answered", "were already answered"))
        return parts.joinToString(", ")
    }

    fun plainFailure(message: String): String {
        var s = DOCTYPE_NOTE.replace(message, "")
        s = s.replace(Regex("\\b(?:the|a) (?:Payment Entry|Journal Entry)\\b"), "the entry")
        s = s.replace(Regex("\\b(?:Payment Entry|Journal Entry)\\b"), "entry")
        s = s.replace(Regex("\\ba Purchase Invoice\\b"), "a bill")
        s = s.replace(Regex("\\ba Sales Invoice\\b"), "an invoice")
        s = s.replace(Regex("\\bPurchase Invoice\\b"), "bill")
        s = s.replace(Regex("\\bSales Invoice\\b"), "invoice")
        s = LEADING_DOC_ID.replace(s, "An entry")
        s = ENTRY_DOC_ID.replace(s, "$1")
        s = ANY_DOC_ID.replace(s, "")
        s = s.replace(Regex("\\s*\\(\\s*\\)"), "")
        s = s.replace(Regex("\\s+([;,.:])"), "$1")
        s = s.replace(Regex("\\s{2,}"), " ")
        return s.trim()
    }

    fun bulkTitle(r: BulkResult): String = when {
        r.done.isNotEmpty() -> "Done"
        r.failed.isNotEmpty() -> "Needs your attention"
        r.queued.isNotEmpty() -> "Saved to send later"
        else -> "Already answered"
    }

    private fun draftOf(txn: PendingTxn, choice: ActionChoice, mappings: List<AccountMapping>): FlowDraft {
        val mapping = IngestPipeline.resolveMapping(mappings, txn)
        return when (choice) {
            is ActionChoice.Expense -> FlowDraft(mapping = mapping, action = choice.type, account = choice.expenseAccount, party = choice.party?.let { FlowParty(it) })
            is ActionChoice.OtherIncome -> FlowDraft(mapping = mapping, action = choice.type, account = choice.incomeAccount)
            is ActionChoice.SupplierPayment -> FlowDraft(mapping = mapping, action = choice.type, party = FlowParty(choice.party))
            else -> FlowDraft(mapping = mapping, action = choice.type)
        }
    }

    fun planChoice(
        mappings: List<AccountMapping>,
        company: String,
        drawingsAccount: String,
        today: String,
    ): (PendingTxn, ActionChoice) -> ActionPlan = { txn, choice ->
        FlowLogic.planFor(
            txn,
            draftOf(txn, choice, mappings),
            FlowSettings(company = company, drawingsAccount = drawingsAccount, today = today),
        ).plan
    }

    fun ruleHasAnswer(r: Rule, c: ActionChoice): Boolean {
        val party = when (c) {
            is ActionChoice.CustomerAdvance -> c.party
            is ActionChoice.SupplierPayment -> c.party
            is ActionChoice.AgainstInvoice -> c.party
            is ActionChoice.BillPaidByCard -> c.party
            is ActionChoice.Expense -> c.party
            is ActionChoice.Salary -> c.party
            else -> null
        }
        val account = when (c) {
            is ActionChoice.Expense -> c.expenseAccount
            is ActionChoice.OtherIncome -> c.incomeAccount
            else -> null
        }
        return r.action == c.type && r.party == party && r.account == account
    }

    fun automaticRule(rules: List<Rule>, key: String, choice: ActionChoice, done: List<PendingTxn>): Rule? {
        if (choice.type !in ClassifyEngine.AUTO_ACTIONS) return null
        val mine = done.filter { SuggestEngine.nameKey(it) == key }
        if (mine.isEmpty()) return null
        val stored = rules.firstOrNull { it.matchKey == key }
        if (stored != null && ruleHasAnswer(stored, choice) && stored.currency == "INR" && stored.maxAmountMinor != null) {
            return stored.copy(auto = true)
        }
        val inr = mine.filter { it.currency == "INR" }
        val largest = (inr.ifEmpty { mine }).maxBy { it.amountMinor }
        val learned = SuggestEngine.learnRule(largest, choice, rules)
            .firstOrNull { it.matchKey == key && it.action == choice.type }
        return learned?.copy(auto = true)
    }
}
