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
        // Credentials come from the provider's auth surface, NOT from a WS-side refresh: L5 keeps
        // the single-flight 401 refresh in UpstreamClient, so a missing/expired credential here
        // simply rides SSE and gets refreshed there.
        val events = provider.auth.credentials()
            ?.let { creds -> runner.attempt(inputs.bodyJson, drive.meta, drive.turnHeaders, creds) }
        if (events == null) {
            // SSE is about to serve this round, so the conversation advances outside any chain.
            runner.roundBypassed(drive.meta)
            return null
        }
        drive.slot.touch()
        drive.emitter.ensureStarted()
        drive.watchdog.resetFirstByte()
        val poller = drive.watchdog.launchIn(inputs.scope, drive.slot, inputs.turnJob)
        // CON-003: every exit below reports the round exactly once. [drive] reports ok=true on a
        // clean terminal and the sentinel path reports a bypass; anything else — an unexpected throw
        // out of the translator, or cancellation — leaves this false and the finally clears the
        // chain, which is [WsRoundRunner.roundEnded]'s stated contract ("anything else must clear
        // the chaining state"). Reported via a flag rather than a catch so the exception itself
        // continues untouched.
        var reported = false
        return try {
            roundDrive.drive(inputs, runner, events).also { reported = true }
        } catch (ignored: WsRoundNeedsSse) {
            // The round failed while the client had seen nothing, so it can still be re-served with
            // the full recovery machinery. Serving the failure raw over the WebSocket instead would
            // bypass retry/refresh/cooldown entirely — the one way this overlay could land BELOW
            // the status quo.
            log("[${provider.key}] websocket round failed before any client frame — serving over SSE\n")
            runner.roundBypassed(drive.meta)
            reported = true
            null
        } finally {
            if (!reported) runner.roundEnded(drive.meta, ok = false)
            poller.cancel()
        }
    }
}
