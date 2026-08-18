// PORT-OF: splice/gateway/head/TurnDriver.kt (postRound, the wsDriver field) @ 86f1411 —
// invariants unchanged: one upstream round over SSE — POST → watchdog/pinger → translator →
// zero-event classify — trying the WebSocket overlay first via [WsRoundDriver]. Exact precedent:
// WsRoundDriver.kt was extracted from TurnDriver for this same reason (wave WS-3) and this file
// sits next to it.
package splice.gateway.head

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import splice.core.perf.PerfKeys
import splice.core.turn.TurnOutcome
import splice.core.util.LogSink
import splice.gateway.usage.UsageStore
import splice.spi.ClientFrameEmitted
import splice.spi.Provider
import splice.spi.TurnSignals
import splice.spi.UpstreamClient
import splice.spi.WireSink

internal class SseRoundDriver(
    private val provider: Provider,
    private val log: LogSink,
    private val upstream: UpstreamClient,
    private val usageStore: UsageStore,
    private val failures: TurnFailures,
    private val telemetry: TurnTelemetry,
    private val tearAwareEvents: TearAwareEvents,
) {
    private val wsDriver = WsRoundDriver(
        provider,
        log,
        ZeroEventClassifier { drive, outcome, bodyText, eventsBase ->
            failures.classifyZeroEventFailure(drive, outcome, bodyText, eventsBase, telemetry)
        },
    )

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
        val eventsBase = drive.perfCounter(PerfKeys.EVENTS_IN)
        val frameEmittedThisRound = ClientFrameEmitted { drive.perfCounter(PerfKeys.CONTENT_FRAMES_OUT) > framesBase }
        // ws-transport WS-3: try the WebSocket overlay first. It returns null for "ride SSE" on
        // EVERY failure, and WsRoundNeedsSse when a round failed before the client saw content —
        // both land on the unchanged post() below, so SSE keeps sole ownership of retry, the
        // single-flight 401 refresh and the shared 429 cooldown (L5). With the quirk off,
        // provider.wsRunner is null and not one line of this executes.
        wsDriver.run(
            WsRoundInputs(drive, bodyJson, sink, self, turnJob, frameEmittedThisRound, eventsBase),
        )?.let { return it }
        return upstream.post(
            url = provider.upstreamUrl,
            bodyJson = bodyJson,
            auth = provider.auth,
            extraHeaders = { creds -> provider.extraHeaders(creds) + drive.turnHeaders },
            onRetry = { log("[${provider.key}] $it\n") },
            perf = drive.perf,
            clientFrameEmitted = frameEmittedThisRound,
            amendBodyOnFailure = provider::amendBodyOnFailure,
        ) { resp ->
            drive.slot.touch()
            // Upstream answered 2xx and the stream is ours — open the turn on the wire immediately
            // instead of waiting for the first content block. This block runs ONLY on success (see
            // UpstreamClient.attemptRequest), so a pre-stream failure still writes its error frame
            // first and nothing here pre-empts it. Recovers p50 2840ms of frozen screen per codex
            // turn; also gives the keepalive pinger an opened stream to ping into.
            drive.emitter.ensureStarted()
            // Persist upstream rate-limit headers for /api/usage + statusline soft-warn (Node
            // codex-proxy wired this; the Kotlin split dropped the call site).
            usageStore.persistRateLimit { name -> resp.header(name) }
            // Fresh upstream round/attempt: reset the idle tier so this round's (possibly long,
            // silent) prefill is judged against firstByteTimeout, not the short streamIdle a prior
            // round's first byte would otherwise pin it to. totalCap still spans the whole turn.
            drive.watchdog.resetFirstByte()
            val poller = drive.watchdog.launchIn(self, drive.slot, turnJob)
            // Leak wall (review 2026-07-19): the attempt's poller dies on EVERY exit of this
            // block — a torn-then-reissued stream used to leak it into `self`, pinning the
            // admission slot ~streamIdle past turn completion. (The client pinger is whole-turn
            // now — launched once in driveOneTurn, cancelled there.)
            try {
                val capture = ZeroEventCapture()
                val events = tearAwareEvents.run(drive, resp, capture, frameEmittedThisRound)
                val signals = TurnSignals(
                    watchdogFired = { drive.watchdog.fired },
                    clientGone = { drive.channel.clientGone.get() },
                )
                val rawOutcome = provider.streamTranslator(drive.meta, signals).driveTurn(events, sink)
                drive.perf.mark(PerfKeys.STREAM_END)
                failures.classifyZeroEventFailure(drive, rawOutcome, capture.snippet.toString(), eventsBase, telemetry)
            } finally {
                poller.cancel()
            }
        }
    }
}
