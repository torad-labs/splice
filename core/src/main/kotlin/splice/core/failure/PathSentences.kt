// NEW: failure-handling slice 2 (2026-10-26). The sentences for the three ways the path to a provider breaks
// (final-design.md section 10).
package splice.core.failure

/** The three ways the path to a provider breaks, said as the person saw them. Once the answer began, the person saw it
 *  start: a connection that closed reads as the answer cut off and a reply that never came as silence mid-answer,
 *  whatever leaf the transport chose (a reset past the handoff is RESET_BEFORE_REPLY wherever it lands,
 *  TurnConnEnd.emitConnReset). Before any of the answer reached them, there is no answer to call cut off or silent in
 *  its middle, whatever the provider's stream had begun: a close reads as one before answering, and a silence as an
 *  answer that did not start in time (slice 2 finding 21, where a cut that landed before its text said "cut off"). */
internal object PathSentences {
    fun said(cause: FailureCause.Path, at: Position, facts: LineFacts, ongoing: Boolean): Said {
        val head = facts.head
        val answering = at.moment == Moment.MID_CONTENT
        val what = when (cause) {
            is FailureCause.Path.Unreachable -> unreachable(cause, head, answering, ongoing)
            is FailureCause.Path.Torn -> torn(cause, head, answering, ongoing)
            is FailureCause.Path.Stalled -> if (answering) {
                silent(head, ongoing)
            } else {
                "$head did not start answering in time"
            }
        }
        return Said(what, next = Next.Resend(SEND_AGAIN))
    }

    private fun unreachable(
        cause: FailureCause.Path.Unreachable,
        head: String,
        answering: Boolean,
        ongoing: Boolean,
    ): String {
        val side = if (cause.local) "$head's model server" else "$head's provider"
        return when (cause.why) {
            Unreach.REFUSED -> refused(side, cause.local, ongoing)
            Unreach.DNS -> "$head could not reach its provider because the address did not resolve"
            Unreach.TLS -> "$head could not open a secure connection to its provider"
            Unreach.RESET_BEFORE_REPLY -> if (answering) {
                cut(head, ongoing, toolOpen = false)
            } else {
                "$side closed the connection before answering"
            }
            Unreach.WRITE_STALLED -> "$head stopped reading the request while splice was sending it"
        }
    }

    /** A model server on this machine that refuses is down; a remote provider that refuses only refused. */
    private fun refused(side: String, local: Boolean, ongoing: Boolean): String = when {
        !local -> "$side refused the connection"
        ongoing -> "$side is not answering"
        else -> "$side is not running"
    }

    private fun torn(cause: FailureCause.Path.Torn, head: String, answering: Boolean, ongoing: Boolean): String = when {
        cause.why == Tear.OVERSIZED_EVENT -> "$head sent a stream event larger than splice accepts"
        answering -> cut(head, ongoing, cause.toolOpen)
        cause.why == Tear.NO_EVENTS -> "$head closed the connection before sending anything"
        else -> "$head closed the connection before answering"
    }

    private fun cut(head: String, ongoing: Boolean, toolOpen: Boolean): String {
        val cut = if (ongoing) "was cut off" else "kept getting cut off"
        return "$head's answer $cut" + if (toolOpen) " inside a tool call" else ""
    }

    private fun silent(head: String, ongoing: Boolean): String =
        "$head went silent mid-answer" + if (ongoing) "" else " and did not recover"
}
