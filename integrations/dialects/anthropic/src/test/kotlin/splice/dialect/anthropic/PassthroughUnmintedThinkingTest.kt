package splice.dialect.anthropic

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.reasoning.ReasoningReplay
import splice.core.turn.SpliceSignatures
import splice.core.util.LogSink

/** A GPT session resumed on claude-splice replays the GPT head's reasoning summaries as thinking
 *  with `signature: ""`; Anthropic refuses the whole request for one. The verifying head drops them. */
class PassthroughUnmintedThinkingTest {
    private val anthropicSignature = "EqQBCkgIBRABGAIiQL-opaque-anthropic-signature"
    private val envelope = ReasoningReplay.encodeReasoningEnvelope(
        kotlinx.serialization.json.buildJsonObject {
            put("id", kotlinx.serialization.json.JsonPrimitive("rs_1"))
            put("encrypted_content", kotlinx.serialization.json.JsonPrimitive("gAAAA-ciphertext"))
        },
    )!!

    @Test
    fun `the verifying head drops thinking Anthropic did not sign and keeps what it did`() {
        val blocks = assistantBlocks(
            verifying = true,
            """{"type":"thinking","thinking":"**Reading code and logs**","signature":""},
               {"type":"thinking","thinking":"no signature field at all"},
               {"type":"thinking","thinking":"kimi reasoning","signature":"${SpliceSignatures.SYNTHESIZED}"},
               {"type":"thinking","thinking":"real reasoning","signature":"$anthropicSignature"},
               {"type":"redacted_thinking","data":"$envelope"},
               {"type":"redacted_thinking","data":"anthropic-opaque-redacted"},
               {"type":"text","text":"the answer"}""",
        )
        assertEquals(listOf("thinking", "redacted_thinking", "text"), blocks.map { it.type() })
        assertEquals(anthropicSignature, blocks[0]["signature"]!!.jsonPrimitive.content)
        assertEquals("real reasoning", blocks[0]["thinking"]!!.jsonPrimitive.content)
        assertEquals("anthropic-opaque-redacted", blocks[1]["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a head whose upstream does not verify keeps every block as before`() {
        val blocks = assistantBlocks(
            verifying = false,
            """{"type":"thinking","thinking":"**Reading code and logs**","signature":""},
               {"type":"thinking","thinking":"kimi reasoning","signature":"${SpliceSignatures.SYNTHESIZED}"},
               {"type":"redacted_thinking","data":"$envelope"},
               {"type":"text","text":"the answer"}""",
        )
        assertEquals(listOf("thinking", "thinking", "redacted_thinking", "text"), blocks.map { it.type() })
        assertEquals(SpliceSignatures.SYNTHESIZED, blocks[1]["signature"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a message of only unsigned thinking becomes one honest block, never an empty array`() {
        val blocks = assistantBlocks(verifying = true, """{"type":"thinking","thinking":"**Header**","signature":""}""")
        assertEquals(listOf("text"), blocks.map { it.type() })
        assertTrue(blocks.single()["text"]!!.jsonPrimitive.content.startsWith("1 content block(s) omitted"))
    }

    @Test
    fun `each drop reason is logged once however many turns replay it`() {
        val lines = mutableListOf<String>()
        val builder = builder(verifying = true, LogSink { lines += it })
        repeat(3) {
            build(builder, """{"type":"thinking","thinking":"a","signature":""},{"type":"text","text":"t"}""")
        }
        val reason = lines.filter { it.contains("thinking with no signature") }
        assertEquals(1, reason.size, lines.toString())
        assertFalse(reason.single().contains("**"), "the log names the reason, never the reasoning text")
    }

    private fun assistantBlocks(verifying: Boolean, content: String): List<JsonObject> =
        build(builder(verifying), content)

    private fun builder(verifying: Boolean, log: LogSink = LogSink {}) = PassthroughRequestBuilder(
        PassthroughQuirks(
            providerTag = "claude-splice",
            thinking = PassthroughThinkingQuirks(
                verifiesThinkingSignatures = verifying,
            ),
        ),
        log = log,
    )

    private fun build(builder: PassthroughRequestBuilder, content: String): List<JsonObject> = builder.build(
        AnthropicParse.parseAnthropicBody(
            """{"model":"m","messages":[{"role":"user","content":"hi"},{"role":"assistant","content":[$content]},
                {"role":"user","content":"next"}]}""",
        ),
        upstreamModel = "m",
        originalModel = "claude-splice--m",
        compact = false,
    ).req["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.type() = this["type"]!!.jsonPrimitive.content
}
