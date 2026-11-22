// NEW: builds the follow-up RoundBody when an `on_stop` hook answers Continue, split from HookRounds.
package splice.harness.hook

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.parse.lenientJson
import splice.core.util.JsonScalars
import splice.harness.agent.AgentAsk
import splice.harness.agent.AgentWire
import splice.harness.agent.USER
import splice.upstream.RoundBody

internal sealed class RoundDecision {
    data object Pass : RoundDecision()
    data class Rewrite(val body: JsonObject) : RoundDecision()
    data class Replace(val text: String) : RoundDecision()
    data class Continue(val text: String) : RoundDecision()
    data class Refuse(val mode: String, val reason: String) : RoundDecision()
}

internal class HookContinue(
    private val wire: AgentWire,
    private val next: AgentAsk,
) {
    fun build(
        body: RoundBody,
        produced: MutableList<JsonObject>,
        assistantBlocks: List<JsonObject>,
        followUp: String,
    ): RoundBody? {
        val userBlock = buildJsonObject {
            put("type", "text")
            put("text", JsonPrimitive(followUp))
        }
        val made = listOf(wire.message(wire.assistant, assistantBlocks), wire.message(USER, listOf(userBlock)))
        val reqObj = (body as? RoundBody.Tree)?.element as? JsonObject
            ?: JsonScalars.objectOrNull(lenientJson, body.text)
        val built = next.next(reqObj, produced + made, made) ?: return null
        produced += made
        return RoundBody.Tree(built)
    }
}
