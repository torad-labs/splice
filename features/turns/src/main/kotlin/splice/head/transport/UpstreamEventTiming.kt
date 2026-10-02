// NEW: V4-456 — one reader-side timing boundary for SSE and WebSocket, without buffering.
package splice.head.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.perf.UpstreamGapEnd

/** Each collection owns its stopwatch. Synchronous delivery is charged separately, never as silence. */
internal class UpstreamEventTiming(private val perf: TurnPerf, private val postedAtMs: Long?) {
    fun observe(events: Flow<JsonObject>): Flow<JsonObject> = flow {
        var previousEventMs = postedAtMs
        events.collect { event ->
            val nowMs = perf.elapsedMs()
            val gapMs = previousEventMs?.let { nowMs - it }
            if (gapMs != null) {
                perf.maxCount(PerfKeys.UP_GAP_MAX_MS, gapMs, gapEnd(event))
                perf.maxCount(PerfKeys.UP_GAPS_2S, 0)
                if (gapMs >= LONG_UPSTREAM_GAP_MS) perf.add(PerfKeys.UP_GAPS_2S, 1)
            }
            val deliveryStartedMs = perf.elapsedMs()
            try {
                emit(event)
            } finally {
                val resumedAtMs = perf.elapsedMs()
                perf.maxCount(PerfKeys.UP_BLOCKED_MAX_MS, resumedAtMs - deliveryStartedMs)
                previousEventMs = resumedAtMs
            }
        }
    }

    private fun gapEnd(event: JsonObject): UpstreamGapEnd =
        when ((event["type"] as? JsonPrimitive)?.content) {
            "content_block_delta" -> deltaEnd(event["delta"] as? JsonObject)
            UpstreamGapEnd.CONTENT_BLOCK_START.wire -> UpstreamGapEnd.CONTENT_BLOCK_START
            UpstreamGapEnd.PING.wire -> UpstreamGapEnd.PING
            UpstreamGapEnd.MESSAGE_DELTA.wire -> UpstreamGapEnd.MESSAGE_DELTA
            else -> UpstreamGapEnd.UNKNOWN
        }

    private fun deltaEnd(delta: JsonObject?): UpstreamGapEnd =
        when ((delta?.get("type") as? JsonPrimitive)?.content) {
            UpstreamGapEnd.THINKING_DELTA.wire -> UpstreamGapEnd.THINKING_DELTA
            UpstreamGapEnd.TEXT_DELTA.wire -> UpstreamGapEnd.TEXT_DELTA
            UpstreamGapEnd.INPUT_JSON_DELTA.wire -> UpstreamGapEnd.INPUT_JSON_DELTA
            else -> UpstreamGapEnd.UNKNOWN
        }
}

// why: the long-gap bucket is two seconds; the reader clock measures milliseconds.
private const val LONG_UPSTREAM_GAP_MS = 2_000L
