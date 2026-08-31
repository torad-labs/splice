// PORT-OF: splice/gateway/head/TurnDriver.kt (tearAwareEvents) @ 86f1411 —
// invariants unchanged: the upstream SSE event flow with instrumentation + the G5 pre-frame tear
// rethrow. Split out (HD-24) beside SseRoundDriver, its sole caller. ZeroEventCapture lives in
// ZeroEventCapture.kt.
package splice.gateway.head

import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import splice.core.perf.PerfKeys
import splice.core.util.LogSink
import splice.spi.ClientFrameEmitted
import splice.spi.Provider
import splice.spi.SseReader
import splice.spi.StreamTornBeforeClient
import splice.spi.UpstreamResponse
import java.io.IOException

internal class TearAwareEvents(
    private val provider: Provider,
    private val log: LogSink,
) {
    /** The upstream SSE event flow with instrumentation + the G5 pre-frame tear rethrow: a
     *  transport tear BEFORE any client frame must reach the reissue machinery in UpstreamClient.
     *  The translators swallow IOException into the honest terminal — right for every post-frame
     *  case, but it made the pre-frame reissue unreachable (review 2026-07-19). Rethrown as
     *  [StreamTornBeforeClient] (plain RuntimeException) so no translator catch matches. */
    suspend fun run(
        drive: TurnDrive,
        resp: UpstreamResponse,
        capture: ZeroEventCapture,
        frameEmittedThisRound: ClientFrameEmitted,
    ) =
        SseReader().sseJsonEvents(
            resp.bodyChannel(),
            onBytes = { chunkBytes ->
                drive.slot.touch()
                drive.watchdog.markByte()
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
            onRawText = { text -> capture.appendRaw(text) },
        ).onEach {
            capture.sawEvent = true
            drive.perf.add(PerfKeys.EVENTS_IN, 1)
        }.catch { e ->
            // Per-round (not per-turn) pre-frame test: a continuation round's early tear is as
            // safely reissuable as a first round's — its own body re-POSTs (code-review 2026-07-24).
            if (e is IOException && !frameEmittedThisRound()) {
                throw StreamTornBeforeClient(e)
            }
            throw e
        }
}
