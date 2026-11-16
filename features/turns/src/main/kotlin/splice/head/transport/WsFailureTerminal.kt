// NEW: a WebSocket round's failure terminal, read once: its code and message, and whether it is a refusal that the
// same context earns again over any transport.
// Failure-handling slice 3: the head's own reader decides the refusal, as it reads the same error over HTTP.
package splice.head.transport

import kotlinx.serialization.json.JsonObject
import splice.core.failure.FailureCause
import splice.core.failure.LoginOwner
import splice.core.failure.RefusedBy
import splice.core.util.JsonScalars
import splice.upstream.failure.FailedEvent
import splice.upstream.failure.Replies

/** The refusals that are the vendor's verdict on what was sent, not the model's answer to it. */
private val VERDICTS = setOf(RefusedBy.POLICY, RefusedBy.MONITOR)

/** The failure terminal [evt] in every shape the dialect's reducer reads (ResponsesEventReducer.onFailure): an
 *  object under `response.error` or `error`, the flat event whose own `code`/`message` are the error, or a
 *  plain-string `error` (DR-109). */
internal class WsFailureTerminal(private val evt: JsonObject) {
    private val carried = (evt["response"] as? JsonObject)?.get("error") ?: evt["error"]
    private val error = carried as? JsonObject ?: evt

    /** The vendor's code; a flat event's `type` is its discriminator, never a code. */
    val code: String = JsonScalars.strOrEmpty(error["code"])
        .ifEmpty { if (error === evt) "" else JsonScalars.strOrEmpty(error["type"]) }

    val message: String = JsonScalars.strOrEmpty(error["message"]).ifEmpty { JsonScalars.strOrEmpty(carried) }

    /** A policy refusal is the vendor's verdict on the REQUEST, and a safety monitor's stop its verdict on the
     *  conversation: re-served over SSE, the identical context met the identical refusal, so the round ends on it
     *  instead. The head's reader ([replies]) reads the event as sent, under its own type, so no code list lives
     *  here. */
    fun policyRefusal(replies: Replies, owner: LoginOwner): Boolean {
        val read = replies.event(FailedEvent(JsonScalars.strOrEmpty(evt["type"]), evt, owner))
        return read is FailureCause.Provider.Refused && read.by in VERDICTS
    }
}
