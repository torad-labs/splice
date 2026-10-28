// Marcos, Oct 28: "claude code picks should also be considered, for all heads and all adapters we do. Always." A chat
// head sends the effort Claude Code's request names, built the way a head builds it, from the client's raw JSON.
package splice.dialect.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse

/** [json] built as a head builds it: the typed body and the client's own raw JSON beside it. */
private fun buildRaw(
    json: String,
    quirks: ChatQuirks = ChatQuirks(providerTag = "chat"),
    model: String = "chat-model",
): JsonObject {
    val parsed = AnthropicParse.parseAnthropicBody(json)
    return ChatRequestBuilder(quirks).build(parsed.typed, upstreamModel = model, compact = false, raw = parsed.raw).req
}

class ChatClientEffortTest {

    // Marcos, Oct 28: "claude code picks should also be considered, for all heads and all adapters we do. Always."
    // Claude Code names its effort in output_config (its /effort, its --effort, which a launch sets to the head's
    // configured effort), and a mid-conversation message's output_config changes it for the turn. A chat head sends
    // it, on the rungs its vendor takes: xhigh and max where the model takes xhigh, high elsewhere.
    @Test
    fun `a chat head sends the effort Claude Code's request names`() {
        val grok = ChatQuirks(
            providerTag = "claude-grok",
            reasoning = ChatReasoningQuirks(xhighModels = Regex("grok-4\\.(?:[6-9]|[1-9]\\d)")),
        )
        fun asked(effort: String) = """{"model":"m","messages":[{"role":"user","content":"hi"}],
            "thinking":{"type":"adaptive"},"output_config":{"effort":"$effort"}}"""
        fun sent(req: JsonObject) = req["reasoning_effort"]?.jsonPrimitive?.content
        assertEquals("low", sent(buildRaw(asked("low"))))
        assertEquals("medium", sent(buildRaw(asked("medium"))))
        assertEquals("high", sent(buildRaw(asked("xhigh"))), "a vendor without xhigh gets its top rung")
        assertEquals("xhigh", sent(buildRaw(asked("xhigh"), quirks = grok, model = "grok-4.6")))
        assertEquals("xhigh", sent(buildRaw(asked("max"), quirks = grok, model = "grok-4.6")))
        assertEquals("high", sent(buildRaw(asked("max"), quirks = grok, model = "grok-4.5")))
        val changed = """{"model":"m","output_config":{"effort":"high"},"messages":[
            {"role":"user","content":"hi"},{"role":"system","content":"x","output_config":{"effort":"low"}}]}"""
        assertEquals("low", sent(buildRaw(changed)), "the latest message's output_config changes the turn's effort")
    }
}
