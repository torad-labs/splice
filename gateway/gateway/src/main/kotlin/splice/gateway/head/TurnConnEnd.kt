// NEW: conn-reset / oversized-frame / torn-stream endings, split from TurnEnding
// (concentration, 2026-08-19) so emitFailure is not billed for this surface. Same-package.
package splice.gateway.head

import splice.core.turn.ErrorType
import splice.core.util.LogSink
import splice.spi.Provider
import splice.spi.SseFrameTooLargeException
import splice.spi.StreamTornBeforeClient
import java.io.IOException

internal class TurnConnEnd(
    private val provider: Provider,
    private val log: LogSink,
    private val telemetry: TurnTelemetry,
    private val failures: TurnFailures,
    private val health: HeadHealthCounters,
) {
    /** True when [e] is a known connection-class failure this surface owns. */
    suspend fun tryEmit(drive: TurnDrive, e: Throwable): Boolean = when (e) {
        // reissue budget exhausted (or non-retryable tear) before any client frame — an
        // upstream connection failure, honestly retryable; never "internal gateway error".
        // post-handoff socket failure: our side of the wire
        is StreamTornBeforeClient, is IOException -> {
            emitConnReset(drive, failures.connectionResetMessage(e))
            true
        }
        is SseFrameTooLargeException -> {
            log(telemetry.errTurn("upstream-frame-too-large", drive, ": ${e.message}"))
            // DR-128: account BEFORE the emit — a dead-client write makes emitError rethrow after
            // sealing, and the turn must not vanish from the perf JSONL and G20 counters (the
            // 2026-07-19 storm shape: dead clients + failing upstream). Same law on every surface.
            telemetry.recordPerf(drive, "error:upstream-frame-too-large")
            health.provider()
            drive.emitter.emitError(ErrorType.API_ERROR, "upstream sent an oversized streaming event — retry")
            true
        }
        else -> false
    }

    /** One conn-reset surface for raw tears and reissue-exhausted [StreamTornBeforeClient]. */
    suspend fun emitConnReset(drive: TurnDrive, detail: String?) {
        log(telemetry.errTurn("conn-reset", drive, ": $detail"))
        val boundedDetail = (detail ?: "no detail").take(ERR_SNIPPET)
        // DR-128: account BEFORE the emit — see the frame-too-large arm above.
        telemetry.recordPerf(drive, "error:conn-reset")
        health.local()
        drive.emitter.emitError(
            ErrorType.OVERLOADED,
            "${provider.key}: upstream connection failed ($boundedDetail) — retry",
        )
    }
}
