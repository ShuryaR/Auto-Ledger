package app.onebook.autoledger.ingest

import app.onebook.autoledger.data.Repo
import app.onebook.autoledger.erp.ErpOps
import app.onebook.autoledger.outbox.OutboxRunner
import app.onebook.autoledger.outbox.RunResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import java.util.WeakHashMap

interface DrainErp : ErpOps {
    suspend fun checkSession(): Boolean
}

enum class DrainStopReason { EMPTY, NEEDS_LOGIN, RETRY_LATER, LIMIT }

data class DrainResult(
    val ran: Int,
    val stoppedBecause: DrainStopReason,
)

object OutboxDrainer {
    const val DRAIN_LIMIT = 20
    const val NEEDS_LOGIN_PARK_AFTER = 3
    const val NEEDS_LOGIN_SINCE_KEY = "needs_login_since"
    const val PERMISSION_DENIED_MESSAGE = OutboxRunner.PERMISSION_DENIED_MESSAGE

    fun needsLoginCountKey(itemId: String) = "needs_login_count:$itemId"

    private val repoLocks = Collections.synchronizedMap(WeakHashMap<Repo, Mutex>())

    private fun lockFor(repo: Repo): Mutex = synchronized(repoLocks) {
        repoLocks.getOrPut(repo) { Mutex() }
    }

    suspend fun drainOutbox(
        repo: Repo,
        erp: DrainErp,
        now: () -> Long = System::currentTimeMillis,
    ): DrainResult = lockFor(repo).withLock {
        drainInternal(repo, erp, now)
    }

    private suspend fun sessionValid(erp: DrainErp): Boolean = try {
        erp.checkSession()
    } catch (_: Throwable) {
        false
    }

    private suspend fun resetCounter(repo: Repo, key: String) {
        val cur = repo.getSetting(key)
        if (cur != null && cur != "0") repo.setSetting(key, "0")
    }

    private suspend fun clearNeedsLogin(repo: Repo) {
        if (!repo.getSetting(NEEDS_LOGIN_SINCE_KEY).isNullOrEmpty()) {
            repo.setSetting(NEEDS_LOGIN_SINCE_KEY, "")
        }
    }

    private suspend fun drainInternal(
        repo: Repo,
        erp: DrainErp,
        now: () -> Long,
    ): DrainResult {
        var ran = 0
        while (ran < DRAIN_LIMIT) {
            val item = repo.nextOutbox() ?: return DrainResult(ran, DrainStopReason.EMPTY)
            val countKey = needsLoginCountKey(item.id)
            val res = try {
                OutboxRunner.runPlan(item.txnId, item.plan, repo, erp, userAction = false)
            } catch (e: Throwable) {
                ran++
                if (repo.getPending(item.txnId) == null) {
                    repo.markOutbox(item.id, status = app.onebook.autoledger.core.OutboxStatus.FAILED)
                    continue
                }
                throw e
            }
            ran++

            if (res is RunResult.NeedsLogin) {
                if (!sessionValid(erp)) {
                    if (repo.getSetting(countKey) != "0") repo.setSetting(countKey, "0")
                    if (repo.getSetting(NEEDS_LOGIN_SINCE_KEY).isNullOrEmpty()) {
                        repo.setSetting(NEEDS_LOGIN_SINCE_KEY, now().toString())
                    }
                    return DrainResult(ran, DrainStopReason.NEEDS_LOGIN)
                }
                clearNeedsLogin(repo)
                val count = (repo.getSetting(countKey)?.toIntOrNull() ?: 0) + 1
                if (count >= NEEDS_LOGIN_PARK_AFTER) {
                    OutboxRunner.parkForPermission(item.txnId, item.id, repo)
                    repo.setSetting(countKey, "0")
                    continue
                }
                repo.setSetting(countKey, count.toString())
                return DrainResult(ran, DrainStopReason.NEEDS_LOGIN)
            }

            resetCounter(repo, countKey)
            clearNeedsLogin(repo)
            if (res is RunResult.Created) continue
            if (res is RunResult.Failed && res.retryable) return DrainResult(ran, DrainStopReason.RETRY_LATER)
        }
        return DrainResult(ran, DrainStopReason.LIMIT)
    }
}
