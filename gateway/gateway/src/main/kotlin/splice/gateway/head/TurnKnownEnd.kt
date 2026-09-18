// NEW: auth-missing / upstream-failed endings, split from TurnEnding
// (concentration, 2026-08-19) so emitFailure is not billed for this surface. Same-package.
package splice.gateway.head

import splice.core.perf.OutcomeTag
import splice.core.turn.ErrorType
import splice.core.util.LogSink
import splice.gateway.pipeline.FailurePresenter
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

    // V4-59: named for the presenter, not the TurnFailures above — the classified message reaching
    // emitError is the SECOND place a vendor's raw body could become client text. The classifier
    // lifts error.message out of a JSON body, but falls back to the WHOLE body whenever the shape
    // is one it cannot read (our own detail-only fail-fast is exactly that shape), and this arm
    // then emitted the fallback verbatim.
    private val presenter = FailurePresenter()

    /** True when [e] is a known upstream-auth or upstream-HTTP failure this surface owns. */
    suspend fun tryEmit(drive: TurnDrive, e: Throwable): Boolean = when (e) {
        is UpstreamAuthMissing -> {
            log(telemetry.errTurn("auth-missing", drive, ": ${e.message}"))
            // DR-128: account BEFORE the emit — a dead-client write makes emitError rethrow after
            // sealing, and the turn must not vanish from the perf JSONL and G20 counters (the
            // 2026-07-19 storm shape: dead clients + failing upstream). Same law on every surface.
            telemetry.recordPerf(drive, OutcomeTag.AUTH_MISSING.wire)
            health.local() // no upstream call ever happened: missing local credentials
            drive.emitter.emitError(
                ErrorType.AUTHENTICATION,
                "${provider.key}: no upstream credentials${failures.loginHint()}",
            )
            true
        }
        is UpstreamFailed -> {
            val failure = UpstreamFailureClassifier.classify(FailureSource.HTTP, e.body, e.status)
            val detail = "type=${failure.type.wireName} status=${e.status} msg=${failure.message.take(ERR_SNIPPET)}"
            log(telemetry.errTurn("upstream-failed", drive, detail))
            // V4-59: the code rides OUTSIDE the snippet bound on purpose. Bounding the presented
            // line instead pushed the appended login hint past ERR_SNIPPET, silently dropping the
            // one part of this message that tells the operator what to DO — caught by the existing
            // login-hint test, which is exactly what it is for.
            val classified = presenter.present(failure.type, failure.message)
            val boundedMessage = "[${classified.code}] ${classified.body.take(ERR_SNIPPET)}"
            val message = if (failure.type == ErrorType.AUTHENTICATION && provider.loginCommand.isNotEmpty()) {
                "$boundedMessage — run: ${provider.loginCommand}"
            } else {
                boundedMessage
            }
            // DR-128: account BEFORE the emit — same law as the auth-missing arm above.
            // A 429 is the quota instrument's most load-bearing event: it is the exact moment the
            // plan said no, and it must be countable in the rollup, not just greppable in the log.
            telemetry.recordPerf(drive, OutcomeTag.UPSTREAM_FAILED.wire, failure.type == ErrorType.RATE_LIMIT)
            health.provider() // e.status/e.body are the literal HTTP response the upstream host gave
            // V4-71 re-sited by V4-81: the FIRST turn to meet a persistent 429 must reach the
            // client RETRYABLE, and a 200 is already committed at TurnStreamer.stream before the
            // upstream connect — so the 429 cannot be sent as a status and the wire type is the
            // only lever. That lever now lives at the emitter; what stays HERE is the one fact the
            // classifier produced for this failure and nothing downstream can re-derive.
            //
            // V4-81: the WIRE TYPE is decided at the emitter now (SseEmitter.emitError), not here,
            // so this surface hands the failure through unaltered. Two facts stay the CALLER'S
            // because only the caller holds them: the failure's own permanence — failure.transient
            // is the classifier's verdict that an identical re-send reproduces this exact answer,
            // and a relabelled permanent failure is 300 client re-sends at six upstream attempts
            // each — and the message text, which is FailurePresenter's plus the login hint V4-59
            // appends outside the snippet bound. Everything else (whether content has reached the
            // client, and what the client does with each type) is the emitter's, because the
            // emitter owns both the counter and the frame. Telemetry above still records the REAL
            // type: only the WIRE TYPE ever moves.
            drive.emitter.emitError(failure.type, message, permanent = !failure.transient)
            true
        }
        else -> false
    }
}
