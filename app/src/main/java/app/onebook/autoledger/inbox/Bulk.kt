package app.onebook.autoledger.inbox

import app.onebook.autoledger.core.ActionChoice
import app.onebook.autoledger.core.ActionPlan
import app.onebook.autoledger.core.ClassifyEngine
import app.onebook.autoledger.core.OutboxStatus
import app.onebook.autoledger.core.PendingTxn
import app.onebook.autoledger.core.TxnState
import app.onebook.autoledger.data.Repo
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicInteger

data class BulkFailure(
    val id: String,
    val message: String,
)

data class BulkResult(
    val done: List<String> = emptyList(),
    val failed: List<BulkFailure> = emptyList(),
    val queued: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
)

object BulkApply {
    const val BULK_ONLY_ONE_BY_ONE =
        "This answer has to be given one payment at a time. Open each payment to answer it."
    const val BULK_SIGNED_OUT =
        "You are signed out of ERPNext. Sign in again; this answer is saved and will be sent then."
    const val BULK_BUSY =
        "This payment is already being sent. Check it again in a moment."

    private sealed interface ItemResult {
        data object Done : ItemResult
        data object Queued : ItemResult
        data object Skipped : ItemResult
        data class Failed(val message: String) : ItemResult
    }

    private suspend fun applyOne(
        repo: Repo,
        erp: InboxErp,
        planFn: (PendingTxn, ActionChoice) -> ActionPlan,
        id: String,
        choice: ActionChoice,
        remember: Boolean,
    ): ItemResult {
        val txn = try {
            val loaded = repo.getPending(id) ?: return ItemResult.Skipped
            if (loaded.state != TxnState.NEW) return ItemResult.Skipped
            if (repo.listOutbox(id).any { it.status == OutboxStatus.PENDING }) return ItemResult.Skipped
            loaded
        } catch (_: Throwable) {
            return ItemResult.Failed(Outcomes.UNKNOWN_FAILURE)
        }
        val plan = try {
            planFn(txn, choice)
        } catch (e: Throwable) {
            return ItemResult.Failed(Outcomes.planErrorMessage(e))
        }
        val outcome = try {
            AnswerSubmitter.submitAnswer(repo, erp, txn, plan, choice, remember)
        } catch (_: Throwable) {
            return ItemResult.Failed(Outcomes.UNKNOWN_FAILURE)
        }
        return when (outcome) {
            is Outcome.Created -> ItemResult.Done
            is Outcome.Offline -> ItemResult.Queued
            Outcome.Gone -> ItemResult.Skipped
            Outcome.Login -> ItemResult.Failed(BULK_SIGNED_OUT)
            Outcome.Busy -> ItemResult.Failed(BULK_BUSY)
            is Outcome.Error -> ItemResult.Failed(outcome.message.ifEmpty { Outcomes.UNKNOWN_FAILURE })
        }
    }

    suspend fun applyToGroup(
        repo: Repo,
        erp: InboxErp,
        plan: (PendingTxn, ActionChoice) -> ActionPlan,
        items: List<PendingTxn>,
        choice: ActionChoice,
        concurrency: Int = 1,
        remember: Boolean = true,
    ): BulkResult = coroutineScope {
        val ids = items.map { it.id }.distinct()
        if (choice.type !in ClassifyEngine.AUTO_ACTIONS) {
            return@coroutineScope BulkResult(
                failed = ids.map { BulkFailure(it, BULK_ONLY_ONE_BY_ONE) },
            )
        }
        if (ids.isEmpty()) return@coroutineScope BulkResult()

        val width = maxOf(1, minOf(ids.size, concurrency))
        val results = arrayOfNulls<ItemResult>(ids.size)
        val next = AtomicInteger(0)
        val jobs = (0 until width).map {
            async {
                while (true) {
                    val idx = next.getAndIncrement()
                    if (idx >= ids.size) break
                    results[idx] = applyOne(repo, erp, plan, ids[idx], choice, remember)
                }
            }
        }
        jobs.awaitAll()

        val done = mutableListOf<String>()
        val queued = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failed = mutableListOf<BulkFailure>()
        ids.forEachIndexed { idx, id ->
            when (val r = results[idx]!!) {
                ItemResult.Done -> done.add(id)
                ItemResult.Queued -> queued.add(id)
                ItemResult.Skipped -> skipped.add(id)
                is ItemResult.Failed -> failed.add(BulkFailure(id, r.message))
            }
        }
        BulkResult(done = done, failed = failed, queued = queued, skipped = skipped)
    }
}
