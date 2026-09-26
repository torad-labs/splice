// V4-179: the one tool_result image renderer, pinned on its own — the ordinary walk and the code-mode
// bridge both read it, so its ORDER (image message, then the unsupported-source marker, then the floor
// marker) and its per-image DISPOSITIONS are the contract, not an implementation detail. The png
// builder is the same one ResponsesImageFloorTest carries; it stays duplicated on purpose (test source
// sets do not share).
package splice.dialect.responses.request

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.wire.ToolResultBlock
import splice.dialect.responses.ResponsesQuirks
import java.util.Base64

class ResponsesToolResultMediaTest {

    @Test
    fun `mixed readable, unreadable and undersized images keep the ordinary path's order`() {
        val block = toolResult(
            """{"type":"image","source":{"type":"base64","media_type":"image/png","data":"${png(8, 8)}"}},""" +
                """{"type":"image","source":{"type":"base64","media_type":"image/png","data":""}},""" +
                """{"type":"image","source":{"type":"base64","media_type":"image/png","data":"${png(1, 1)}"}}""",
        )
        val rendered = ResponsesToolResultMedia(FLOORED).followUps(block)
        assertEquals(3, rendered.items.size, rendered.items.toString())
        val first = rendered.items[0].jsonObject
        assertEquals("user", first["role"]?.jsonPrimitive?.content)
        val parts = first.getValue("content").jsonArray
        assertEquals("[images from tool_result t1]", parts[0].jsonObject["text"]?.jsonPrimitive?.content)
        assertEquals("input_image", parts[1].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals(2, parts.size, "only the readable, above-floor image rides")
        assertEquals(
            "[1 image(s) from tool_result t1 omitted by claude-grok proxy: unsupported source]",
            rendered.items[1].jsonObject["content"]?.jsonPrimitive?.content,
        )
        assertEquals(
            "[1 image(s) from tool_result t1 omitted by claude-grok proxy: $FLOOR_REASON]",
            rendered.items[2].jsonObject["content"]?.jsonPrimitive?.content,
        )
        assertEquals(listOf(true, false, false), rendered.dispositions.map { it.delivered })
        assertEquals(listOf(null, "unsupported source", FLOOR_REASON), rendered.dispositions.map { it.reason })
    }

    @Test
    fun `a text-only tool_result renders nothing`() {
        val rendered = ResponsesToolResultMedia(CODEX).followUps(toolResult("""{"type":"text","text":"ok"}"""))
        assertTrue(rendered.items.isEmpty())
        assertTrue(rendered.dispositions.isEmpty())
    }

    @Test
    fun `with no floor configured a tiny image is delivered`() {
        val block = toolResult(
            """{"type":"image","source":{"type":"base64","media_type":"image/png","data":"${png(1, 1)}"}}""",
        )
        val rendered = ResponsesToolResultMedia(CODEX).followUps(block)
        assertEquals(1, rendered.items.size)
        assertTrue(rendered.dispositions.single().delivered)
        assertFalse(rendered.items.toString().contains("omitted"))
    }

    private fun toolResult(content: String): ToolResultBlock {
        val body = """{"model":"m","max_tokens":1,"messages":[{"role":"user","content":[""" +
            """{"type":"tool_result","tool_use_id":"t1","content":[$content]}]}]}"""
        return AnthropicParse.parseAnthropicBody(body).typed.messages.single().content.single() as ToolResultBlock
    }
}

private val FLOORED = ResponsesQuirks(providerTag = "claude-grok", minImageEdgePx = XAI_FLOOR)
private val CODEX = ResponsesQuirks(providerTag = "claudex")

private fun png(w: Int, h: Int): String {
    val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
        be32(IHDR_LEN) + "IHDR".toByteArray(Charsets.US_ASCII) + be32(w) + be32(h) +
        byteArrayOf(PNG_BIT_DEPTH, PNG_COLOR_RGBA, 0, 0, 0)
    return Base64.getEncoder().encodeToString(bytes)
}

private fun be32(v: Int): ByteArray =
    byteArrayOf((v ushr BITS_24).toByte(), (v ushr BITS_16).toByte(), (v ushr BITS_8).toByte(), v.toByte())

private const val XAI_FLOOR = 8
private const val FLOOR_REASON = "image edge below this backend's 8px minimum"
private const val IHDR_LEN = 13
private const val PNG_BIT_DEPTH: Byte = 8
private const val PNG_COLOR_RGBA: Byte = 6
private const val BITS_8 = 8
private const val BITS_16 = 16
private const val BITS_24 = 24
