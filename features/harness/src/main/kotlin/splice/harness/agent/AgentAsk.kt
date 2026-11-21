// NEW: a door mode on every dialect (docs/specs/harness.md, decision 12, Modes). The request a round after an answered
// call sends: the harness's own request in Anthropic's Messages shape with every message the provider produced so
// far after the client's, built by the turn into its provider's request; on a head whose provider sends Messages, the
// round's own request with the round's messages after it, as before.
package splice.harness.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.head.port.HarnessTurn

/** The requests after [asked], the Messages request the turn [turn] sent first. */
internal class AgentAsk(private val asked: JsonObject, private val turn: HarnessTurn) {
    /** The next request: [round] (the round's own request, null when it cannot be read) with [made] after it, or the
     *  turn's build of [asked] with every message in [produced] after the client's. Null when neither can be made. */
    fun next(round: JsonObject?, produced: List<JsonObject>, made: List<JsonObject>): JsonObject? =
        turn.built(after(asked, produced)) ?: round?.let { after(it, made) }

    private fun after(body: JsonObject, messages: List<JsonObject>): JsonObject {
        val held = (body[ASK_MESSAGES] as? JsonArray).orEmpty()
        return JsonObject(body + (ASK_MESSAGES to JsonArray(held + messages)))
    }
}

private const val ASK_MESSAGES = "messages"
