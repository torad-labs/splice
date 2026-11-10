// NEW: 2026-11-09 audit — what a request refused while it was prepared arrived as, so the refusal leaves a log line, a
// perf row and a trace record (PreDriveRefusals.refusePrepared). From 5:51 to 6:58 PM CT that day a Responses dialect
// refused every claudex turn on a field Claude Code 2.1.321 had started sending, and the log, the perf file and the
// trace recorded nothing: Preparation.Rejected carried only its cause, so there was nothing to record it with.
// It lives in splice.head (Nov 10), beside the request reader, so splice.head.turn keeps its 30 files (concentration).
package splice.head

import splice.core.failure.Decline
import splice.core.failure.FailureCause
import splice.core.memory.HeapCapacityException
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnReasoning
import splice.core.turn.TurnRoute
import splice.core.turn.TurnScope
import splice.core.util.SafeFailureText
import splice.head.turn.Preparation
import splice.head.wire.ClientInbound

/** The model, session and transport the request named when its body parsed (empty, null and false when it did not),
 *  the request itself when the head's trace is on, and what splice saw beyond the cause (a body's sizes, a defect's
 *  kind and site). */
internal data class RefusedRequest(
    val model: String = "",
    val sessionId: String? = null,
    val stream: Boolean = false,
    val inbound: ClientInbound? = null,
    val detail: String? = null,
) {
    /** The turn facts a refusal's perf row and trace are stamped with: a request that never became a turn has no
     *  route, reasoning or compaction of its own, so it names the model it asked for on both sides of the route. */
    fun meta(): TurnMeta = TurnMeta(
        compact = false,
        route = TurnRoute(stream = stream, originalModel = model, upstreamModel = model, clientMaxTokens = null),
        reasoning = TurnReasoning(ReasoningDisplay.OFF, effort = "", summary = null, budgetTokens = null),
        scope = TurnScope(sessionId = sessionId),
    )

    /** A body that did not parse: [bytes] read against the [declared] length, and the parser's failure by kind alone
     *  (the class, never the content: the body is the user's transcript). The declared length beside the read one
     *  tells a truncated read from a malformed body. */
    fun badBody(bytes: Int, declared: String, cause: Throwable?): Preparation.Rejected {
        val why = cause?.let { it::class.simpleName } ?: "no exception"
        return Preparation.Rejected(
            FailureCause.Splice.Declined(Decline.BadBody(bytes.toLong(), declared.toLongOrNull())),
            copy(detail = "request rejected: invalid request body ($bytes bytes read, $declared declared, $why)"),
        )
    }

    /** The daemon.log detail of a defect that escaped a request's own handling (PreDriveRefusals.refuseEscaped): its
     *  kind and the splice line it came from, never its message, which may quote the transcript (SafeFailureText). */
    fun escaped(failure: Throwable): String {
        val kind = failure::class.simpleName ?: "unnamed failure"
        return "request failed: $kind: ${SafeFailureText.render(failure)}${SafeFailureText.site(failure)}"
    }

    /** A defect out of preparing a parsed request: its kind and the splice line it came from, never its message, which
     *  may quote the transcript (SafeFailureText). The site is what makes it findable from daemon.log. Retryable,
     *  like every unattributed failure (the retry default is total). A full heap is not a defect: it goes on to
     *  HeapIngress, which answers it with the retryable overload. */
    fun unprepared(defect: Throwable): Preparation.Rejected {
        if (defect is HeapCapacityException) throw defect
        val kind = defect::class.simpleName ?: "unnamed failure"
        val said = "${SafeFailureText.render(defect)}${SafeFailureText.site(defect)}"
        return Preparation.Rejected(
            FailureCause.Splice.Internal(escaped = true),
            copy(detail = "request could not be prepared ($kind: $said)"),
        )
    }
}
