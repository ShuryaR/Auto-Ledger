package app.onebook.autoledger.state

import app.onebook.autoledger.ingest.CycleSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Single-flight visible + background foreground cycle runner.
 * `trigger()` joins an in-flight visible cycle or returns immediately if only the trailing
 * background step (dupCheck + autoApply) is still running. `idle()` awaits everything.
 */
class CycleRunner(
    private val scope: CoroutineScope,
    private val run: suspend () -> CycleSummary,
    private val after: suspend (CycleSummary?) -> Unit,
    private val background: (suspend (CycleSummary?) -> Unit)? = null,
) {
    private val lock = Any()
    private var visibleJob: CompletableDeferred<Unit>? = null
    private var wholeJob: CompletableDeferred<Unit>? = null

    fun triggerAsync(): Job = scope.launch { trigger() }

    suspend fun trigger() {
        val (existing, startedVisible, startedWhole) = synchronized(lock) {
            val v = visibleJob
            if (v != null) {
                Triple(v, null, null)
            } else {
                val nv = CompletableDeferred<Unit>()
                val nw = CompletableDeferred<Unit>()
                visibleJob = nv
                wholeJob = nw
                Triple(null, nv, nw)
            }
        }
        if (existing != null) {
            existing.await()
            return
        }
        val nv = startedVisible!!
        val nw = startedWhole!!
        var summary: CycleSummary? = null
        try {
            summary = try {
                run()
            } catch (_: Throwable) {
                null
            }
            try {
                after(summary)
            } catch (_: Throwable) {
                // Best effort
            }
        } finally {
            nv.complete(Unit)
        }

        if (background == null) {
            synchronized(lock) {
                visibleJob = null
                wholeJob = null
            }
            nw.complete(Unit)
            return
        }

        val bgSummary = summary
        scope.launch {
            try {
                background.invoke(bgSummary)
            } catch (_: Throwable) {
                // Best effort
            } finally {
                synchronized(lock) {
                    visibleJob = null
                    wholeJob = null
                }
                nw.complete(Unit)
            }
        }
    }

    suspend fun idle() {
        val w = synchronized(lock) { wholeJob } ?: return
        w.await()
    }
}
