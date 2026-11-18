package app.onebook.autoledger.ingest

import app.onebook.autoledger.core.AccountMapping
import app.onebook.autoledger.core.AccountType
import app.onebook.autoledger.core.Correlate
import app.onebook.autoledger.core.Correlation
import app.onebook.autoledger.core.ParsedTxn
import app.onebook.autoledger.core.PendingTxn
import app.onebook.autoledger.core.TxnPatch
import app.onebook.autoledger.core.TxnState
import app.onebook.autoledger.data.MergeResult
import app.onebook.autoledger.data.Repo
import app.onebook.autoledger.sms.ParseResult
import app.onebook.autoledger.sms.SmsIngestManager
import app.onebook.autoledger.sms.TemplateEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.ceil

data class IngestResult(
    val inserted: Int = 0,
    val enriched: Int = 0,
    val duplicates: Int = 0,
    val ignored: Int = 0,
    val unrecognised: Int = 0,
    val unmappedAccounts: List<String> = emptyList(),
    val errors: Int = 0,
)

enum class BackfillSkipped { NO_PERMISSION, NO_MAPPINGS }

data class BackfillResult(
    val ingest: IngestResult = IngestResult(),
    val backfillSkipped: BackfillSkipped? = null,
    val truncated: Boolean = false,
)

enum class CycleStep {
    SYNC_SENDER_PATTERNS,
    INGEST,
    ENSURE_BACKFILL,
    DRAIN_OUTBOX,
    POLL_STATUSES,
}

data class CycleSummary(
    val senderPatterns: List<String>? = null,
    val ingest: IngestResult? = null,
    val backfill: BackfillResult? = null,
    val drain: DrainResult? = null,
    val poll: PollResult? = null,
    val failedSteps: List<CycleStep> = emptyList(),
    val erpReached: Boolean = false,
    val skippedSiteMismatch: Boolean = false,
)

object IngestPipeline {
    const val BACKFILL_DAYS_KEY = "backfill_days"
    val BACKFILL_CHOICES = listOf(7, 30, 90, 180, 0)
    const val ALL_TIME_DAYS = 0
    const val DEFAULT_BACKFILL_DAYS = 7
    const val UNRECOGNISED_COUNT_KEY = "unrecognised_count"
    const val BACKFILL_DONE_AT_KEY = "backfill_done_at"
    const val ENGINE_DISAGREEMENT_COUNT_KEY = "engine_disagreement_count"

    private const val DAY_MS = 86_400_000L
    private const val CORRELATE_LOOKBACK_MS = 7L * 24L * 3600_000L
    private const val NATIVE_UNRECOGNISED = "unrecognised"
    private val NATIVE_MONEY_KINDS = setOf("ca_credit", "ca_debit", "neft_confirm", "card_spend", "card_si_paid")

    fun unrecognisedAccountKey(last4: String) = "unrecognised_account:$last4"

    suspend fun getBackfillDays(repo: Repo): Int {
        val raw = repo.getSetting(BACKFILL_DAYS_KEY)?.trim().orEmpty()
        if (raw.isEmpty()) return DEFAULT_BACKFILL_DAYS
        val n = raw.toIntOrNull() ?: return DEFAULT_BACKFILL_DAYS
        return if (n in BACKFILL_CHOICES) n else DEFAULT_BACKFILL_DAYS
    }

    private fun senderMatches(m: AccountMapping, sender: String): Boolean =
        m.senderIds.any { it.trim().isNotEmpty() && sender.contains(it.trim(), ignoreCase = true) }

    fun resolveMapping(
        mappings: List<AccountMapping>,
        accountType: AccountType,
        sender: String,
        accountLast4: String?,
    ): AccountMapping? {
        val cands = mappings.filter { it.kind == accountType && senderMatches(it, sender) }
        if (accountLast4 == null) return if (cands.size == 1) cands[0] else null
        return cands.firstOrNull { accountLast4 in it.last4 }
    }

    fun resolveMapping(mappings: List<AccountMapping>, txn: ParsedTxn): AccountMapping? =
        resolveMapping(mappings, txn.accountType, txn.sender, txn.accountLast4)

    fun resolveMapping(mappings: List<AccountMapping>, txn: PendingTxn): AccountMapping? =
        resolveMapping(mappings, txn.accountType, txn.sender, txn.accountLast4)

    fun senderPatternsOf(mappings: List<AccountMapping>): List<String> =
        mappings.flatMap { it.senderIds }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()

    suspend fun syncSenderPatterns(repo: Repo, sms: SmsIngestManager): List<String> {
        val patterns = senderPatternsOf(repo.listMappings())
        sms.setSenderPatterns(patterns)
        return patterns
    }

    private val serialLocks = Collections.synchronizedMap(WeakHashMap<Repo, Mutex>())
    private fun lockFor(repo: Repo): Mutex = synchronized(serialLocks) {
        serialLocks.getOrPut(repo) { Mutex() }
    }

    private sealed interface Outcome {
        data object Inserted : Outcome
        data object Enriched : Outcome
        data object Duplicate : Outcome
        data object Ignored : Outcome
        data object Unrecognised : Outcome
        data object Disagreement : Outcome
        data class Unmapped(val last4: String) : Outcome
    }

    private class Batch(
        private val repo: Repo,
        private val sms: SmsIngestManager,
        private val now: () -> Long,
    ) {
        var inserted = 0
        var enriched = 0
        var duplicates = 0
        var ignored = 0
        var unrecognised = 0
        val unmappedAccounts = mutableListOf<String>()
        var errors = 0

        private var engine: TemplateEngine? = null
        private var mappings: List<AccountMapping> = emptyList()
        private val pending = mutableListOf<PendingTxn>()
        private val hashes = HashSet<String>()
        private val ids = HashSet<String>()
        private val merged = HashSet<String>()

        suspend fun load() {
            engine = TemplatesManager.loadEngine(repo, sms)
            mappings = repo.listMappings()
            pending.clear()
            pending.addAll(repo.listPending())
            merged.clear()
            merged.addAll(repo.listMergedSmsHashes())
            hashes.clear()
            ids.clear()
            for (p in pending) {
                hashes.add(p.rawHash)
                ids.add(p.id)
            }
        }

        suspend fun process(sender: String, body: String, receivedAt: Long, nativeKind: String?): Outcome {
            val eng = engine ?: return if (nativeKind == NATIVE_UNRECOGNISED) Outcome.Unrecognised else Outcome.Ignored
            val res = eng.parseFull(sender, body, receivedAt)
            if (res !is ParseResult.Ok) {
                if (nativeKind == NATIVE_UNRECOGNISED) return Outcome.Unrecognised
                if (nativeKind != null && nativeKind in NATIVE_MONEY_KINDS) return Outcome.Disagreement
                return Outcome.Ignored
            }
            val txn = res.txn
            if (mappings.none { senderMatches(it, txn.sender) }) return Outcome.Ignored
            if (txn.accountLast4 != null && resolveMapping(mappings, txn) == null) {
                val key = unrecognisedAccountKey(txn.accountLast4)
                if (repo.getSetting(key) == null) repo.setSetting(key, now().toString())
                return Outcome.Unmapped(txn.accountLast4)
            }
            if (txn.rawHash in hashes || txn.rawHash in merged) return Outcome.Duplicate

            val existing = pending.filter { abs(it.receivedAt - txn.receivedAt) <= CORRELATE_LOOKBACK_MS }
            val corr = Correlate.correlate(existing, txn)
            if (corr is Correlation.DropDuplicate && corr.reason == "same_sms") return Outcome.Duplicate
            if (corr is Correlation.DropDuplicate || corr is Correlation.Enrich) {
                val targetId = when (corr) {
                    is Correlation.Enrich -> corr.targetId
                    is Correlation.DropDuplicate -> corr.ofId
                    Correlation.Insert -> ""
                }
                val patch = if (corr is Correlation.Enrich) corr.patch else TxnPatch()
                val m = repo.mergeInto(targetId, patch, txn.rawHash)
                if (m is MergeResult.Merged) {
                    replace(m.txn)
                    merged.add(txn.rawHash)
                } else if (m is MergeResult.Already) {
                    merged.add(txn.rawHash)
                }
                if (m !is MergeResult.Merged || corr is Correlation.DropDuplicate) return Outcome.Duplicate
                return Outcome.Enriched
            }

            val id = "t_${txn.rawHash}"
            if (id in ids) return Outcome.Duplicate
            val row = PendingTxn(
                id = id,
                kind = txn.kind,
                direction = txn.direction,
                amountMinor = txn.amountMinor,
                currency = txn.currency,
                occurredOn = txn.occurredOn,
                occurredTime = txn.occurredTime,
                accountType = txn.accountType,
                accountLast4 = txn.accountLast4,
                counterparty = txn.counterparty,
                utr = txn.utr,
                merchant = txn.merchant,
                balanceMinor = txn.balanceMinor,
                sender = txn.sender,
                receivedAt = txn.receivedAt,
                rawHash = txn.rawHash,
                state = TxnState.NEW,
                mergedCount = 1,
                createdDocs = emptyList(),
                error = null,
            )
            repo.upsertPending(row)
            pending.add(row)
            hashes.add(row.rawHash)
            ids.add(row.id)
            return Outcome.Inserted
        }

        fun count(o: Outcome) {
            when (o) {
                is Outcome.Unmapped -> if (o.last4 !in unmappedAccounts) unmappedAccounts.add(o.last4)
                Outcome.Inserted -> inserted++
                Outcome.Enriched -> enriched++
                Outcome.Duplicate -> duplicates++
                Outcome.Ignored -> ignored++
                Outcome.Unrecognised, Outcome.Disagreement -> unrecognised++
            }
        }

        fun toResult() = IngestResult(
            inserted = inserted,
            enriched = enriched,
            duplicates = duplicates,
            ignored = ignored,
            unrecognised = unrecognised,
            unmappedAccounts = unmappedAccounts.toList(),
            errors = errors,
        )

        private fun replace(t: PendingTxn) {
            val i = pending.indexOfFirst { it.id == t.id }
            if (i >= 0) pending[i] = t else pending.add(t)
            hashes.add(t.rawHash)
            ids.add(t.id)
        }
    }

    private suspend fun addToCounter(repo: Repo, key: String, n: Int) {
        val cur = repo.getSetting(key)?.toIntOrNull() ?: 0
        repo.setSetting(key, (cur + n).toString())
    }

    suspend fun ingest(
        repo: Repo,
        sms: SmsIngestManager,
        now: () -> Long = System::currentTimeMillis,
    ): IngestResult = lockFor(repo).withLock {
        val items = sms.peekQueue()
        if (items.isEmpty()) return@withLock IngestResult()
        val batch = Batch(repo, sms, now)
        batch.load()
        val done = mutableListOf<String>()
        val unrecognisedIds = mutableListOf<String>()
        var disagreements = 0
        for (q in items.sortedBy { it.receivedAt }) {
            try {
                val o = batch.process(q.sender, q.body, q.receivedAt, q.kind)
                batch.count(o)
                if (o == Outcome.Disagreement) disagreements++
                if (o == Outcome.Unrecognised || o == Outcome.Disagreement) unrecognisedIds.add(q.id) else done.add(q.id)
            } catch (_: Throwable) {
                batch.errors++
            }
        }
        if (unrecognisedIds.isNotEmpty()) {
            try {
                addToCounter(repo, UNRECOGNISED_COUNT_KEY, unrecognisedIds.size)
                if (disagreements > 0) addToCounter(repo, ENGINE_DISAGREEMENT_COUNT_KEY, disagreements)
                done.addAll(unrecognisedIds)
            } catch (_: Throwable) {
                batch.errors += unrecognisedIds.size
            }
        }
        if (done.isNotEmpty()) {
            try {
                sms.ackQueue(done)
            } catch (_: Throwable) {
                batch.errors += done.size
            }
        }
        batch.toResult()
    }

    suspend fun ensureBackfill(
        repo: Repo,
        sms: SmsIngestManager,
        now: () -> Long = System::currentTimeMillis,
        fullWindow: Boolean = false,
    ): BackfillResult = lockFor(repo).withLock {
        val perm = sms.permissionStatus()
        if (!perm.readSms) return@withLock BackfillResult(backfillSkipped = BackfillSkipped.NO_PERMISSION)
        val patterns = senderPatternsOf(repo.listMappings())
        if (patterns.isEmpty()) return@withLock BackfillResult(backfillSkipped = BackfillSkipped.NO_MAPPINGS)
        val chosen = getBackfillDays(repo)
        val allTime = chosen == ALL_TIME_DAYS
        var days = chosen
        if (!fullWindow) {
            val doneAt = repo.getSetting(BACKFILL_DONE_AT_KEY)?.toLongOrNull()
            if (doneAt != null && doneAt > 0L) {
                val sinceDone = ceil(maxOf(0L, now() - doneAt).toDouble() / DAY_MS.toDouble()).toInt() + 1
                val routine = maxOf(DEFAULT_BACKFILL_DAYS, sinceDone)
                days = if (allTime) routine else minOf(chosen, routine)
            }
        }
        val scan = sms.backfillScan(days = days, patternsOverride = patterns)
        val batch = Batch(repo, sms, now)
        batch.load()
        for (raw in scan.messages.sortedBy { it.receivedAt }) {
            try {
                val o = batch.process(raw.sender, raw.body, raw.receivedAt, null)
                batch.count(o)
            } catch (_: Throwable) {
                batch.errors++
            }
        }
        repo.setSetting(BACKFILL_DONE_AT_KEY, now().toString())
        BackfillResult(ingest = batch.toResult(), truncated = scan.truncated)
    }

    suspend fun runForegroundCycle(
        repo: Repo,
        sms: SmsIngestManager,
        erp: DrainErp,
        now: () -> Long = System::currentTimeMillis,
    ): CycleSummary {
        val failedSteps = mutableListOf<CycleStep>()
        var answered = false
        val watchedErp = object : DrainErp by erp {
            override suspend fun checkSession(): Boolean {
                val ok = erp.checkSession()
                if (ok) answered = true
                return ok
            }

            override suspend fun findDraftsByUtr(reference: String) =
                erp.findDraftsByUtr(reference).also { answered = true }

            override suspend fun getDraftSummary(doctype: String, name: String) =
                erp.getDraftSummary(doctype, name).also { answered = true }

            override suspend fun createDraft(doctype: String, body: org.json.JSONObject) =
                erp.createDraft(doctype, body).also { answered = true }

            override suspend fun deleteDraft(doctype: String, name: String) =
                erp.deleteDraft(doctype, name).also { answered = true }

            override suspend fun searchParties(
                doctype: app.onebook.autoledger.core.PartyDoctype,
                q: String,
                exact: Boolean,
            ) = erp.searchParties(doctype, q, exact).also { answered = true }

            override suspend fun getDocStatuses(refs: List<Pair<String, String>>) =
                erp.getDocStatuses(refs).also { answered = true }

            override suspend fun createParty(
                doctype: app.onebook.autoledger.core.PartyDoctype,
                fields: org.json.JSONObject,
            ) = erp.createParty(doctype, fields).also { answered = true }
        }

        val senderPatterns = try {
            syncSenderPatterns(repo, sms)
        } catch (_: Throwable) {
            failedSteps.add(CycleStep.SYNC_SENDER_PATTERNS)
            null
        }
        val ingestRes = try {
            ingest(repo, sms, now)
        } catch (_: Throwable) {
            failedSteps.add(CycleStep.INGEST)
            null
        }
        val backfillRes = try {
            ensureBackfill(repo, sms, now, fullWindow = false)
        } catch (_: Throwable) {
            failedSteps.add(CycleStep.ENSURE_BACKFILL)
            null
        }
        val drainRes = try {
            OutboxDrainer.drainOutbox(repo, watchedErp, now)
        } catch (_: Throwable) {
            failedSteps.add(CycleStep.DRAIN_OUTBOX)
            null
        }
        val pollRes = try {
            StatusPoller.pollStatuses(repo, watchedErp, now)
        } catch (_: Throwable) {
            failedSteps.add(CycleStep.POLL_STATUSES)
            null
        }

        val finished = drainRes?.stoppedBecause == DrainStopReason.EMPTY || drainRes?.stoppedBecause == DrainStopReason.LIMIT
        if (!answered && failedSteps.isEmpty() && finished && pollRes?.needsLogin != true) {
            answered = try {
                erp.checkSession()
            } catch (_: Throwable) {
                false
            }
        }
        return CycleSummary(
            senderPatterns = senderPatterns,
            ingest = ingestRes,
            backfill = backfillRes,
            drain = drainRes,
            poll = pollRes,
            failedSteps = failedSteps,
            erpReached = answered,
        )
    }
}
