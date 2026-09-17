// NEW: auth-missing / upstream-failed endings, split from TurnEnding
// (concentration, 2026-08-19) so emitFailure is not billed for this surface. Same-package.
package splice.gateway.head

import splice.core.perf.PerfKeys
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
            telemetry.recordPerf(drive, "error:auth-missing")
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
            telemetry.recordPerf(drive, "error:upstream-failed", failure.type == ErrorType.RATE_LIMIT)
            health.provider() // e.status/e.body are the literal HTTP response the upstream host gave
            // V4-71: the FIRST turn to meet a persistent 429 must reach the client RETRYABLE, and
            // today it does not. Claude Code retries an in-band error ONLY when it carries
            // overloaded_error (or a real 429/529 status) — verified in the 2.1.257 binary — and a
            // 200 is already committed at TurnStreamer.stream before the upstream connect, so a
            // genuine 429 is not ours to send here. Before any content the turn is indistinguishable
            // from a transient overload and nothing the client read is at stake, so the WIRE TYPE
            // becomes OVERLOADED while the WORDS stay rate-limit: the same shape the conn-reset and
            // watchdog endings already use. The cooldown is armed by RetryRules.giveUp, so the
            // client's re-send meets the V4-50 admission 429 with its real headers.
            //
            // THE FACT IS THE TURN'S, NOT A ROUND'S. SseRoundDriver baselines CONTENT_FRAMES_OUT per
            // round and this layer cannot see a round baseline; perfCounter is turn-cumulative, so
            // "> 0" is the question that belongs here — has ANY content reached the client this turn.
            // After content this stays a rate_limit_error, which is V4-60's exclusion and unchanged.
            val contentReachedClient = drive.perfCounter(PerfKeys.CONTENT_FRAMES_OUT) > 0
            // V4-78: API_ERROR is the SECOND type this rule covers, and for exactly the reason
            // above — the binary retries an in-band error ONLY when the body carries
            // overloaded_error, so a pre-content api_error is terminal for the client while a
            // pre-content overloaded_error is retried. A live api_error-in-200 path exists
            // (HeadServerFailureBranchTest drives it), and until this line such a turn ended the
            // session where a retry would have healed it.
            //
            // THE BOUNDS ARE THE POINT, and they are the same ones the rate-limit half keeps:
            // content already delivered excludes it (after content the client finalizes whatever it
            // holds, and a remap would be a lie about what it is reading), and INVALID_REQUEST and
            // AUTH are NOT remapped — those are splice telling the client something it must act on,
            // and no retry of the same bytes changes them. Only the WIRE TYPE moves: the message
            // text still comes from FailurePresenter and telemetry still records the REAL type.
            val wireType = PreContentWireType.of(failure.type, contentReachedClient)
            drive.emitter.emitError(wireType, message)
            true
        }
        else -> false
    }
}

/** V4-78: THE PRE-CONTENT WIRE-TYPE RULE, as a value rather than a branch buried in a handler.
 *
 *  Claude Code 2.1.257 retries an IN-BAND error event only when its body carries overloaded_error
 *  (a real 429/529 is retried by STATUS, and neither is ours to send once the 200 is committed).
 *  So before any content has reached the client, a failure whose type the client would treat as
 *  terminal — RATE_LIMIT, and API_ERROR — is wired as OVERLOADED instead. Nothing the client has
 *  read is at stake at that point, and the turn is indistinguishable from a transient overload.
 *
 *  THE BOUNDS, all three deliberate: after content the type is left ALONE (the client finalizes
 *  whatever it holds, and relabelling would misdescribe what it is reading); INVALID_REQUEST and
 *  AUTHENTICATION are never remapped (splice is telling the client something only the operator can
 *  change, and a retry of identical bytes cannot); and only the WIRE TYPE moves — the message still
 *  comes from FailurePresenter and telemetry still records the REAL type. */
internal object PreContentWireType {
    fun of(type: ErrorType, contentReachedClient: Boolean): ErrorType {
        val retryable = type == ErrorType.RATE_LIMIT || type == ErrorType.API_ERROR
        return if (retryable && !contentReachedClient) ErrorType.OVERLOADED else type
    }
}
