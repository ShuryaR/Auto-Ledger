package app.onebook.autoledger.inbox

import app.onebook.autoledger.core.ActionChoice
import app.onebook.autoledger.core.ActionPlan
import app.onebook.autoledger.core.PendingTxn
import app.onebook.autoledger.core.SuggestEngine
import app.onebook.autoledger.core.TxnState
import app.onebook.autoledger.data.Repo
import app.onebook.autoledger.outbox.OutboxRunner
import app.onebook.autoledger.outbox.RunResult
import java.util.Collections

object AnswerSubmitter {
    private val running: MutableSet<String> = Collections.synchronizedSet(HashSet())

    fun isSubmitting(txnId: String): Boolean = txnId in running

    private suspend fun remember(repo: Repo, txn: PendingTxn, choice: ActionChoice) {
        val rules = repo.listRules()
        for (r in SuggestEngine.learnRule(txn, choice, rules)) {
            if (rules.none { it == r }) repo.saveRule(r)
        }
    }

    private suspend fun afterThrow(repo: Repo, id: String): Outcome {
        try {
            val t = repo.getPending(id) ?: return Outcome.Gone
            if (t.state == TxnState.CREATED || t.state == TxnState.SUBMITTED || t.state == TxnState.IGNORED) {
                return Outcome.Created(t.createdDocs.map { it.doctype to it.name })
            }
        } catch (_: Throwable) {
            // Fall through to generic failure
        }
        return Outcome.Error(Outcomes.UNKNOWN_FAILURE)
    }

    suspend fun submitAnswer(
        repo: Repo,
        erp: InboxErp,
        txn: PendingTxn,
        plan: ActionPlan,
        choice: ActionChoice,
        rememberChoice: Boolean,
    ): Outcome {
        val id = txn.id
        if (!running.add(id)) return Outcome.Busy
        try {
            val r = try {
                OutboxRunner.runPlan(id, plan, repo, erp, userAction = true)
            } catch (_: Throwable) {
                return afterThrow(repo, id)
            }
            if (r is RunResult.Created) {
                if (rememberChoice) {
                    try {
                        remember(repo, repo.getPending(id) ?: txn, choice)
                    } catch (_: Throwable) {
                        // Best effort
                    }
                }
                return Outcomes.outcomeOf(r, null, plan)
            }
            if (r is RunResult.NeedsLogin) {
                val valid = try {
                    erp.checkSession()
                } catch (_: Throwable) {
                    false
                }
                if (valid) {
                    return Outcome.Error("${OutboxRunner.PERMISSION_DENIED_MESSAGE}.")
                }
                return Outcome.Login
            }
            if (r is RunResult.Failed && r.retryable) {
                return Outcomes.outcomeOf(r, null, plan)
            }
            val failed = r as RunResult.Failed
            val note = try {
                repo.getPending(id)?.error
            } catch (_: Throwable) {
                null
            }
            return Outcomes.outcomeOf(r, Outcomes.readableFailure(failed.error, note), plan)
        } finally {
            running.remove(id)
        }
    }
}
