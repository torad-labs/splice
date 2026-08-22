// NEW: auth-missing / upstream-failed endings, split from TurnEnding
// (concentration, 2026-08-19) so emitFailure is not billed for this surface. Same-package.
package splice.gateway.head

import splice.core.turn.ErrorType
import splice.core.util.LogSink
import splice.spi.FailureSource
import splice.spi.Provider
import splice.spi.UpstreamAuthMissing
import splice.spi.UpstreamFailed
import splice.spi.UpstreamFailureClassifier

internal class TurnKnownEnd(
    private val provider: Provider,
    private val log: LogSink,
    private val telemetry: TurnTelemetry,
    private val failures: TurnFailures,
    private val health: HeadHealthCounters,
) {
    /** True when [e] is a known upstream-auth or upstream-HTTP failure this surface owns. */
    suspend fun tryEmit(drive: TurnDrive, e: Throwable): Boolean = when (e) {
        is UpstreamAuthMissing -> {
            log(telemetry.errTurn("auth-missing", drive, ": ${e.message}"))
            drive.emitter.emitError(
                ErrorType.AUTHENTICATION,
                "${provider.key}: no upstream credentials${failures.loginHint()}",
            )
            telemetry.recordPerf(drive, "error:auth-missing")
            health.local() // no upstream call ever happened: missing local credentials
            true
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
            true
        }
        else -> false
    }
}
