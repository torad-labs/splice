// NEW: the G2 zero-event reclassifier, split from TurnFailures (concentration, 2026-08-19)
// so the catch-boundary file is not billed for the classifier's subsystems. Same-package.
// Failure-handling slice 3: the body is read once, by the head's own reader, and a body it does not name is the stream
// that closed before sending anything (final-design.md section 7, Torn(NO_EVENTS)).
package splice.head.transport

import kotlinx.serialization.json.Json
import splice.core.failure.ClientWire
import splice.core.failure.FailureCause
import splice.core.failure.Tear
import splice.core.turn.TurnOutcome
import splice.core.util.ERR_SNIPPET
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.head.turn.TurnDrive
import splice.head.turn.TurnTelemetry
import splice.upstream.Provider
import splice.upstream.failure.ERROR_EVENT
import splice.upstream.failure.FailedEvent
import splice.upstream.transport.HeaderRedaction

/** Bodies are the provider's, so they are read leniently. */
private val bodies = Json {
    isLenient = true
    ignoreUnknownKeys = true
}

internal class ZeroEventFailure(
    private val provider: Provider,
    private val log: LogSink,
) {
    /** G2: a zero-event HTTP-200 stream was hardcoded OVERLOADED — undiagnosable, and Claude Code
     *  retries a dead head forever. When the SSE reader parsed literally zero JSON frames from the
     *  body (events_in == 0) and non-blank raw text was captured before that, the head's reader reads it: a
     *  dead head's error object ("token expired" JSON) is the failure it names, the login it rejects included, and
     *  any other body (an HTML login page) is a stream that closed before sending anything. A genuinely empty body
     *  (no bytes at all — a real stall) has nothing to read and keeps the translator's original verdict.
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
        // read) — either case keeps the translator's original verdict.
        if (eventsIn != 0L || snippet.isBlank()) return outcome
        val cause = read(snippet)
        // The body as it came, with a header it quotes losing that value (fix 4, Oct 27: a dead head's body naming
        // the request's turn state would otherwise write it into the daemon log).
        val shown = HeaderRedaction.redactText(snippet).take(ERR_SNIPPET).replace("\n", "\\n").replace("\r", "")
        log(
            telemetry.errTurn(
                "zero-event",
                drive,
                "was=${ClientWire.ownType(outcome.cause).wireName} classified=${ClientWire.ownType(cause).wireName} " +
                    "snippet=\"$shown\"",
            ),
        )
        // copy() preserves the (empty) partial: a full-constructor rebuild would silently drop
        // the field on any future broadening of this path (code-review 2026-07-24). The body the provider sent
        // rides in the evidence for the log; a person reads the line written from the cause.
        return outcome.copy(cause = cause, evidence = outcome.evidence.copy(message = snippet.take(ERR_SNIPPET)))
    }

    /** What the head's reader names in a body carrying an error object, else a stream torn before any event. */
    private fun read(snippet: String): FailureCause {
        val named = JsonScalars.objectOrNull(bodies, snippet)
            ?.let { provider.replies.event(FailedEvent(ERROR_EVENT, it, provider.auth.owner)) }
            ?.takeUnless { it is FailureCause.Provider.Unrecognized }
        return named ?: FailureCause.Path.Torn(Tear.NO_EVENTS, toolOpen = false)
    }
}
