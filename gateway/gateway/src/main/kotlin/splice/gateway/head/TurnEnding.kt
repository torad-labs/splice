// PORT-OF: splice/gateway/head/TurnDriver.kt (emitFailure, emitConnReset) @ 86f1411 — invariants
// unchanged: the honest-error-frame surface — one error frame per failure class, and the shared
// conn-reset path for raw tears and reissue-exhausted StreamTornBeforeClient. Its own file (HD-24)
// because this IS the L3 honesty contract: anything that is not a known turn failure and not a
// RuntimeException (i.e. an Error) rethrows — never swallowed. Connection-class endings live in
// TurnConnEnd; auth/upstream-HTTP endings live in TurnKnownEnd (concentration, 2026-08-19).
package splice.gateway.head

import splice.core.turn.ErrorType
import splice.core.util.LogSink

internal class TurnEnding(
    private val log: LogSink,
    private val telemetry: TurnTelemetry,
    private val health: HeadHealthCounters,
    private val connEnd: TurnConnEnd,
    private val knownEnd: TurnKnownEnd,
) {
    /** One honest error frame per failure class; anything that is not a known turn failure and
     *  not a RuntimeException (i.e. an Error) rethrows — never swallowed. */
    suspend fun emitFailure(drive: TurnDrive, e: Throwable) {
        if (connEnd.tryEmit(drive, e)) return
        if (knownEnd.tryEmit(drive, e)) return
        when (e) {
            is RuntimeException -> {
                // e.g. a URL-parse error from a bad base_url, an IllegalState out of Ktor
                // internals. Previously ESCAPED: truncated 200, no error frame, no perf row.
                //
                // The throwable renders ITSELF (`Throwable.toString()` = runtime class + message).
                // A `when` over IllegalArgumentException/IllegalStateException was tried and is
                // wrong (HD-18 review): the BASE classes the boundary converts are a closed set,
                // but the SUBCLASSES that actually arrive are not, and it is the subclass that
                // names the bug source. io.ktor.http.URLParserException IS an IllegalStateException
                // and kotlinx.serialization.SerializationException IS an IllegalArgumentException —
                // the two shapes the comment above names — so the `when` erased precisely the
                // identity this L3-honesty line exists to report. No reflection is involved here;
                // the JVM's own diagnostic rendering is not a runtime type lookup in this source.
                log(telemetry.errTurn("unexpected", drive, ": $e"))
                // DR-128: account BEFORE the emit — a dead-client write makes emitError rethrow
                // after sealing, and the turn must not vanish from the perf JSONL and G20
                // counters. Same law on every failure surface (TurnConnEnd, TurnKnownEnd).
                telemetry.recordPerf(drive, "error:unexpected")
                health.local() // internal gateway bug (e.g. bad base_url parse)
                drive.emitter.emitError(ErrorType.API_ERROR, "claudex: internal gateway error — retry")
            }
            else -> throw e // Errors (OOM etc.) are not turn failures — never masked
        }
    }
}
