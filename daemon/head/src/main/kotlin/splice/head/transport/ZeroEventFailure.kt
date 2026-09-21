// NEW: the G2 zero-event reclassifier, split from TurnFailures (concentration, 2026-08-19)
// so the catch-boundary file is not billed for the classifier's subsystems. Same-package.
package splice.head.transport

import splice.core.turn.ErrorType
import splice.core.turn.TurnOutcome
import splice.core.util.ERR_SNIPPET
import splice.core.util.LogSink
import splice.head.turn.TurnDrive
import splice.head.turn.TurnTelemetry
import splice.upstream.Provider
import splice.upstream.failure.FailureSource
import splice.upstream.failure.UpstreamFailureClassifier

internal class ZeroEventFailure(
    private val provider: Provider,
    private val log: LogSink,
) {
    /** G2: a zero-event HTTP-200 stream was hardcoded OVERLOADED — undiagnosable, and Claude Code
     *  retries a dead head forever. When the SSE reader parsed literally zero JSON frames from the
     *  body (events_in == 0) and non-blank raw text was captured before that, classify it via
     *  UpstreamFailureClassifier instead of trusting the translator's generic truncation verdict —
     *  an auth-shaped dead-head body (HTML login page, "unauthorized"/"token expired" JSON) now
     *  surfaces AUTHENTICATION with a login hint instead of a retryable OVERLOADED that spins
     *  forever. A genuinely empty body (no bytes at all — a real stall) has nothing to classify and
     *  keeps the translator's original verdict.
     *
     *  [eventsIn] is THIS ROUND's parsed-frame count (cumulative counter minus the round baseline),
     *  computed by the caller so this file is not billed for splice.core.perf. */
    fun classify(
        drive: TurnDrive,
        outcome: TurnOutcome,
        snippet: String,
        eventsIn: Long,
        telemetry: TurnTelemetry,
    ): TurnOutcome {
        if (outcome !is TurnOutcome.Failure) return outcome
        // zero JSON frames parsed means a dead-head body; a blank body is a true stall (nothing to
        // classify) — either case keeps the translator's original verdict.
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
            val hint = if (provider.loginCommand.isNotEmpty()) " — run: ${provider.loginCommand}" else ""
            "${classified.message}$hint"
        } else {
            classified.message
        }
        // classified from a body the provider actually sent — a provider-reported failure (G20).
        // copy() preserves the (empty) partial: a full-constructor rebuild would silently drop
        // the field on any future broadening of this path (code-review 2026-07-24).
        // V4-117: the cause is adopted from the classifier rather than the type being overwritten.
        // This line used to be the boundary's SECOND author of the wire type; now the classifier
        // names the cause (it is the one holding the code, the status and the body) and the derived
        // type follows from it plus the phase, so the failure and the wire cannot disagree.
        return outcome.copy(cause = classified.cause, message = message, providerReported = true)
    }
}
