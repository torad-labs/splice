// PORT-OF: splice/gateway/head/TurnDriver.kt (postRound, the wsDriver field) @ 86f1411 —
// invariants unchanged: one upstream round over SSE — POST → watchdog/pinger → translator →
// zero-event classify — trying the WebSocket overlay first via [WsRoundDriver]. Exact precedent:
// WsRoundDriver.kt was extracted from TurnDriver for this same reason (wave WS-3) and this file
// sits next to it. Consume/post wiring live in SseRoundConsume/SseRoundPost (concentration, 2026-08-19).
package splice.gateway.head

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import splice.core.perf.PerfKeys
import splice.core.turn.TurnOutcome
import splice.spi.ClientFrameEmitted
import splice.spi.WireSink

internal class SseRoundDriver(
    private val wsDriver: WsRoundDriver,
    private val ssePost: SseRoundPost,
) {
    /** One upstream round driven into [sink]: POST → watchdog/pinger → translator → zero-event
     *  classify. The non-fold path and every fold round share this; the caller's [FinishTurn] runs
     *  so the fold loop emits exactly ONE terminal across all rounds (L3). */
    suspend fun postRound(
        drive: TurnDrive,
        bodyJson: String,
        sink: WireSink,
        self: CoroutineScope,
        turnJob: Job,
    ): TurnOutcome {
        // Per-ROUND baselines (code-review 2026-07-24): drive.perf is one cumulative TurnPerf
        // shared across re-anchor rounds — the global FIRST_FRAME mark and EVENTS_IN counter go
        // permanently stale after round 1, which (a) denied continuation rounds the safe
        // pre-frame reissue and (b) skipped the G2 zero-event reclassifier for them. Frame/event
        // facts for reissue and zero-event classification are judged against THIS round only.
        // CONTENT frames, not all frames: message_start now lands at upstream-handoff, so keying
        // G5 off FRAMES_OUT would report "client saw output" before a single token existed and
        // silently kill pre-content reissue (HeadServerReviewTest: torn-before-client must stay a
        // retryable overloaded_error, not a raw api_error).
        val framesBase = drive.perfCounter(PerfKeys.CONTENT_FRAMES_OUT)
        // DR-90: this capture feeds the WS overlay only (at most one ws attempt per round, always
        // first); the SSE consume re-baselines per ATTEMPT internally, because UpstreamClient
        // reuses these inputs across G5 stream reissues.
        val eventsBase = drive.perfCounter(PerfKeys.EVENTS_IN)
        val frameEmittedThisRound = ClientFrameEmitted { drive.perfCounter(PerfKeys.CONTENT_FRAMES_OUT) > framesBase }
        // ws-transport WS-3: try the WebSocket overlay first. It returns null for "ride SSE" on
        // EVERY failure, and WsRoundNeedsSse when a round failed before the client saw content —
        // both land on the unchanged post() below, so SSE keeps sole ownership of retry, the
        // single-flight 401 refresh and the shared 429 cooldown (L5). With the quirk off,
        // provider.wsRunner is null and not one line of this executes.
        val inputs = WsRoundInputs(drive, bodyJson, sink, self, turnJob, frameEmittedThisRound, eventsBase)
        wsDriver.run(inputs)?.let { return it }
        return ssePost.post(inputs)
    }
}
