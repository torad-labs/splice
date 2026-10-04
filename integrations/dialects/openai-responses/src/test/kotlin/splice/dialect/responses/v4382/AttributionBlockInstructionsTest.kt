// NEW: V4-382 — the Responses dialect's `instructions` no longer carries Claude Code's per-session attribution block.
// Its fingerprint hashes the session's first user prompt, so two sessions (or a subagent, or the turn after a compaction)
// sent different first bytes, and the backend's prompt cache forked at the very front (bonsai, Sep 18: the post-compaction
// prompt forked at token 47,374). Two requests that differ only in that fingerprint must build the same instructions.
package splice.dialect.responses.v4382

import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.reasoning.InjectPriorReasoning
import splice.dialect.responses.reasoning.RequestEncryptedReasoning
import splice.dialect.responses.request.BuildOptions
import splice.dialect.responses.request.ResponsesRequestBuilder

class AttributionBlockInstructionsTest {
    private fun instructions(fingerprint: String): String {
        val parsed = AnthropicParse.parseAnthropicBody(
            """{"model":"m","system":[
                {"type":"text","text":"x-anthropic-billing-header: cc_version=2.1.284.$fingerprint; cc_entrypoint=sdk-cli;"},
                {"type":"text","text":"You are a Claude agent. "},{"type":"text","text":"Be terse."}],
               "messages":[{"role":"user","content":"hi"}]}""",
        )
        val options = BuildOptions(
            compact = false,
            originalModel = "m",
            upstreamModel = "m",
            configEffort = null,
            configSummary = null,
            showReasoning = ReasoningDisplay.TEXT,
            replayReasoning = InjectPriorReasoning(false),
            includeEncryptedReasoning = RequestEncryptedReasoning(false),
            decodeReasoningEnvelope = { null },
        )
        val builder = ResponsesRequestBuilder(ResponsesQuirks(providerTag = "claudex"))
        return builder.build(parsed.typed, parsed.raw, options).req.getValue("instructions").jsonPrimitive.content
    }

    @Test
    fun `two first prompts build the same instructions, with no attribution text in them`() {
        val first = instructions("12a")

        assertEquals("You are a Claude agent. Be terse.", first)
        assertEquals(first, instructions("a36"))
        assertFalse(first.contains("x-anthropic-billing-header"))
    }
}
