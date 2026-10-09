// NEW: conn-reset / oversized-frame / torn-stream endings, split from TurnEnding
// (concentration, 2026-08-19) so emitFailure is not billed for this surface. Same-package.
package splice.head.turn

import splice.core.perf.OutcomeTag
import splice.core.perf.PerfKeys
import splice.core.turn.CONN_RESET_KIND
import splice.core.turn.CONN_RESET_OUTCOME
import splice.core.turn.CodeModeDivergenceMarker
import splice.core.turn.ErrorType
import splice.core.util.ERR_SNIPPET
import splice.core.util.LogSink
import splice.head.HeadHealthCounters
import splice.upstream.Provider
import splice.upstream.transport.SseFrameTooLarge
import splice.upstream.transport.StreamTornBeforeClient
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.URISyntaxException
import java.nio.channels.ClosedChannelException

private const val RETRY_HINT = "; retry"

// why: bound cause traversal even if an external throwable forms a cycle.
private const val MAX_FAILURE_CAUSE_DEPTH = 8

internal class TurnConnEnd(
    private val provider: Provider,
    private val log: LogSink,
    private val telemetry: TurnTelemetry,
    private val failures: TurnFailures,
    private val health: HeadHealthCounters,
) {
    /** True when [e] is a raw connection-class failure this surface owns: a socket failure past the handoff. */
    suspend fun tryEmit(drive: TurnDrive, e: Throwable): Boolean {
        if (e !is IOException) return false
        emitConn(drive, e)
        return true
    }

    /** A tear before any client frame that could not be re-issued: an upstream connection failure, honestly
     *  retryable; never "internal gateway error". */
    suspend fun emitTorn(drive: TurnDrive, torn: StreamTornBeforeClient) = emitConn(drive, torn.cause)

    private suspend fun emitConn(drive: TurnDrive, e: IOException) {
        val divergence = generateSequence<Throwable>(e) { it.cause }.take(MAX_FAILURE_CAUSE_DEPTH).any { failure ->
            failure.suppressed.any { it is CodeModeDivergenceMarker }
        }
        if (divergence) drive.perf.setCount(PerfKeys.CODE_MODE_DIVERGENCE, 1)
        refusedRuntimePort(e)?.let { drive.perf.setCount(PerfKeys.REFUSED_RUNTIME_PORT, it.toLong()) }
        emitConnReset(drive, failures.connectionResetMessage(e))
    }

    /** The upstream sent a frame over our own size limit, before or after content reached the client. */
    suspend fun emitOversized(drive: TurnDrive, e: SseFrameTooLarge) {
        log(telemetry.errTurn("upstream-frame-too-large", drive, ": ${e.text}"))
        drive.trace?.failureSentence(frameTooLargeSentence(e))
        // DR-128: account BEFORE the emit — a dead-client write makes emitError rethrow after
        // sealing, and the turn must not vanish from the perf JSONL and G20 counters (the
        // 2026-07-19 storm shape: dead clients + failing upstream). Same law on every surface.
        drive.markPermanent(false)
        telemetry.recordPerf(drive, OutcomeTag.UPSTREAM_FRAME_TOO_LARGE.wire)
        health.provider()
        // V4-81: the wire type is the EMITTER's decision now, so this arm passes permanence
        // explicitly — and this is the one arm where the answer needed deciding rather than
        // reading. AN OVERSIZED FRAME IS TRANSIENT (permanent = false): the frame size is a
        // property of the RESPONSE the upstream host chose to send, not of the request we sent,
        // so a re-send buys a genuinely different response and can come back small. Contrast
        // TurnEnding's unparseable base_url, which is a property of our own config and
        // reproduces exactly — that one is permanent. The emitter still owns the other half:
        // an oversized event can be met either side of content, and after content the type is
        // left alone because the client is already finalizing what it holds. The message and
        // the telemetry type are unchanged.
        drive.emitter.emitError(
            ErrorType.API_ERROR,
            "upstream sent an oversized streaming event; retry",
            permanent = false,
        )
    }

    /** The wire record's words for an oversized frame: the label, which limit, and how big the frame had grown. Never
     *  the frame's content. */
    private fun frameTooLargeSentence(e: SseFrameTooLarge): String {
        val size = e.observed?.let { ", frame reached $it characters" }.orEmpty()
        return "frame_too_large: ${e.text}$size$RETRY_HINT"
    }

    private fun refusedRuntimePort(error: Throwable): Int? {
        val chain = generateSequence(error) { it.cause }.take(MAX_FAILURE_CAUSE_DEPTH).toList()
        val closedConnect = chain.any { it is ConnectException } && chain.any { it is ClosedChannelException }
        val refused = closedConnect || chain.any(::namedRefusal)
        if (!refused || chain.any(::otherConnectFailure)) return null
        val endpoint = try {
            URI(provider.upstreamUrl)
        } catch (_: URISyntaxException) {
            return null
        }
        return endpoint.port.takeIf { it > 0 && endpoint.host in setOf("localhost", "127.0.0.1", "[::1]", "::1") }
    }

    private fun namedRefusal(error: Throwable): Boolean =
        error is ConnectException && error.message?.contains("Connection refused", ignoreCase = true) == true

    private fun otherConnectFailure(error: Throwable): Boolean = when (error) {
        is java.nio.channels.UnresolvedAddressException, is java.net.UnknownHostException,
        is java.net.NoRouteToHostException, is java.net.http.HttpConnectTimeoutException,
        is io.ktor.client.network.sockets.ConnectTimeoutException,
        -> true
        else -> false
    }

    /** One conn-reset surface for raw tears and reissue-exhausted [StreamTornBeforeClient]. */
    suspend fun emitConnReset(drive: TurnDrive, detail: String) {
        log(telemetry.errTurn(CONN_RESET_KIND, drive, ": $detail"))
        val boundedDetail = detail.take(ERR_SNIPPET - RETRY_HINT.length)
        drive.trace?.failureSentence("$boundedDetail$RETRY_HINT")
        // DR-128: account BEFORE the emit — see the frame-too-large arm above.
        val cause = if (drive.perfCounter(PerfKeys.REFUSED_RUNTIME_PORT) > 0) "CONNECT_REFUSED" else null
        telemetry.recordPerf(drive, CONN_RESET_OUTCOME, cause = cause)
        health.local()
        drive.emitter.emitError(
            ErrorType.OVERLOADED,
            "${provider.key}: upstream connection failed ($boundedDetail); retry",
        )
    }
}
