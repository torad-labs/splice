// PORT-OF: splice/gateway/head/TurnDriver.kt (the CancellationException catch block inside
// driveSealingCancellation) @ 86f1411 — invariants unchanged: seal the terminal honestly before a
// cancellation rethrows — client-gone -> abandon (nothing on the wire); still-connected -> an
// honest error frame so Claude Code retries instead of seeing a truncated HTTP 200. Its own file
// (HD-24) precisely because it is the L3 seal contract that must have exactly one copy shared by
// stream and collect; the try/catch skeleton that rethrows stays in TurnDriver so the control flow
// that owns the turn stays where the turn is driven.
//
// The seal records only the known completed raw-post prefix, never an estimate for
// the interrupted post. That prefix lives on TurnDrive because the round accumulator dies with a
// cancelled code-mode subtree. Normal aggregate outcomes still own normal completion accounting.
package splice.head.turn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import splice.core.perf.OutcomeTag
import splice.core.perf.OutcomeTags
import splice.core.turn.ErrorType
import splice.core.util.LogSink
import splice.head.HeadHealthCounters
import splice.upstream.Provider
import java.io.IOException

internal class CancellationSeal(
    private val provider: Provider,
    private val log: LogSink,
    private val telemetry: TurnTelemetry,
    private val health: HeadHealthCounters,
    private val usageStamp: TurnUsageStamp,
) {
    private fun retainCleanup(original: CancellationException, cleanup: Throwable) {
        if (cleanup !== original) original.addSuppressed(cleanup)
    }

    /** Seal first, then persist only the raw rounds that returned before cancellation. Any cleanup
     *  error is retained on [original], while the caller still rethrows that exact cancellation. */
    suspend fun sealAndStamp(drive: TurnDrive, seal: Boolean, original: CancellationException) {
        try {
            seal(drive, seal, original)
        } catch (cleanup: CancellationException) {
            retainCleanup(original, cleanup)
        } catch (cleanup: IOException) {
            retainCleanup(original, cleanup)
        } catch (cleanup: IllegalStateException) {
            retainCleanup(original, cleanup)
        }
        try {
            usageStamp.stampKnownOnCancellation(drive)
        } catch (cleanup: CancellationException) {
            retainCleanup(original, cleanup)
        } catch (cleanup: IOException) {
            retainCleanup(original, cleanup)
        } catch (cleanup: IllegalStateException) {
            retainCleanup(original, cleanup)
        }
    }

    /** [seal] gates the cancellation seal to the STREAM path only: collect passes seal=false —
     *  it never commits a 200 before its terminal respondText, so a cancelled collect has no
     *  half-open response to rescue; sealing there only wrote an error body nobody reads while
     *  polluting localOriginErrors (review 2026-07-22 round 3). [cause] is the cancellation being
     *  sealed, which says whether it was the operator's stop (V4-319, [endingOf]). */
    suspend fun seal(drive: TurnDrive, seal: Boolean, cause: Throwable) {
        // Flat when (not nested if) so the still-connected try/catch stays shallow:
        // catch → if(seal) → if(clientGone) → try would trip NestedBlockDepth's depth-4 ceiling.
        when {
            !seal || drive.emitter.hasEnded -> Unit
            drive.channel.clientGone.get() -> {
                drive.emitter.abandon()
                telemetry.recordPerf(drive, OutcomeTag.CLIENT_ABORT.wire)
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
            else -> withContext(NonCancellable) { sealConnected(drive, endingOf(drive, cause)) }
        }
    }

    /** What a still-connected cancelled turn tells its client, and how it is journalled and counted.
     *  [local] bumps the head's own error count: a cancel the head or its watchdog caused, never the
     *  operator's stop. */
    private data class Ending(
        val type: ErrorType,
        val message: String,
        val kind: String,
        val detail: String,
        val outcome: String,
        val local: Boolean,
    )

    private fun endingOf(drive: TurnDrive, cause: Throwable): Ending = when {
        // V4-319: the operator's stop says so, as a request error no retry changes, and never "retry".
        // Read down the cause chain because coroutine stack recovery may hand the seal a wrapped copy.
        generateSequence(cause) { it.cause }.take(CAUSE_DEPTH).any { it is OperatorStop } -> Ending(
            type = ErrorType.INVALID_REQUEST,
            message = "${provider.key}: $OPERATOR_STOPPED",
            kind = "stopped",
            detail = ": the operator stopped the turn",
            outcome = OutcomeTags.error("stopped"),
            local = false,
        )
        // NF-03: a watchdog-fired cancellation names its reason. Pre-stream reaps (total cap
        // during connect/backoff/refresh) land HERE, not in a translator's watchdogOutcome —
        // the generic "cancelled" hid them.
        drive.watchdog.fired != null -> cancelled("${provider.key}: upstream stalled (watchdog), aborted; retry")
        else -> cancelled("${provider.key}: turn cancelled; retry")
    }

    private fun cancelled(message: String): Ending = Ending(
        type = ErrorType.OVERLOADED,
        message = message,
        kind = "cancelled",
        detail = ": turn cancelled before terminal",
        outcome = OutcomeTag.CANCELLED.wire,
        local = true,
    )

    private suspend fun sealConnected(drive: TurnDrive, ending: Ending) {
        try {
            drive.emitter.emitError(ending.type, ending.message)
            log(telemetry.errTurn(ending.kind, drive, ending.detail))
            telemetry.recordPerf(drive, ending.outcome)
            if (ending.local) health.local()
        } catch (io: IOException) {
            // emitError's error frame could not reach the wire — the cancel WAS a client
            // disconnect the ping/write path hadn't flagged. Reclassify as a benign
            // abandon (emitError already sealed on IOException; the set is idempotent),
            // NOT an error:cancelled — no health bump (review 2026-07-22 round 3).
            log("[${provider.key}] turn cancelled + error frame unwritable (${io.message}); client gone\n")
            drive.emitter.abandon()
            telemetry.recordPerf(drive, OutcomeTag.CLIENT_ABORT.wire)
        }
    }
}

/** How far down a cancellation's causes the seal looks for an [OperatorStop]: the throw itself, and the
 *  copies coroutine stack recovery wraps around it. */
private const val CAUSE_DEPTH = 4
