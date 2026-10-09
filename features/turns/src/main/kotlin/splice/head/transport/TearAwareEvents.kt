// PORT-OF: splice/gateway/head/TurnDriver.kt (tearAwareEvents) @ 86f1411 —
// invariants unchanged: the upstream SSE event flow with instrumentation + the G5 pre-frame tear
// rethrow. Split out (HD-24) beside SseRoundDriver, its sole caller. ZeroEventCapture lives in
// ZeroEventCapture.kt.
package splice.head.transport

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.JsonObject
import splice.core.perf.PerfKeys
import splice.core.util.ERR_SNIPPET
import splice.core.util.LogSink
import splice.head.turn.TurnDrive
import splice.upstream.ClientFrameEmitted
import splice.upstream.Provider
import splice.upstream.sse.SseReader
import java.io.IOException

internal class TearAwareEvents(
    private val provider: Provider,
    private val log: LogSink,
) {
    /** The upstream SSE event flow with instrumentation + the G5 pre-frame tear: a transport tear BEFORE any
     *  client frame must reach the reissue machinery in UpstreamClient. The translators swallow IOException into the
     *  honest terminal — right for every post-frame case, but it made the pre-frame reissue unreachable (review
     *  2026-07-19). So the tear is recorded in [end] and the flow completes, and an oversized frame the reader
     *  stopped at is recorded there too; [SseRoundConsume] reads [end] after the translator returns. */
    fun run(
        drive: TurnDrive,
        body: ByteReadChannel,
        capture: ZeroEventCapture,
        frameEmittedThisRound: ClientFrameEmitted,
        end: RoundEnd = RoundEnd(),
    ): Flow<JsonObject> {
        val reader = SseReader(
            onBytes = { chunkBytes ->
                // Bytes TOUCH the slot (liveness) and stamp FIRST_BYTE; they do not pick the
                // watchdog tier. A Responses handshake (response.created) is bytes with no output,
                // and letting it flip the tier reaped every silent compaction — see Watchdog.kt.
                drive.slot.received()
                drive.perf.markOnce(PerfKeys.FIRST_BYTE)
                drive.perf.add(PerfKeys.SSE_BYTES_IN, chunkBytes.toLong())
            },
            // G9: count every skipped malformed frame; log the first snippet once per ATTEMPT
            // (the capture is attempt-scoped, SseRoundConsume constructs one per consume; DR-90
            // rider) — never influences [splice.core.turn.TurnOutcome] (L3 stays intact; the skip
            // is silent to the client).
            onMalformed = { sn ->
                drive.perf.add(PerfKeys.FRAMES_SKIPPED, 1)
                if (!capture.malformedLogged) {
                    log("[${provider.key}] malformed SSE frame skipped: ${sn.take(ERR_SNIPPET)}\n")
                }
                capture.malformedLogged = true
            },
            // V4-174: the trace takes the WHOLE response text, so the observer stays subscribed
            // past the point the zero-event capture has seen enough (it returns false from there).
            onRawText = { text ->
                val captureWants = capture.appendRaw(text)
                drive.trace?.responseText(text)
                captureWants || drive.trace != null
            },
        )
        val events = reader.sseJsonEvents(body)
        return UpstreamEventTiming(drive.perf, end.postedAtMs).observe(events).onEach { event ->
            UpstreamProgress.observe(event, drive.watchdog)
            capture.sawEvent = true
            drive.perf.add(PerfKeys.EVENTS_IN, 1)
        }.catch { e ->
            // Per-round (not per-turn) pre-frame test: a continuation round's early tear is as
            // safely reissuable as a first round's — its own body re-POSTs (code-review 2026-07-24).
            //
            // DR-7 rider: NOT when the watchdog reaped this round. Reaping now aborts the body
            // channel, which surfaces here as exactly the IOException a transport tear does — so
            // without this test a round stalled BEFORE its first client frame would enter the G5
            // reissue path and silently re-POST, racing the salvage-and-continue decision the
            // terminal outcome is about to make. A stall is not a tear: the socket was fine, the
            // backend went quiet, and the round has an outcome to report.
            if (e !is IOException || !reissuable(drive, frameEmittedThisRound)) throw e
            end.torn = e
        }.onCompletion { end.oversized = reader.exceeded }
    }

    /** Whether an I/O tear is one this round may silently re-POST: before any client frame, and not caused by
     *  the watchdog. */
    private fun reissuable(drive: TurnDrive, frameEmittedThisRound: ClientFrameEmitted): Boolean =
        !frameEmittedThisRound() && drive.watchdog.fired == null
}
