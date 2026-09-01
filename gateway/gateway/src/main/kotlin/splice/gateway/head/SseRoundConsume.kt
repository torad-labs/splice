// NEW: the SSE success-path consume (slot/start/watchdog/translator/zero-event).
// Split from SseRoundDriver (concentration, 2026-08-19) so neither file is
// billed for the other's subsystems. Same-package.
package splice.gateway.head

import kotlinx.coroutines.Job
import splice.core.perf.PerfKeys
import splice.core.turn.TurnOutcome
import splice.spi.Provider
import splice.spi.TurnSignals
import splice.spi.UpstreamResponse
import java.io.IOException

internal class SseRoundConsume(
    private val provider: Provider,
    private val zeroEvent: ZeroEventFailure,
    private val telemetry: TurnTelemetry,
    private val tearAwareEvents: TearAwareEvents,
) {
    suspend fun consume(inputs: WsRoundInputs, resp: UpstreamResponse): TurnOutcome {
        val drive = inputs.drive
        drive.slot.touch()
        // Upstream answered 2xx and the stream is ours — open the turn on the wire immediately
        // instead of waiting for the first content block. This block runs ONLY on success (see
        // UpstreamClient.attemptRequest), so a pre-stream failure still writes its error frame
        // first and nothing here pre-empts it. Recovers p50 2840ms of frozen screen per codex
        // turn; also gives the keepalive pinger an opened stream to ping into.
        drive.emitter.ensureStarted()
        // Fresh upstream round/attempt: reset the idle tier so this round's (possibly long,
        // silent) prefill is judged against firstByteTimeout, not the short streamIdle a prior
        // round's first byte would otherwise pin it to. totalCap still spans the whole turn.
        drive.watchdog.resetFirstByte()
        // DR-7: the idle watchdog reaps THIS ROUND, not the turn. It used to cancel inputs.turnJob,
        // which killed driveTurn along with the round — so the translator never returned, the
        // salvaged reasoning died with it, and the fold loop had nothing left to continue from. The
        // round job carries no work of its own; it is a cancellation SIGNAL, and the thing it
        // signals is "stop reading this body". The translator folds the resulting IOException into
        // an honest terminal, and the terminal decision then sees the watchdog sentinel and reports
        // a stall WITH its partial.
        //
        // The cause type is DEFENSIVE, not load-bearing, and this comment used to claim otherwise:
        // it said a CancellationException here would propagate into the turn coroutine and
        // reproduce the bug. codex-splice disproved that by swapping the cause and finding the
        // whole acceptance suite still green — every translator already swallows a cancellation
        // when the watchdog has fired (ResponsesStreamTranslator does it explicitly). IOException
        // is kept because it does not DEPEND on that swallow: it reads as an ordinary torn read on
        // any translator, including one written later that forwards cancellation faithfully.
        //
        // Parented to turnJob so the reverse direction still holds: a client hang-up or the
        // whole-turn totalCap cancels the turn, which cancels this, which aborts the read.
        val roundJob = Job(inputs.turnJob)
        val body = resp.bodyChannel()
        roundJob.invokeOnCompletion { cause -> if (cause != null) body.cancel(IOException(REAPED, cause)) }
        val poller = drive.watchdog.launchIn(inputs.scope, drive.slot, roundJob)
        // Leak wall (review 2026-07-19): the attempt's poller dies on EVERY exit of this
        // block — a torn-then-reissued stream used to leak it into `self`, pinning the
        // admission slot ~streamIdle past turn completion. (The client pinger is whole-turn
        // now — launched once in driveOneTurn, cancelled there.)
        try {
            // DR-90: baseline THIS ATTEMPT, not the round — UpstreamClient reuses [inputs] across
            // stream reissues (G5), so the round-scoped [WsRoundInputs.eventsBase] counts attempt
            // 1's events against a reissued attempt and skips the G2 zero-event reclassify exactly
            // when the reissue came back as a dead-head body (HTML login 200). The inputs field
            // stays for the WS overlay, which runs at most once per round and always FIRST.
            val eventsBase = drive.perfCounter(PerfKeys.EVENTS_IN)
            val capture = ZeroEventCapture()
            val events = tearAwareEvents.run(drive, body, capture, inputs.frameEmittedThisRound)
            val signals = TurnSignals(
                watchdogFired = { drive.watchdog.fired },
                clientGone = { drive.channel.clientGone.get() },
            )
            val rawOutcome = provider.streamTranslator(drive.meta, signals).driveTurn(events, inputs.sink)
            drive.perf.mark(PerfKeys.STREAM_END)
            return zeroEvent.classify(
                drive,
                rawOutcome,
                capture.snippet.toString(),
                drive.perfCounter(PerfKeys.EVENTS_IN) - eventsBase,
                telemetry,
            )
        } finally {
            poller.cancel()
            // Completes with no cause on every ordinary exit, so the handler above leaves a healthy
            // channel alone; a no-op if the watchdog already cancelled it.
            roundJob.complete()
        }
    }
}

private const val REAPED = "round reaped by the idle watchdog"
