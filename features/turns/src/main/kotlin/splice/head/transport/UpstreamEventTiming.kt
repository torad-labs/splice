// NEW: V4-456 — one reader-side timing boundary for SSE and WebSocket, without buffering.
package splice.head.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.perf.UpstreamAttempts
import splice.core.perf.UpstreamGapEnd
import splice.core.perf.observation.PerfAttemptRecorder
import splice.core.perf.observation.PerfEventKind
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Each collection owns its stopwatch. Synchronous delivery is charged separately, never as silence. */
internal class UpstreamEventTiming(private val perf: TurnPerf, private val postedAtMs: Long?) {
    fun observe(events: Flow<JsonObject>): Flow<JsonObject> = flow {
        val observations = perf.upstream.observations
        val tail = UpstreamEventTail(perf, observations, postedAtMs)
        perf.upstream.observeTail(observations, tail)
        var previousEventMs = postedAtMs
        var previousContentMs = postedAtMs ?: perf.elapsedMs()
        var ending = UpstreamGapEnd.TORN
        var deliveryTotalMs = 0L
        try {
            events.collect { event ->
                val nowMs = perf.elapsedMs()
                val kind = UpstreamEventKinds.kind(event)
                recordGap(previousEventMs, nowMs, kind, observations)
                if (UpstreamEventKinds.hasContent(event, kind)) {
                    perf.maxCount(PerfKeys.UP_CONTENT_GAP_MAX_MS, nowMs - previousContentMs)
                    previousContentMs = nowMs
                }
                val deliveryStartedMs = perf.elapsedMs()
                try {
                    emit(event)
                } finally {
                    val resumedAtMs = perf.elapsedMs()
                    val blockedMs = resumedAtMs - deliveryStartedMs
                    deliveryTotalMs += blockedMs
                    perf.maxCount(PerfKeys.UP_BLOCKED_MAX_MS, blockedMs)
                    observations?.observe(PerfEventKind.GAP, mapOf("local_read_block_ms" to blockedMs.toDouble()))
                    previousEventMs = resumedAtMs
                    tail.delivered(resumedAtMs)
                    // Even ping delivery is downstream work, not part of the wait for content.
                    previousContentMs += blockedMs
                }
            }
            ending = UpstreamGapEnd.COMPLETED
        } catch (cancelled: CancellationException) {
            ending = UpstreamGapEnd.CANCELLED
            throw cancelled
        } finally {
            perf.maxCount(PerfKeys.UP_BLOCKED_TOTAL_MS, 0)
            perf.add(PerfKeys.UP_BLOCKED_TOTAL_MS, deliveryTotalMs)
            val endedAtMs = perf.elapsedMs()
            tail.record()
            recordGap(previousEventMs, endedAtMs, ending, null)
            perf.maxCount(PerfKeys.UP_CONTENT_GAP_MAX_MS, endedAtMs - previousContentMs)
        }
    }

    private fun recordGap(previousMs: Long?, nowMs: Long, kind: UpstreamGapEnd, observations: PerfAttemptRecorder?) {
        if (previousMs == null) return
        val gapMs = nowMs - previousMs
        observations?.observe(PerfEventKind.GAP, mapOf("upstream_gap_ms" to gapMs.toDouble()))
        perf.intervals.record(PerfKeys.UP_GAP_MAX_MS, previousMs, nowMs, kind)
        perf.maxCount(PerfKeys.UP_GAPS_2S, 0)
        if (gapMs >= LONG_UPSTREAM_GAP_MS) perf.add(PerfKeys.UP_GAPS_2S, 1)
    }
}

// why: the long-gap bucket is two seconds; the reader clock measures milliseconds.
private const val LONG_UPSTREAM_GAP_MS = 2_000L

/** One logical-reader tail, closed by the transport before its handle or by the reader's finally. */
internal class UpstreamEventTail(
    private val perf: TurnPerf,
    private val observations: PerfAttemptRecorder?,
    postedAtMs: Long?,
) : UpstreamAttempts.TailObservation {
    private val previous = AtomicReference(postedAtMs)
    private val recorded = AtomicBoolean(false)

    /** Synchronous event delivery has finished; that blocked time is not upstream silence. */
    fun delivered(atMs: Long) {
        previous.set(atMs)
    }

    override fun record() {
        if (!recorded.compareAndSet(false, true)) return
        val from = previous.get() ?: return
        val gap = (perf.elapsedMs() - from).coerceAtLeast(0)
        observations?.observe(PerfEventKind.GAP, mapOf("upstream_gap_ms" to gap.toDouble()))
    }
}
