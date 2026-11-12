package splice.app.observability

import splice.app.sources.observability.PerfAggregateStore
import splice.core.perf.observation.aggregate.PerfAccumulator
import splice.core.util.AsyncFileIo
import splice.core.util.PerfWorkPhase
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Performs one checkpoint pass using the collection's existing budget and persistence policy. */
internal fun interface PerfCheckpointPass {
    fun checkpoint(phase: PerfWorkPhase.Lease)
}

/** Gives the retained dirty-minute pages one fair bounded drain before persistence closes. */
internal class PerfCheckpointDrain(
    private val accumulator: PerfAccumulator,
    private val aggregate: PerfAggregateStore,
    private val checkpointPath: Path,
    private val phase: PerfWorkPhase.Lease,
    private val checkpointPass: PerfCheckpointPass,
) {
    fun drain() {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FINAL_CHECKPOINT_TIMEOUT_MS)
        var attempt = 0
        while (attempt < MAX_FINAL_CHECKPOINT_PAGES && System.nanoTime() < deadline) {
            if (!drainAttempt(attempt, deadline)) break
            attempt++
        }
        aggregate.reportUnflushedPages(accumulator.pendingCheckpointPages)
    }

    private fun drainAttempt(attempt: Int, deadline: Long): Boolean {
        if (accumulator.pendingCheckpointPages == 0 && attempt > 0) return false
        val owned = if (attempt == 0) phase else PerfWorkPhase.tryCheckpoint() ?: return false
        return owned.use { checkpointAndAwait(it, deadline) }
    }

    private fun checkpointAndAwait(owned: PerfWorkPhase.Lease, deadline: Long): Boolean {
        val enqueuedBefore = aggregate.status().enqueued
        // Each accepted write consumes its phase handle; a later page acquires a fresh exclusive phase.
        checkpointPass.checkpoint(owned)
        val enqueuedAfter = aggregate.status().enqueued
        val pendingAfter = accumulator.pendingCheckpointPages
        return if (enqueuedAfter > enqueuedBefore) {
            awaitEnqueuedWrite(deadline)
        } else {
            // A synchronous refusal leaves the page dirty; continue so the next fair page can proceed.
            pendingAfter > 0
        }
    }

    private fun awaitEnqueuedWrite(deadline: Long): Boolean {
        val remainingNanos = (deadline - System.nanoTime()).coerceAtLeast(0L)
        if (remainingNanos == 0L) return false
        val remainingMs = TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L)
        val settled = AsyncFileIo.awaitFile(checkpointPath, remainingMs)
        // Preserve the historical rule: a failed await is terminal only while file work remains pending.
        return settled || aggregate.status().pending == 0L
    }
}

// Bound the final persistence drain to five seconds so slow storage cannot indefinitely delay shutdown.
private const val FINAL_CHECKPOINT_TIMEOUT_MS = 5_000L

// Bound synchronous refusal retries as well as elapsed time; any remaining pages are reported unflushed.
private const val MAX_FINAL_CHECKPOINT_PAGES = 120
