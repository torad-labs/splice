// PORT-OF: splice/gateway/head/TurnDriver.kt (emitFailure, emitConnReset) @ 86f1411 — invariants
// unchanged: the honest-error-frame surface — one error frame per failure class, and the shared
// conn-reset path for raw tears and reissue-exhausted StreamTornBeforeClient. Its own file (HD-24)
// because this IS the L3 honesty contract: anything that is not a known turn failure and not a
// RuntimeException (i.e. an Error) rethrows — never swallowed.
package splice.gateway.head

import splice.core.turn.ErrorType
import splice.core.util.LogSink
import splice.spi.FailureSource
import splice.spi.Provider
import splice.spi.SseFrameTooLargeException
import splice.spi.StreamTornBeforeClient
import splice.spi.UpstreamAuthMissing
import splice.spi.UpstreamFailed
import splice.spi.UpstreamFailureClassifier
import java.io.IOException

internal class TurnEnding(
    private val provider: Provider,
    private val log: LogSink,
    private val telemetry: TurnTelemetry,
    private val failures: TurnFailures,
    private val health: HeadHealthCounters,
) {
    /** One honest error frame per failure class; anything that is not a known turn failure and
     *  not a RuntimeException (i.e. an Error) rethrows — never swallowed. */
    suspend fun emitFailure(drive: TurnDrive, e: Throwable) {
        when (e) {
            is UpstreamAuthMissing -> {
                log(telemetry.errTurn("auth-missing", drive, ": ${e.message}"))
                drive.emitter.emitError(
                    ErrorType.AUTHENTICATION,
                    "${provider.key}: no upstream credentials${failures.loginHint()}",
                )
                telemetry.recordPerf(drive, "error:auth-missing")
                health.local() // no upstream call ever happened: missing local credentials
            }
            is UpstreamFailed -> {
                val failure = UpstreamFailureClassifier.classify(FailureSource.HTTP, e.body, e.status)
                val detail = "type=${failure.type.wireName} status=${e.status} msg=${failure.message.take(ERR_SNIPPET)}"
                log(telemetry.errTurn("upstream-failed", drive, detail))
                val boundedMessage = failure.message.take(ERR_SNIPPET)
                val message = if (failure.type == ErrorType.AUTHENTICATION && provider.loginCommand.isNotEmpty()) {
                    "$boundedMessage — run: ${provider.loginCommand}"
                } else {
                    boundedMessage
                }
                drive.emitter.emitError(failure.type, message)
                telemetry.recordPerf(drive, "error:upstream-failed")
                health.provider() // e.status/e.body are the literal HTTP response the upstream host gave
            }
            // reissue budget exhausted (or non-retryable tear) before any client frame — an
            // upstream connection failure, honestly retryable; never "internal gateway error".
            // post-handoff socket failure: our side of the wire
            is StreamTornBeforeClient, is IOException -> emitConnReset(drive, failures.connectionResetMessage(e))
            is SseFrameTooLargeException -> {
                log(telemetry.errTurn("upstream-frame-too-large", drive, ": ${e.message}"))
                drive.emitter.emitError(ErrorType.API_ERROR, "upstream sent an oversized streaming event — retry")
                telemetry.recordPerf(drive, "error:upstream-frame-too-large")
                health.provider()
            }
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
                drive.emitter.emitError(ErrorType.API_ERROR, "claudex: internal gateway error — retry")
                telemetry.recordPerf(drive, "error:unexpected")
                health.local() // internal gateway bug (e.g. bad base_url parse)
            }
            else -> throw e // Errors (OOM etc.) are not turn failures — never masked
        }
    }

    /** One conn-reset surface for raw tears and reissue-exhausted [StreamTornBeforeClient]. */
    suspend fun emitConnReset(drive: TurnDrive, detail: String?) {
        log(telemetry.errTurn("conn-reset", drive, ": $detail"))
        val boundedDetail = (detail ?: "no detail").take(ERR_SNIPPET)
        drive.emitter.emitError(
            ErrorType.OVERLOADED,
            "${provider.key}: upstream connection failed ($boundedDetail) — retry",
        )
        telemetry.recordPerf(drive, "error:conn-reset")
        health.local()
    }
}
