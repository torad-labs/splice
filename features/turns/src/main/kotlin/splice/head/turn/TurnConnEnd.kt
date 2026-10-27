// NEW: conn-reset / oversized-frame / torn-stream endings, split from TurnEnding
// (concentration, 2026-08-19) so emitFailure is not billed for this surface. Same-package.
package splice.head.turn

import splice.core.failure.FailureCause
import splice.core.failure.Tear
import splice.core.failure.Unreach
import splice.core.perf.OutcomeTag
import splice.core.perf.PerfKeys
import splice.core.turn.CONN_RESET_KIND
import splice.core.turn.CONN_RESET_OUTCOME
import splice.core.turn.CodeModeDivergenceMarker
import splice.core.util.LogSink
import splice.core.util.LoopbackOverride
import splice.head.HeadHealthCounters
import splice.head.perf.PerfFailure
import splice.upstream.Provider
import splice.upstream.transport.SseFrameTooLarge
import splice.upstream.transport.StreamTornBeforeClient
import splice.upstream.transport.TransportFaults
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException

// why: bound cause traversal even if an external throwable forms a cycle.
private const val MAX_FAILURE_CAUSE_DEPTH = 8

internal class TurnConnEnd(
    private val provider: Provider,
    private val log: LogSink,
    private val telemetry: TurnTelemetry,
    private val failures: TurnFailures,
    private val health: HeadHealthCounters,
) {
    /** True when [e] is a raw connection-class failure this surface owns: a socket failure past the handoff.
     *
     *  A client that already left is a client abort, not a connection reset (splice-providers, Oct 12): a write to
     *  its closed socket throws the JDK's bare IOException("Stream closed"), and in codex code mode that write
     *  happens outside the translator (the held writes drained into a new step's sink), so the raw IOException
     *  reaches here with ClientChannel's clientGone already set. It is sealed as CancellationSeal seals one:
     *  nothing on the wire, a client_abort row, and no conn-reset count against the head. */
    suspend fun tryEmit(drive: TurnDrive, e: Throwable): Boolean {
        if (e !is IOException) return false
        if (drive.channel.clientGone.get()) {
            drive.emitter.abandon()
            telemetry.recordPerf(drive, OutcomeTag.CLIENT_ABORT.wire)
        } else {
            emitConn(drive, e)
        }
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
        // Failure-handling slice 3: the socket's failure is READ, once, by the one reader of it
        // (TransportFaults) — this surface no longer decides a refusal from a perf counter it set itself, which
        // left every refusal that was not a loopback one reading as a connection closed before answering.
        val cause = TransportFaults.of(e, local = LoopbackOverride.isLoopback(provider.upstreamUrl))
        if (cause.why == Unreach.REFUSED) {
            runtimePort()?.let { drive.perf.setCount(PerfKeys.REFUSED_RUNTIME_PORT, it.toLong()) }
        }
        emitConnReset(drive, cause, failures.connectionResetMessage(e))
    }

    /** The upstream sent a frame over our own size limit, before or after content reached the client. */
    suspend fun emitOversized(drive: TurnDrive, e: SseFrameTooLarge) {
        // The limit and how big the frame had grown stay in the log; the client and the trace read the line.
        val size = e.observed?.let { ", frame reached $it characters" }.orEmpty()
        log(telemetry.errTurn("upstream-frame-too-large", drive, ": ${e.text}$size"))
        val cause = FailureCause.Path.Torn(Tear.OVERSIZED_EVENT, toolOpen = false)
        // DR-128: account BEFORE the emit — a dead-client write makes emitError rethrow after
        // sealing, and the turn must not vanish from the perf JSONL and G20 counters (the
        // 2026-07-19 storm shape: dead clients + failing upstream). Same law on every surface.
        drive.markPermanent(false)
        telemetry.recordPerf(drive, OutcomeTag.UPSTREAM_FRAME_TOO_LARGE.wire, ending = cause)
        health.provider()
        // V4-81: the wire type is the emitter's decision (ClientWire), and this is the one arm where
        // the cause's permanence needed deciding rather than reading. AN OVERSIZED FRAME IS TRANSIENT
        // (Torn is never permanent): the frame size is a property of the RESPONSE the upstream host
        // chose to send, not of the request we sent, so a re-send buys a genuinely different response
        // and can come back small. Contrast TurnEnding's unparseable base_url, which is a property of
        // our own config and reproduces exactly — that one is permanent. The emitter still owns the
        // other half: an oversized event can be met either side of content, and after content the
        // type is left alone because the client is already finalizing what it holds. The telemetry
        // type is unchanged, and the line is the one the emitter's voice composes for this cause.
        drive.emitter.emitError(cause)
    }

    /** The runtime port a refusal was against, when the address is this machine's own: the count the perf row
     *  carries (refused_runtime_port). Null for a provider out on the network, whose port is nobody's to start. */
    private fun runtimePort(): Int? {
        val endpoint = try {
            URI(provider.upstreamUrl)
        } catch (_: URISyntaxException) {
            return null
        }
        return endpoint.port.takeIf { it > 0 && endpoint.host in setOf("localhost", "127.0.0.1", "[::1]", "::1") }
    }

    /** One conn-reset surface for raw tears and reissue-exhausted [StreamTornBeforeClient]. [cause] is what the
     *  transport said happened (TransportFaults), and [detail] the same failure in the log's words. */
    suspend fun emitConnReset(drive: TurnDrive, cause: FailureCause.Path.Unreachable, detail: String) {
        // The transport's own detail stays in the log; the client and the trace read the line for its cause.
        log(telemetry.errTurn(CONN_RESET_KIND, drive, ": $detail"))
        // DR-128: account BEFORE the emit — see the frame-too-large arm above.
        val refused = drive.perfCounter(PerfKeys.REFUSED_RUNTIME_PORT) > 0
        // The row names a cause only for a refused runtime port, as it always has.
        telemetry.recordPerf(
            drive,
            CONN_RESET_OUTCOME,
            failure = PerfFailure(cause = cause.persisted.name.takeIf { refused }),
            ending = cause,
        )
        health.local()
        drive.emitter.emitError(cause)
    }
}
