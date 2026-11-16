// NEW: server mode (docs/specs/server-mode.md, F5). The status an answer was given under, where the response's own no
// longer says it. A kept-alive stream is opened as a 200 before anyone has answered (HeadAnswers.early), and what
// comes after it, a refusal, a silence or an answer that is no stream, is said as the stream's error event. Whoever
// records how the request went reads the status here, so a 429 that came late is still a refusal in the ledger and a
// server that never answered is never a served turn.
package splice.share

import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey

public object AnsweredStatus {
    private val late = AttributeKey<Int>("splice.relay.late-status")

    /** [call]'s stream was already open when its answer came as [status]. */
    internal fun late(call: ApplicationCall, status: Int) {
        call.attributes.put(late, status)
    }

    /** The status [call] was answered under: the late answer's where its stream opened first, else the response's
     *  own; null while it has neither. */
    public fun of(call: ApplicationCall): Int? = call.attributes.getOrNull(late) ?: call.response.status()?.value
}
