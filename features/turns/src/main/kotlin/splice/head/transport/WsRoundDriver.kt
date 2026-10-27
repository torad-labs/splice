// NEW: (ws-transport WS-3, 2026-08-01) one round over the WebSocket overlay, extracted from
// TurnDriver for the same reason TurnDriver was itself split out of HeadServer: the class was at
// its LargeClass/TooManyFunctions ceiling and this is a self-contained drive, not more turn logic.
//
// THE CONTRACT, and it is the whole safety argument for the overlay: [run] returns null for
// "serve this round over SSE", and every failure produces exactly that — the quirk is off, no
// runner exists, credentials are unavailable, the transport declined, or the round failed before
// the client saw content. Nothing here can produce a client-visible failure the SSE path would not
// have produced (NEVER-BELOW-STATUS-QUO), and SSE keeps sole ownership of retry, the single-flight
// 401 refresh and the shared 429 cooldown, which live in UpstreamClient and are deliberately NOT
// reimplemented here. A round the upstream answers (response.created) only reports that answer to
// its credential's hold, as an HTTP answer does, so a plan hold ends whichever transport answered.
package splice.head.transport

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import splice.core.perf.PerfKeys
import splice.core.turn.TurnOutcome
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.WallClock
import splice.head.turn.TurnDrive
import splice.head.turn.ZeroEventClassifier
import splice.upstream.PathLiveness
import splice.upstream.Provider
import splice.upstream.RoundBody
import splice.upstream.WsRoundAbort
import splice.upstream.WsRoundRunner
import splice.upstream.transport.HeaderRedaction

internal class WsRoundDriver(
    private val provider: Provider,
    private val log: LogSink,
    classifyZeroEvent: ZeroEventClassifier,
    answerObserver: StreamAnswerObserver = StreamAnswerObserver { _, _, _, _ -> },
    clock: WallClock = WallClock(System::currentTimeMillis),
    holds: CredentialHolds = CredentialHolds { _, _ -> null },
) {
    private val roundDrive = WsRoundDrive(provider, classifyZeroEvent)
    private val roundAcquire = WsRoundAcquire(provider, answerObserver, clock, holds)

    /** Run the round, or null to fall through to the SSE path. */
    suspend fun run(inputs: WsRoundInputs): TurnOutcome? {
        val runner = provider.wsRunner ?: return null
        val drive = inputs.drive
        clearAccountBoundary(runner, drive)
        val first = driveRound(runner, drive, inputs)
        // Marcos, 2026-10-27: a socket that failed before any output (the 60-minute limit, a close, a drop) is
        // replaced at once on the same login, with no backoff, the way codex-rs reconnects. HTTP is for a second
        // failure. The first round already reported its bypass, so the chain is clear and the new socket full-sends.
        val result = if (first is WsRoundResult.NeedsSse && first.freshSocket) {
            drive.perf.add(PerfKeys.WS_FRESH_SOCKET_RETRIES, 1)
            log(
                "[${provider.key}] websocket ${first.reasonCode} before any output (${first.detail}); " +
                    "sending again on a fresh socket, ${who(drive)}\n",
            )
            driveRound(runner, drive, inputs)
        } else {
            first
        }
        return when (result) {
            null -> null
            is WsRoundResult.Streamed -> result.outcome
            is WsRoundResult.NeedsSse -> bypassToSse(drive, result)
        }
    }

    /** Every round HTTP serves is a fallback, so it warns and counts. A socket that could not take the round failed
     *  before any output, like a tear, so it gets a fresh socket first. A frame the peer refused as too large would be
     *  refused again, and any other bypass has no socket fault to replace, so those go straight on. */
    private fun bypassOf(drive: TurnDrive, declines: Long, refusals: Long): WsRoundResult.NeedsSse = when {
        drive.perf.count(PerfKeys.WS_REFUSED_TOO_LARGE) > refusals ->
            WsRoundResult.NeedsSse(TOO_LARGE, "frame_too_large")
        drive.perf.count(PerfKeys.WS_TRANSPORT_DECLINES) > declines ->
            WsRoundResult.NeedsSse(DECLINED, "transport_declined", freshSocket = true)
        else -> WsRoundResult.NeedsSse(BYPASSED, "socket_bypassed")
    }

    /** The session and login a fallback line names, so one conversation's lines can be found on one login. */
    private fun who(drive: TurnDrive): String {
        val login = drive.accountLabel.of(drive.account?.account?.label) ?: "-"
        return "session=${drive.meta.scope.sessionId ?: "-"} login=$login"
    }

    /** The round body, extracted (V4-114 continuation) so [run] stays inside detekt's
     *  CyclomaticComplexMethod budget — the `when`, the accepted-null guard and the finally guard
     *  all move here. Behaviour is unchanged; the reporting contract below still binds. */
    private suspend fun driveRound(
        runner: WsRoundRunner,
        drive: TurnDrive,
        inputs: WsRoundInputs,
    ): WsRoundResult? {
        // CON-003 + DR-91: every exit below reports the round exactly once, INCLUDING a
        // cancellation that lands while credentials()/attempt() are in flight — the WS send may
        // already have advanced the runner's chaining state, and an unreported unwind left the
        // next turn chained onto a round that never finished. [roundDrive] reports ok=true on a
        // clean terminal and the sentinel path reports a bypass; anything else leaves the flag
        // false and the finally clears the chain, which is [WsRoundRunner.roundEnded]'s stated
        // contract ("anything else must clear the chaining state"). Reported via a flag rather
        // than a catch so the exception itself continues untouched.
        var reported = false
        var poller: Job? = null
        var roundJob: CompletableJob? = null
        // V4-174: the trace's WebSocket round, opened once the runner accepted the send and closed
        // in the finally with whatever ended it; a bypassed round (SSE serves it) is never opened.
        var traced = false
        var ending: String? = null
        try {
            // Credentials come from the provider's auth surface, NOT from a WS-side refresh: L5
            // keeps the single-flight 401 refresh in UpstreamClient, so a missing/expired
            // credential here simply rides SSE and gets refreshed there.
            val declines = drive.perf.count(PerfKeys.WS_TRANSPORT_DECLINES)
            val refusals = drive.perf.count(PerfKeys.WS_REFUSED_TOO_LARGE)
            val acquired = acquireCounted(runner, drive, inputs.body)
            if (acquired == null) {
                // SSE is about to serve this round, so the conversation advances outside any chain.
                runner.roundBypassed(drive.meta)
                reported = true
                return bypassOf(drive, declines, refusals)
            }
            val accepted = acquired.round
            drive.slot.touch()
            drive.perf.add(PerfKeys.ATTEMPTS, 1)
            drive.trace?.wsRoundStarted()
            traced = true
            // Start the client while the acquired cold flow is being collected, not before: if the
            // start write throws or is cancelled, the exception unwinds through the transport flow's
            // onCompletion and poisons its busy lease instead of stranding the connection forever.
            val events = roundDrive.startingEvents(accepted, runner, drive, acquired.answer)
            drive.watchdog.resetRound()
            // DR-7 round 2: the idle watchdog reaps THIS ROUND here too, the same way
            // SseRoundConsume does. It used to cancel inputs.turnJob, so a WS stall killed the
            // translator along with the round and the salvage died with it — the SSE path earned
            // salvage-and-continue and this one was left behind because a WS round's events are an
            // opaque Flow with no per-round abort. The seam was widened rather than the behaviour
            // left wrong: an accepted round now carries its own [WsRound.abort], and the Responses
            // implementation kills that round's socket — which WsRoundStream turns into an
            // IOException out of the flow. Same shape as cancelling an SSE body: a torn read the
            // translator folds into an honest terminal, and NOT a cancellation, which would take
            // the collector down and lose the partial with it.
            //
            // The abort rides the ROUND rather than being a runner method the head calls by name,
            // because the head has no name precise enough — one conversation can hold two live
            // rounds on two sockets (compact and non-compact want different handshake headers), so
            // a lookup keyed by conversation aborts whichever was registered last. Closing over the
            // round is also what the SSE path does with its response body.
            //
            // Parented to turnJob so the reverse direction still holds: a client hang-up or the
            // whole-turn totalCap cancels the turn, which cancels this, which aborts the round.
            //
            // NOT covered, and named rather than left to be discovered: the window INSIDE attempt()
            // — connect, send, and the wait for the first event — has no round job yet, so a stall
            // there is still owned by the transport's OWN budgets, which end it with a null and
            // ride SSE. That is the status quo and it is bounded; this repair is about the window
            // after the round is accepted.
            //
            // DR-182: "the transport's own first-event timeout" is what this said, and it was true
            // of two of those three steps. The send had no budget: sendText's future completes when
            // the write reaches the transport, so a peer that stops reading left it pending with
            // nothing thrown, and the first-event budget never started because it is applied after
            // the send returns. The only remaining bound was the totalCap poller below, so a
            // stalled send burned the whole upstream timeout and failed the turn instead of
            // degrading to SSE in seconds. The transport now bounds the send too (SEND_TIMEOUT_MS),
            // which is what makes this paragraph true as written.
            val round = Job(inputs.turnJob)
            roundJob = round
            round.invokeOnCompletion { cause -> if (cause != null) abortRound(inputs, accepted.abort) }
            // The round's socket pulse rides along (2026-09-06): a silent round on a path the server
            // is still pinging is held, not reaped — see TurnWatchdog.launchIn.
            poller = drive.watchdog.launchIn(
                inputs.scope,
                drive.slot,
                round,
                inputs.frameEmittedThisRound,
                PathLiveness(accepted.pathPulse),
            )
            // V4-114: the fallback is a VALUE on drive()'s return type, so this branch is
            // compiler-checked — adding a WsRoundResult case can no longer slip past a `catch` on
            // a name. Both arms report the round (Streamed via roundEnded inside drive, NeedsSse
            // via roundBypassed), so `reported` is set once after the when returns — after the
            // report, so an exception still leaves it false and the finally clears the chain.
            val result = roundDrive.drive(inputs, runner, events)
            if (result is WsRoundResult.NeedsSse) {
                ending = result.detail
                // The round's buffered draft belongs to a round the client never saw; the next round answers the
                // turn on this same sink, so what this one buffered must not be flushed beside that answer.
                inputs.sink.discard()
                runner.roundBypassed(drive.meta)
            }
            reported = true
            return result
        } finally {
            if (!reported) runner.roundEnded(drive.meta, ok = false)
            if (traced) closeTrace(drive, inputs, ending, reported)
            poller?.cancel()
            // Completes with no cause on every ordinary exit, so the handler above leaves a healthy
            // connection alone; a no-op if the watchdog already cancelled it. Without this the job
            // stays an incomplete child of turnJob and the turn cannot finish.
            roundJob?.complete()
        }
    }

    /** The round the websocket took, or null when SSE serves it. A websocket round never encodes bytes, so a round it
     *  took counts the text it was handed, and so does one whose send threw after its frame may have gone out. SSE
     *  counts a round it serves from the bytes its post writes, so a declined round is never counted here. */
    private suspend fun acquireCounted(runner: WsRoundRunner, drive: TurnDrive, body: RoundBody): WsAcquiredRound? {
        val text = body.text
        var declined = false
        try {
            val acquired = roundAcquire.acquire(runner, drive, text)
            declined = acquired == null
            return acquired
        } finally {
            if (!declined) drive.perf.setCount(PerfKeys.UPSTREAM_REQ_BYTES, body.byteSize())
        }
    }

    /** The abort must run even after its owner is cancelled, but must not throw through Job's handlers.
     *  It cannot suspend, so an undispatched root completes before returning and reports failure by class. */
    private fun abortRound(inputs: WsRoundInputs, abort: WsRoundAbort) {
        val handler = CoroutineExceptionHandler { _, failure ->
            log("[${provider.key}] websocket round abort failed: ${SafeFailureText.render(failure)}\n")
        }
        inputs.scope.launch(NonCancellable + handler, CoroutineStart.UNDISPATCHED) {
            abort.abort()
        }
    }

    /** V4-174: the trace's WebSocket round closes with whatever ended it — the NeedsSse detail, an
     *  unwind before the report (a cancellation or a throw, which the finally cannot name), or
     *  nothing for a clean terminal. Headers redacted by name class like the SSE path's. */
    private fun closeTrace(drive: TurnDrive, inputs: WsRoundInputs, ending: String?, reported: Boolean) {
        val failure = ending ?: if (reported) null else "round unwound before it reported (cancelled or thrown)"
        drive.trace?.wsAttempt(inputs.body.text, HeaderRedaction.redact(drive.turnHeaders), failure)
    }

    /** The round failed while the client had seen nothing, so it can still be RE-SERVED with the
     *  full recovery machinery. Serving the failure raw over the WebSocket instead would bypass
     *  retry/refresh/cooldown entirely — the one way this overlay could land BELOW the status quo.
     *  The line names the server's failure terminal (type, code, message): a chained turn the server
     *  refuses re-reads the whole transcript cold over SSE, and which turns it refuses is the
     *  finding. Always returns null — the caller's `run` falls through to the SSE path. */
    private fun bypassToSse(drive: TurnDrive, result: WsRoundResult.NeedsSse): TurnOutcome? {
        drive.perf.add(PerfKeys.WS_PRE_OUTPUT_FALLBACKS, 1)
        log(
            "[${provider.key}] warning: websocket fallback to HTTP code=${result.reasonCode} ${who(drive)} " +
                "(${result.detail}); serving over SSE\n",
        )
        return null
    }

    private fun clearAccountBoundary(runner: WsRoundRunner, drive: TurnDrive) {
        if (drive.account?.cacheCold == true && drive.claimAccountBoundary()) runner.roundBypassed(drive.meta)
    }
}

// The detail of a round the socket could not take; the transport's own line before it names the step that failed.
private const val DECLINED = "the socket could not take the round; the [ws] line before this names why"

// The detail of a round the socket path did not take; the [ws] line before it names why (no key, no credentials,
// a prefill that did not finish).
private const val BYPASSED = "the socket path did not take the round; the [ws] line before this names why"

// The detail of a frame the peer closed with 1009; a fresh socket would refuse the same frame.
private const val TOO_LARGE = "the peer refused the frame as too large, so a fresh socket would refuse it too"
