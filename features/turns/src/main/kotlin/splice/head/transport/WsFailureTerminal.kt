// NEW: a WebSocket round's failure terminal, read once: its code and message, and whether it is a policy refusal.
package splice.head.transport

import kotlinx.serialization.json.JsonObject
import splice.core.turn.FailureCause
import splice.core.util.JsonScalars
import splice.upstream.failure.FailureSource
import splice.upstream.failure.UpstreamFailureClassifier

/** The failure terminal [evt] in every shape the dialect's reducer reads (ResponsesEventReducer.onFailure): an
 *  object under `response.error` or `error`, the flat event whose own `code`/`message` are the error, or a
 *  plain-string `error` (DR-109). */
internal class WsFailureTerminal(evt: JsonObject) {
    private val carried = (evt["response"] as? JsonObject)?.get("error") ?: evt["error"]
    private val error = carried as? JsonObject ?: evt

    /** The vendor's code; a flat event's `type` is its discriminator, never a code. */
    val code: String = JsonScalars.strOrEmpty(error["code"])
        .ifEmpty { if (error === evt) "" else JsonScalars.strOrEmpty(error["type"]) }

    val message: String = JsonScalars.strOrEmpty(error["message"]).ifEmpty { JsonScalars.strOrEmpty(carried) }

    /** A policy refusal is the vendor's verdict on the REQUEST: re-served over SSE, the identical context met the
     *  identical refusal (2026-10-04, 2:18 to 2:21 and 6:05 PM CT), so the round ends on it instead. The
     *  classifier that names the refusal on every other path decides, so no code list lives here. */
    fun policyRefusal(): Boolean =
        UpstreamFailureClassifier.classify(FailureSource.SSE, "$code $message", code = code.ifEmpty { null }).cause ==
            FailureCause.CONTENT_FILTERED
}
