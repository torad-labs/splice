package splice.dialect.anthropic.v4385

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.turn.SpliceNotice
import splice.dialect.anthropic.PassthroughQuirks
import splice.dialect.anthropic.PassthroughRequestBuilder

class SpliceNoticeReplayTest {
    private val realSignature = "opaque-real-model-signature"

    @Test
    fun `splice notice is excluded while real signed reasoning and answer ride unchanged`() {
        val request = build(
            """{"role":"assistant","content":[
                {"type":"thinking","thinking":"[splice] holding this turn open. 34s into the turn, no output yet.",
                    "signature":"${SpliceNotice.SIGNATURE}"},
                {"type":"thinking","thinking":"model's own reasoning","signature":"$realSignature"},
                {"type":"text","text":"final answer"}
            ]}""",
        )
        val blocks = request["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("thinking", "text"), blocks.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("model's own reasoning", blocks[0]["thinking"]!!.jsonPrimitive.content)
        assertEquals(realSignature, blocks[0]["signature"]!!.jsonPrimitive.content)
        assertEquals("final answer", blocks[1]["text"]!!.jsonPrimitive.content)
        assertFalse(request.toString().contains(SpliceNotice.SIGNATURE), request.toString())
        assertFalse(request.toString().contains("[splice] holding"), request.toString())
    }

    @Test
    fun `notice-shaped model text with a different signature is not stripped by prefix`() {
        val near = "${SpliceNotice.SIGNATURE}-not-the-marker"
        val block = build(
            """{"role":"assistant","content":[
                {"type":"thinking","thinking":"[splice] holding this turn open, but said by a model",
                    "signature":"$near"}
            ]}""",
        )["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.single().jsonObject
        assertEquals(near, block["signature"]!!.jsonPrimitive.content)
        assertEquals("[splice] holding this turn open, but said by a model", block["thinking"]!!.jsonPrimitive.content)
    }

    private fun build(message: String) = PassthroughRequestBuilder(
        PassthroughQuirks(providerTag = "claude-muse"),
    ).build(
        AnthropicParse.parseAnthropicBody("""{"model":"m","messages":[$message]}"""),
        upstreamModel = "m",
        originalModel = "claude-muse--m",
        compact = false,
    ).req
}
