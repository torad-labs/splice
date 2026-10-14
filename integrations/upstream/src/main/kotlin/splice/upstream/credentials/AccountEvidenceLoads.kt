package splice.upstream.credentials

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import splice.core.memory.HeapBudget
import splice.core.memory.HeapCategory
import splice.core.memory.HeapObservation
import splice.core.memory.HeapOwnerState
import splice.core.memory.HeapOwners
import splice.core.util.Cancellables
import java.io.IOException
import java.util.IdentityHashMap

private const val MAX_EVIDENCE_LOAD_KEYS = 256
private const val MAX_EVIDENCE_WAVE_WAITERS = 128
private const val EVIDENCE_WAITER_TIMEOUT_MS = 1_000L
private const val EVIDENCE_LOAD_TIMEOUT_MS = 5_000L
private const val EVIDENCE_COHORT_SCALAR_BYTES = 256L
private const val EVIDENCE_WAITER_SCALAR_BYTES = 64L

/** Head-owned sharing of immutable raw account evidence, never OAuth refresh or account decisions.
 *
 * Membership freezes before the read starts. A caller arriving during I/O enters the next wave,
 * because there is no persistent file generation that could prove the older read fresh for it.
 * Each source retains at most one running and one queued wave; completed waves are removed.
 * Saturation or a waiter deadline falls back to an independent fresh read on the same I/O view.
 * Closing this coordinator or cancelling its injected lifecycle stops producers, not just waiters.
 */
public class AccountEvidenceLoads(
    private val owner: CoroutineScope,
    internal val ioDispatcher: CoroutineDispatcher,
    private val heap: HeapBudget? = null,
) : AutoCloseable {
    private val lifecycle = SupervisorJob(
        requireNotNull(owner.coroutineContext[Job]) {
            "credential evidence loads require a lifecycle-owned scope"
        },
    )
    private val monitor = Any()
    private val slots = IdentityHashMap<TtlCredentialIdentitySource, Slot>()

    internal fun ensureActive() {
        lifecycle.ensureActive()
    }

    internal suspend fun refresh(source: TtlCredentialIdentitySource) {
        lifecycle.ensureActive()
        currentCoroutineContext().ensureActive()
        source.track(heap)
        val admitted = admit(source)
        if (admitted == null) {
            fresh(source)
            return
        }
        val accepted = try {
            admitted.dispatch?.let { dispatch(source, admitted.slot, it) }
            withTimeoutOrNull(EVIDENCE_WAITER_TIMEOUT_MS) { admitted.wave.result.await() }
        } finally {
            synchronized(monitor) { admitted.wave.detach() }
        }
        lifecycle.ensureActive()
        currentCoroutineContext().ensureActive()
        if (accepted != true || source.epoch !== admitted.wave.epoch) fresh(source)
    }

    private suspend fun fresh(source: TtlCredentialIdentitySource) {
        val completed = withTimeoutOrNull(EVIDENCE_LOAD_TIMEOUT_MS) {
            while (true) {
                lifecycle.ensureActive()
                val observed = runInterruptible(ioDispatcher) { source.observe(source.epoch) }
                currentCoroutineContext().ensureActive()
                lifecycle.ensureActive()
                if (observed != null && source.publish(observed)) break
            }
        }
        if (completed == null) throw IOException("credential evidence read exceeded its deadline")
    }

    private fun admit(source: TtlCredentialIdentitySource): Admission? {
        var retired: Wave? = null
        val admitted = synchronized(monitor) {
            val slot = slotFor(source) ?: return@synchronized null
            val epoch = source.epoch
            var wave = slot.queued
            if (wave != null && wave.epoch !== epoch) {
                retired = wave
                slot.queued = null
                wave = null
            }
            val queued = wave ?: newWave(epoch).also { slot.queued = it }
            if (queued.waiters >= MAX_EVIDENCE_WAVE_WAITERS) return@synchronized null
            queued.join()
            val next = queued.takeIf { slot.running == null && slot.scheduled == null }
            if (next != null) slot.scheduled = next
            Admission(slot, queued, next)
        }
        // Completion may resume an unconfined waiter, so never complete under the admission monitor.
        retired?.result?.complete(false)
        retired?.retention?.ownerFinished()
        return admitted
    }

    /** A scalar estimate of the cohort and waiter references, separate from unique admission charges. */
    private fun newWave(epoch: TtlCredentialIdentitySource.Epoch): Wave {
        val wave = Wave(epoch)
        wave.retention = heap?.let {
            HeapOwners.observe(
                wave,
                it,
                EVIDENCE_COHORT_SCALAR_BYTES,
                HeapCategory.AUTH_EVIDENCE,
                HeapOwnerState.QUEUED,
            )
        }
        return wave
    }

    /** Called only under the short admission monitor. */
    private fun slotFor(source: TtlCredentialIdentitySource): Slot? {
        val current = slots[source]
        if (current != null) return current
        if (slots.size >= MAX_EVIDENCE_LOAD_KEYS) return null
        return Slot().also { slots[source] = it }
    }

    private fun dispatch(source: TtlCredentialIdentitySource, slot: Slot, wave: Wave) {
        // The supervisor is a child of the injected head owner, not a detached or caller-owned job.
        var producer: Job? = null
        try {
            val attempt = Cancellables.runCatchingBestEffort {
                owner.async(lifecycle + ioDispatcher, start = CoroutineStart.UNDISPATCHED) {
                    val accepted = withTimeoutOrNull(EVIDENCE_LOAD_TIMEOUT_MS) {
                        // Start the deadline before dispatcher queueing, then yield admission to peers.
                        yield()
                        if (!freeze(source, slot, wave)) {
                            false
                        } else {
                            val observed = runInterruptible(ioDispatcher) { source.observe(wave.epoch) }
                            currentCoroutineContext().ensureActive()
                            observed != null && source.publish(observed)
                        }
                    }
                    wave.result.complete(accepted == true)
                }
            }
            producer = attempt.getOrNull()
            attempt.exceptionOrNull()?.let { wave.result.completeExceptionally(it) }
        } finally {
            if (producer == null) {
                wave.result.cancel()
                finished(source, slot, wave)
            }
        }
        // Deferred failure is forwarded to every waiter, including cancellation before the body runs.
        producer?.invokeOnCompletion { failure ->
            if (failure != null) wave.result.completeExceptionally(failure) else wave.result.complete(false)
            finished(source, slot, wave)
        }
    }

    private fun freeze(source: TtlCredentialIdentitySource, slot: Slot, wave: Wave): Boolean =
        synchronized(monitor) {
            val retainedSlot = slots[source] === slot
            val retainedWave = slot.scheduled === wave && slot.queued === wave
            if (!retainedSlot || !retainedWave) {
                false
            } else {
                slot.scheduled = null
                slot.queued = null
                slot.running = wave
                wave.retention?.state(HeapOwnerState.RUNNING)
                true
            }
        }

    private fun finished(source: TtlCredentialIdentitySource, slot: Slot, wave: Wave) {
        wave.retention?.ownerFinished()
        val next = synchronized(monitor) {
            if (slots[source] !== slot) return@synchronized null
            if (slot.running === wave) slot.running = null
            if (slot.scheduled === wave) slot.scheduled = null
            if (slot.queued === wave) slot.queued = null
            if (slot.running == null && slot.scheduled == null) {
                val queued = slot.queued
                if (queued == null) slots.remove(source) else slot.scheduled = queued
                queued
            } else {
                null
            }
        }
        if (next != null) dispatch(source, slot, next)
    }

    override fun close() {
        lifecycle.cancel()
    }

    private data class Wave(val epoch: TtlCredentialIdentitySource.Epoch) {
        val result = CompletableDeferred<Boolean>()
        var waiters = 0
        var retention: HeapObservation? = null

        fun join() {
            waiters += 1
            retention?.resize(EVIDENCE_COHORT_SCALAR_BYTES + waiters.toLong() * EVIDENCE_WAITER_SCALAR_BYTES)
        }

        fun detach() {
            waiters -= 1
            retention?.resize(EVIDENCE_COHORT_SCALAR_BYTES + waiters.toLong() * EVIDENCE_WAITER_SCALAR_BYTES)
        }
    }

    private class Slot {
        var running: Wave? = null
        var queued: Wave? = null
        var scheduled: Wave? = null
    }

    private data class Admission(val slot: Slot, val wave: Wave, val dispatch: Wave?)
}
