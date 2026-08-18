// PORT-OF: splice/gateway/head/TurnDriver.kt (the CancellationException catch block inside
// driveSealingCancellation) @ 86f1411 — invariants unchanged: seal the terminal honestly before a
// cancellation rethrows — client-gone -> abandon (nothing on the wire); still-connected -> an
// honest error frame so Claude Code retries instead of seeing a truncated HTTP 200. Its own file
// (HD-24) precisely because it is the L3 seal contract that must have exactly one copy shared by
// stream and collect; the try/catch skeleton that rethrows stays in TurnDriver so the control flow
// that owns the turn stays where the turn is driven.
package splice.gateway.head

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import splice.core.turn.ErrorType
import splice.core.util.LogSink
import splice.spi.Provider
import java.io.IOException

internal class CancellationSeal(
    private val provider: Provider,
    private val log: LogSink,
    private val telemetry: TurnTelemetry,
    private val health: HeadHealthCounters,
) {
    /** [seal] gates the cancellation seal to the STREAM path only: collect passes seal=false —
     *  it never commits a 200 before its terminal respondText, so a cancelled collect has no
     *  half-open response to rescue; sealing there only wrote an error body nobody reads while
     *  polluting localOriginErrors (review 2026-07-22 round 3). */
    suspend fun seal(drive: TurnDrive, seal: Boolean) {
        // NF-03: a watchdog-fired cancellation names its reason. Pre-stream reaps (total cap
        // during connect/backoff/refresh) land HERE, not in a translator's watchdogOutcome —
        // the generic "cancelled" hid them.
        val cancelMsg = if (drive.watchdog.fired != null) {
            "${provider.key}: upstream stalled (watchdog) — aborted; retry"
        } else {
            "${provider.key}: turn cancelled — retry"
        }
        // Flat when (not nested if) so the still-connected try/catch stays shallow:
        // catch → if(seal) → if(clientGone) → try would trip NestedBlockDepth's depth-4 ceiling.
        when {
            !seal || drive.emitter.hasEnded -> Unit
            drive.channel.clientGone.get() -> {
                drive.emitter.abandon()
                telemetry.recordPerf(drive, "client_abort")
            }
            // clientGone flips only on a FAILED write, but Ktor/Netty cancels on
            // channel-inactive with no write having failed — a user abort mid-lull reaches here
            // still "connected". Emit FIRST; if the error frame can't reach the wire the cancel
            // WAS a client disconnect the ping/write path hadn't flagged, so reclassify it as an
            // abandon, not an error:cancelled (review 2026-07-22 round 3).
            //
            // NonCancellable because seal() is called FROM a CancellationException catch, so the Job
            // is ALREADY cancelling: emitError — the only suspend call in this function — would
            // rethrow CancellationException at its first suspension point instead of writing the
            // frame. That is not an IOException, so the catch below would MISS it and the log, the
            // perf row, the health bump and the abandon() reclassification would all be skipped,
            // handing the client exactly the truncated 200 this file exists to prevent.
            // SseEmitter.emitError releases its seal claim on cancellation "so a later seal can
            // still retry" — this IS that later seal, and nothing runs after it. Same leak-safe
            // teardown idiom as the slot release in HeadAdmission/AdmissionGate.
            else ->
                withContext(NonCancellable) {
                    try {
                        drive.emitter.emitError(ErrorType.OVERLOADED, cancelMsg)
                        log(telemetry.errTurn("cancelled", drive, ": turn cancelled before terminal"))
                        telemetry.recordPerf(drive, "error:cancelled")
                        health.local()
                    } catch (io: IOException) {
                        // emitError's error frame could not reach the wire — the cancel WAS a client
                        // disconnect the ping/write path hadn't flagged. Reclassify as a benign
                        // abandon (emitError already sealed on IOException; the set is idempotent),
                        // NOT an error:cancelled — no health bump (review 2026-07-22 round 3).
                        log("[${provider.key}] turn cancelled + error frame unwritable (${io.message}) — client gone\n")
                        drive.emitter.abandon()
                        telemetry.recordPerf(drive, "client_abort")
                    }
                }
        }
    }
}
