// NEW: outcome types and queue state transitions for ccd_session suggested tasks.
package splice.modes.ccdsession

internal sealed class DismissOutcome {
    data object Withdrawn : DismissOutcome()
    data object AlreadyStarted : DismissOutcome()
    data object AlreadyDismissed : DismissOutcome()
    data object Expired : DismissOutcome()
    data object InFlight : DismissOutcome()
    data object NotFound : DismissOutcome()
}

internal sealed class ClaimOutcome {
    data class Claimed(val task: SuggestedTask) : ClaimOutcome()
    data object InFlight : ClaimOutcome()
    data object NotPending : ClaimOutcome()
    data object NotFound : ClaimOutcome()
}

internal sealed class ResolveOutcome {
    data class Resolved(val task: SuggestedTask) : ResolveOutcome()
    data object NotFound : ResolveOutcome()
    data class BadRequest(val error: String) : ResolveOutcome()
}

internal data class SpawnResult(
    val task: SuggestedTask,
    val position: Int,
    val evicted: List<SuggestedTask>,
    val pending: List<SuggestedTask>,
)

internal class CcdTaskQueue {
    fun evictIfFull(bucket: SessionBucket): List<SuggestedTask> {
        val evicted = mutableListOf<SuggestedTask>()
        while (bucket.active.size >= MAX_PENDING_SUGGESTIONS) {
            val victimIdx = bucket.active.indexOfFirst { it.state == TaskState.PENDING }
            if (victimIdx < 0) break
            val victim = bucket.active.removeAt(victimIdx).copy(state = TaskState.DISMISSED)
            recordResolved(bucket, victim)
            evicted.add(victim)
        }
        return evicted
    }

    fun recordResolved(bucket: SessionBucket, task: SuggestedTask) {
        bucket.resolved.add(task)
        while (bucket.resolved.size > MAX_RESOLVED_TASKS) {
            bucket.resolved.removeAt(0)
        }
    }

    fun resolveActive(
        bucket: SessionBucket,
        activeIdx: Int,
        outcome: String,
        startedSessionId: String?,
    ): SuggestedTask? {
        val task = bucket.active[activeIdx]
        if (outcome == "released") {
            val pending = task.copy(state = TaskState.PENDING, claimedBy = null)
            bucket.active[activeIdx] = pending
            return pending
        }
        val nextState = when (outcome) {
            "started" -> TaskState.STARTED
            "here" -> TaskState.HERE
            "dismissed" -> TaskState.DISMISSED
            "expired" -> TaskState.EXPIRED
            else -> return null
        }
        bucket.active.removeAt(activeIdx)
        val finished = task.copy(
            state = nextState,
            startedSessionId = if (nextState == TaskState.STARTED) startedSessionId else null,
            claimedBy = null,
        )
        recordResolved(bucket, finished)
        return finished
    }

    fun priorDismissOutcome(bucket: SessionBucket, taskId: String): DismissOutcome {
        val prior = bucket.resolved.lastOrNull { it.taskId == taskId }
        return when (prior?.state) {
            TaskState.STARTED, TaskState.HERE -> DismissOutcome.AlreadyStarted
            TaskState.DISMISSED -> DismissOutcome.AlreadyDismissed
            TaskState.EXPIRED -> DismissOutcome.Expired
            TaskState.PENDING, TaskState.IN_FLIGHT, null -> DismissOutcome.NotFound
        }
    }
}
