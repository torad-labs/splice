// PORT-OF: splice/gateway/head/TurnDriver.kt (postRound, the wsDriver field) @ 86f1411 —
// invariants unchanged: one upstream round over SSE — POST → watchdog/pinger → translator →
// zero-event classify — trying the WebSocket overlay first via [WsRoundDriver]. Exact precedent:
// WsRoundDriver.kt was extracted from TurnDriver for this same reason (wave WS-3) and this file
// sits next to it. Consume/post wiring live in SseRoundConsume/SseRoundPost (concentration, 2026-08-19).
package splice.gateway.head

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import splice.core.perf.PerfKeys
import splice.core.turn.ErrorType
import splice.core.turn.TurnOutcome
import splice.spi.ClientFrameEmitted
import splice.spi.StreamTornBeforeClient
import splice.spi.WireSink
import java.io.IOException

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
        // V4-67: the same per-round discipline for the upstream BYTES this round read. Only the
        // 2xx body path adds to this counter (SseReader.onBytes, inside consume), so a round whose
        // count moved is a round the upstream handed a stream to — the fact [tearOutcome] needs and
        // the one the connect phase must not be confused with.
        val bytesInBase = drive.perfCounter(PerfKeys.SSE_BYTES_IN)
        val frameEmittedThisRound = ClientFrameEmitted { drive.perfCounter(PerfKeys.CONTENT_FRAMES_OUT) > framesBase }
        // ws-transport WS-3: try the WebSocket overlay first. It returns null for "ride SSE" on
        // EVERY failure, and WsRoundNeedsSse when a round failed before the client saw content —
        // both land on the unchanged post() below, so SSE keeps sole ownership of retry, the
        // single-flight 401 refresh and the shared 429 cooldown (L5). With the quirk off,
        // provider.wsRunner is null and not one line of this executes.
        val inputs = WsRoundInputs(drive, bodyJson, sink, self, turnJob, frameEmittedThisRound, eventsBase)
        wsDriver.run(inputs)?.let { return it }
        return try {
            ssePost.post(inputs)
        } catch (e: StreamTornBeforeClient) {
            tearOutcome(e, drive, framesBase, bytesInBase) ?: throw e
        } catch (e: IOException) {
            tearOutcome(e, drive, framesBase, bytesInBase) ?: throw e
        }
    }

    /**
     * V4-67: a connection-class tear that ESCAPED this round, turned into the round's OUTCOME so
     * the re-anchor loop gets its chance — or null, meaning "let it escape exactly as it does
     * today".
     *
     * MEASURED, not inferred. The row that opened this said a post-content tear escapes; it does
     * not. Every translator's collect catches IOException and reports the round as a truncation
     * WITH its partial (PassthroughStreamTranslator: `stream ended without a terminal event`), so
     * V4-41's salvage already covers the tear that happens once text is flowing — driven end to
     * end by MidStreamTearContinuesTest against unmodified code before this method existed. What
     * has NO retry at all is the tear BEFORE the first content frame: TearAwareEvents rethrows it
     * as [StreamTornBeforeClient] so no translator catch can see it, and then the G5 interlock
     * declines to re-issue unless the throwable is in its six-name transport allowlist. A torn
     * chunk stream is not in that set, so `rethrowUnlessRetryableTransport` rethrows with
     * `deadlineHit = streamHandedOff` and the turn dies at attempts=1 with every budget unspent.
     * That is the operator's own 2026-09-16 turn (claude-deepseek session 4b09e038: frames_out=3,
     * content_frames_out=1 — which is the error frame itself — attempts=1, and no reissue or
     * transport notice anywhere in the journal).
     *
     * THE TWO GATES ARE THE SAFETY ARGUMENT, and they are the reason this cannot make anything
     * worse:
     *  - NO CONTENT FRAME THIS ROUND. A proxy cannot un-send bytes. This seam holds no salvage
     *    buffer (those live in the translator, one layer down), so the only continuation it can
     *    offer is the empty partial both controllers turn into a VERBATIM whole-stream restart —
     *    which duplicates nothing only while the client has been shown nothing. Once content is on
     *    the wire the honest escape is the right answer and the throw is re-raised untouched.
     *  - BYTES READ THIS ROUND. A failure with no upstream body bytes never got a stream at all;
     *    it belongs to the connect phase, which has just spent its own four attempts on it.
     *    Converting that one would hand a dead upstream the re-anchor budget on top.
     *
     * NEVER BELOW STATUS QUO holds by construction: this only produces a Failure, and a Failure
     * with no controller, no budget or a declining controller is finished as the honest error the
     * turn would have ended with anyway. muse never sees a prefill here — an empty partial takes
     * the restart branch of [splice.dialect.passthrough.PassthroughReanchorController] before the
     * quirk is ever read.
     *
     * CANCELLATION IS NOT A TEAR and cannot reach this method: CancellationException is an
     * IllegalStateException, neither an [IOException] nor a [StreamTornBeforeClient], so it
     * propagates past both catch clauses exactly as TurnFailures already orders it.
     */
    private fun tearOutcome(
        e: Throwable,
        drive: TurnDrive,
        framesBase: Long,
        bytesInBase: Long,
    ): TurnOutcome.Failure? {
        val contentEmitted = drive.perfCounter(PerfKeys.CONTENT_FRAMES_OUT) > framesBase
        val streamRead = drive.perfCounter(PerfKeys.SSE_BYTES_IN) > bytesInBase
        if (contentEmitted || !streamRead) return null
        // The same detail TurnConnEnd would have printed, so the ending a declined continuation
        // still reaches names the same failure.
        val detail = (e as? StreamTornBeforeClient)?.cause?.message ?: e.message ?: NO_TEAR_DETAIL
        return TurnOutcome.Failure(
            ErrorType.OVERLOADED,
            "upstream connection failed ($detail) — retry",
            // Locally synthesized: the upstream reported nothing, the socket did (G20 health split).
            providerReported = false,
            // Deliberately EMPTY, and the whole reason the gates above are what they are: nothing
            // was shown to the client, so the continuation is a restart and not a replay.
            partial = TurnOutcome.PartialRound(),
            // The ending keeps the tag this failure would have carried had it escaped: an
            // unrecovered tear stays greppable as conn-reset in the perf row, which is the only
            // string in the journal that names this failure class.
            connReset = true,
        )
    }
}

private const val NO_TEAR_DETAIL = "no detail"
