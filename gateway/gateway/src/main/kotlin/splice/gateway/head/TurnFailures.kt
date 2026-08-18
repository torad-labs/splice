// PORT-OF: splice/gateway/head/TurnDriver.kt (TurnFailures, TurnDriver.classifyZeroEventFailure)
// @ 86f1411 — invariants unchanged: the per-turn error boundary, the failure-message shaping around
// it, and the G2 zero-event reclassifier. The reclassifier IS failure classification — it belongs
// with the class that already owns "which exception/body becomes which ErrorType + message"
// (HD-24). [provider]/[log] became CONSTRUCTOR fields (not classifyZeroEventFailure params): the
// 7-param form tripped detekt's LongParameterList (max 6); [telemetry] stays per-call because it is
// specific to the zero-event path, not shared by loginHint/connectionResetMessage/
// catchingTurnFailure.
package splice.gateway.head

import kotlinx.coroutines.CancellationException
import splice.core.perf.PerfKeys
import splice.core.turn.ErrorType
import splice.core.turn.TurnOutcome
import splice.core.util.LogSink
import splice.spi.FailureSource
import splice.spi.Provider
import splice.spi.SseFrameTooLargeException
import splice.spi.StreamTornBeforeClient
import splice.spi.UpstreamAuthMissing
import splice.spi.UpstreamFailed
import splice.spi.UpstreamFailureClassifier
import java.io.IOException

/** The per-turn error boundary and the failure-message shaping around it. */
internal class TurnFailures(
    private val provider: Provider,
    private val log: LogSink,
) {
    /** Captures exactly the failure classes [TurnEnding.emitFailure] dispatches on: the custom
     *  transport signals, I/O, and the two documented gateway-bug classes (IllegalArgument/
     *  IllegalState — a bad base_url parse, a Ktor internal state error), which previously escaped
     *  as a truncated 200 with no error frame (review 2026-07-19). The stream and collect entries
     *  share ONE boundary. */
    inline fun <R> catchingTurnFailure(block: () -> R): Result<R> =
        try {
            Result.success(block())
        } catch (e: UpstreamAuthMissing) {
            Result.failure(e)
        } catch (e: UpstreamFailed) {
            Result.failure(e)
        } catch (e: StreamTornBeforeClient) {
            // Plain RuntimeException (so translators don't swallow it into a terminal). After
            // UpstreamClient exhausts MAX_STREAM_REISSUES it rethrows here — must become emitConnReset,
            // not escape respondTextWriter as a truncated HTTP 200 SSE.
            Result.failure(e)
        } catch (e: SseFrameTooLargeException) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: CancellationException) {
            // CancellationException extends IllegalStateException — rethrown BEFORE it so a
            // cancelled turn actually stops; stream()/collect() seal the emitter then rethrow.
            throw e
        } catch (e: IllegalArgumentException) {
            // e.g. a URL-parse error from a bad base_url (review 2026-07-19: previously escaped
            // as a truncated 200 with no error frame — emitFailure's branch was unreachable)
            Result.failure(e)
        } catch (e: IllegalStateException) {
            // e.g. an IllegalState out of Ktor internals — same escape class as above
            Result.failure(e)
        }

    fun connectionResetMessage(error: Throwable): String? =
        if (error is StreamTornBeforeClient) error.cause?.message else error.message

    /** G19-consistent per-head hint — every AUTHENTICATION surface uses the SAME provider-threaded
     *  command (review 2026-07-19: two paths still hardcoded "claudex login" on non-codex heads). */
    fun loginHint(): String =
        if (provider.loginCommand.isNotEmpty()) " — run: ${provider.loginCommand}" else ""

    /** G2: a zero-event HTTP-200 stream was hardcoded OVERLOADED — undiagnosable, and Claude Code
     *  retries a dead head forever. When the SSE reader parsed literally zero JSON frames from the
     *  body (events_in == 0) and non-blank raw text was captured before that, classify it via
     *  UpstreamFailureClassifier instead of trusting the translator's generic truncation verdict —
     *  an auth-shaped dead-head body (HTML login page, "unauthorized"/"token expired" JSON) now
     *  surfaces AUTHENTICATION with a login hint instead of a retryable OVERLOADED that spins
     *  forever. A genuinely empty body (no bytes at all — a real stall) has nothing to classify and
     *  keeps the translator's original verdict. */
    fun classifyZeroEventFailure(
        drive: TurnDrive,
        outcome: TurnOutcome,
        snippet: String,
        eventsBase: Long,
        telemetry: TurnTelemetry,
    ): TurnOutcome {
        if (outcome !is TurnOutcome.Failure) return outcome
        // THIS ROUND's events (cumulative counter minus the round's baseline): zero JSON frames
        // parsed means a dead-head body; a blank body is a true stall (nothing to classify) —
        // either case keeps the translator's original verdict.
        val eventsIn = drive.perfCounter(PerfKeys.EVENTS_IN) - eventsBase
        if (eventsIn != 0L || snippet.isBlank()) return outcome
        val classified = UpstreamFailureClassifier.classify(FailureSource.SSE, snippet)
        log(
            telemetry.errTurn(
                "zero-event",
                drive,
                "was=${outcome.type.wireName} classified=${classified.type.wireName} " +
                    "snippet=\"${snippet.take(ERR_SNIPPET).replace("\n", "\\n").replace("\r", "")}\"",
            ),
        )
        val message = if (classified.type == ErrorType.AUTHENTICATION) {
            "${classified.message}${loginHint()}"
        } else {
            classified.message
        }
        // classified from a body the provider actually sent — a provider-reported failure (G20).
        // copy() preserves the (empty) partial: a full-constructor rebuild would silently drop
        // the field on any future broadening of this path (code-review 2026-07-24).
        return outcome.copy(type = classified.type, message = message, providerReported = true)
    }
}
