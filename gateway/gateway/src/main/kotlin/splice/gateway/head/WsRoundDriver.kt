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
// reimplemented here.
package splice.gateway.head

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.onEach
import splice.core.turn.TurnOutcome
import splice.core.util.LogSink
import splice.spi.Provider
import splice.spi.WsRoundNeedsSse

internal class WsRoundDriver(
    private val provider: Provider,
    private val log: LogSink,
    classifyZeroEvent: ZeroEventClassifier,
) {
    private val roundDrive = WsRoundDrive(provider, classifyZeroEvent)

    /** Run the round, or null to fall through to the SSE path. */
    suspend fun run(inputs: WsRoundInputs): TurnOutcome? {
        val runner = provider.wsRunner ?: return null
        val drive = inputs.drive
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
        try {
            // Credentials come from the provider's auth surface, NOT from a WS-side refresh: L5
            // keeps the single-flight 401 refresh in UpstreamClient, so a missing/expired
            // credential here simply rides SSE and gets refreshed there.
            val accepted = provider.auth.credentials()
                ?.let { creds -> runner.attempt(inputs.bodyJson, drive.meta, drive.turnHeaders, creds) }
            if (accepted == null) {
                // SSE is about to serve this round, so the conversation advances outside any chain.
                runner.roundBypassed(drive.meta)
                reported = true
                return null
            }
            drive.slot.touch()
            // Start the client while the acquired cold flow is being collected, not before: if the
            // start write throws or is cancelled, the exception unwinds through the transport flow's
            // onCompletion and poisons its busy lease instead of stranding the connection forever.
            val startingEvents = accepted.events.onEach { drive.emitter.ensureStarted() }
            drive.watchdog.resetFirstByte()
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
            round.invokeOnCompletion { cause -> if (cause != null) accepted.abort.abort() }
            poller = drive.watchdog.launchIn(inputs.scope, drive.slot, round)
            return try {
                roundDrive.drive(inputs, runner, startingEvents).also { reported = true }
            } catch (ignored: WsRoundNeedsSse) {
                // The round failed while the client had seen nothing, so it can still be re-served
                // with the full recovery machinery. Serving the failure raw over the WebSocket
                // instead would bypass retry/refresh/cooldown entirely — the one way this overlay
                // could land BELOW the status quo.
                log("[${provider.key}] websocket round failed before any client frame — serving over SSE\n")
                runner.roundBypassed(drive.meta)
                reported = true
                null
            }
        } finally {
            if (!reported) runner.roundEnded(drive.meta, ok = false)
            poller?.cancel()
            // Completes with no cause on every ordinary exit, so the handler above leaves a healthy
            // connection alone; a no-op if the watchdog already cancelled it. Without this the job
            // stays an incomplete child of turnJob and the turn cannot finish.
            roundJob?.complete()
        }
    }
}
