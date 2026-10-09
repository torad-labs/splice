// NEW: V4-382 — the openai-chat dialect's system message no longer carries Claude Code's per-session attribution block,
// whose fingerprint hashes the first user prompt and so forks a backend's prompt cache at the front of every session,
// subagent and post-compaction prompt. Two requests that differ only in that fingerprint build the same system message.
package splice.dialect.chat.v4382

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.dialect.chat.ChatQuirks
import splice.dialect.chat.ChatRequestBuilder

class AttributionBlockSystemMessageTest {
    private fun systemMessage(fingerprint: String): String {
        val body = AnthropicParse.parseAnthropicBody(
            """{"model":"m","system":[
                {"type":"text","text":"x-anthropic-billing-header: cc_version=2.1.284.$fingerprint; cc_entrypoint=sdk-cli;"},
                {"type":"text","text":"You are a Claude agent. "},{"type":"text","text":"Be terse."}],
               "messages":[{"role":"user","content":"hi"}]}""",
        ).typed
        val built = ChatRequestBuilder(ChatQuirks(providerTag = "bonsai"))
            .build(body, upstreamModel = "bonsai", compact = false)
        val first = built.req.getValue("messages").jsonArray.first().jsonObject
        assertEquals("system", first.getValue("role").jsonPrimitive.content)
        return first.getValue("content").jsonPrimitive.content
    }

    @Test
    fun `two first prompts build the same system message, with no attribution text in it`() {
        val first = systemMessage("12a")

        assertEquals("You are a Claude agent. Be terse.", first)
        assertEquals(first, systemMessage("a36"))
    }
}
