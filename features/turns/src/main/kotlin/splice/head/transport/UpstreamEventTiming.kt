// NEW: V4-456 — one reader-side timing boundary for SSE and WebSocket, without buffering.
package splice.head.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.perf.UpstreamGapEnd

/** Each collection owns its stopwatch. Synchronous delivery is charged separately, never as silence. */
internal class UpstreamEventTiming(private val perf: TurnPerf, private val postedAtMs: Long?) {
    fun observe(events: Flow<JsonObject>): Flow<JsonObject> = flow {
        var previousEventMs = postedAtMs
        var previousContentMs = postedAtMs ?: perf.elapsedMs()
        var ending = UpstreamGapEnd.TORN
        try {
            events.collect { event ->
                val nowMs = perf.elapsedMs()
                val kind = UpstreamEventKinds.kind(event)
                recordGap(previousEventMs, nowMs, kind)
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
                    perf.maxCount(PerfKeys.UP_BLOCKED_MAX_MS, blockedMs)
                    previousEventMs = resumedAtMs
                    // Even ping delivery is downstream work, not part of the wait for content.
                    previousContentMs += blockedMs
                }
            }
            ending = UpstreamGapEnd.COMPLETED
        } catch (cancelled: CancellationException) {
            ending = UpstreamGapEnd.CANCELLED
            throw cancelled
        } finally {
            val endedAtMs = perf.elapsedMs()
            recordGap(previousEventMs, endedAtMs, ending)
            perf.maxCount(PerfKeys.UP_CONTENT_GAP_MAX_MS, endedAtMs - previousContentMs)
        }
    }

    private fun recordGap(previousMs: Long?, nowMs: Long, kind: UpstreamGapEnd) {
        if (previousMs == null) return
        val gapMs = nowMs - previousMs
        perf.maxCount(PerfKeys.UP_GAP_MAX_MS, gapMs, kind)
        perf.maxCount(PerfKeys.UP_GAPS_2S, 0)
        if (gapMs >= LONG_UPSTREAM_GAP_MS) perf.add(PerfKeys.UP_GAPS_2S, 1)
    }
}

// why: the long-gap bucket is two seconds; the reader clock measures milliseconds.
private const val LONG_UPSTREAM_GAP_MS = 2_000L
