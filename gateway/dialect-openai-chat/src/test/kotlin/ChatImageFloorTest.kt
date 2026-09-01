// NEW (DR-155): the vendor minimum-edge floor on the chat wire — the dialect that serves the live
// claude-grok head. Six turns in the DR-152 soak died on a byte-identical HTTP 400 from xAI,
// code=invalid_image, "Image dimensions 1x1 are too small. Both width and height must be at least 8
// pixels.", because splice forwarded whatever the client sent with no dimension check at all.
//
// Two properties are pinned here and they pull in opposite directions, which is the point: an image
// PROVEN undersized is dropped with an honest sentence of its own, and everything else — including
// every head that did not opt in — is byte-identical to before this existed.
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.dialect.chat.ChatQuirks
import splice.dialect.chat.ChatRequestBuilder
import java.util.Base64

class ChatImageFloorTest {

    @Test
    fun `an undersized image is dropped with its own marker and the turn survives - DR-155`() {
        val req = floored(
            """{"model":"m","messages":[{"role":"user","content":[
                {"type":"text","text":"look at this"},
                {"type":"image","source":{"type":"base64","media_type":"image/png","data":"${png(1, 1)}"}}
            ]}]}""",
        )
        val content = req.messages().single().textContent()
        // The turn SURVIVES: pre-fix this request reached xAI intact and the whole turn 400d.
        assertTrue(content.contains("look at this"), "the sibling text must still ride: $content")
        assertTrue(content.contains("1 image(s) omitted by claude-grok proxy: $FLOOR_REASON"), content)
        assertFalse(req.toString().contains("image_url"), "the undersized image must not reach the wire")
    }

    // The boundary itself is legal — xAI's minimum is "at least 8", not "more than 8". Mutant: <=.
    @Test
    fun `an image exactly at the floor still rides upstream - DR-155`() {
        val req = floored(
            """{"model":"m","messages":[{"role":"user","content":[
                {"type":"image","source":{"type":"base64","media_type":"image/png","data":"${png(8, 8)}"}}
            ]}]}""",
        )
        assertTrue(req.toString().contains("data:image/png;base64,${png(8, 8)}"), "8x8 must forward")
        assertFalse(req.toString().contains("omitted by"), "and carry no marker")
    }

    // THE DEFAULT IS THE SAFETY ARGUMENT. Every head that did not opt in must be byte-identical to
    // before this feature existed — no decode, no drop, not even for the exact image that kills a
    // grok turn. Mutant: give minImageEdgePx a non-null default.
    @Test
    fun `with no floor configured the same 1x1 rides upstream untouched - DR-155`() {
        val req = build(
            """{"model":"m","messages":[{"role":"user","content":[
                {"type":"image","source":{"type":"base64","media_type":"image/png","data":"${png(1, 1)}"}}
            ]}]}""",
            ChatQuirks(providerTag = "kimi"),
        )
        assertTrue(req.toString().contains("data:image/png;base64,${png(1, 1)}"), "no floor, no drop")
        assertFalse(req.toString().contains("omitted by"))
    }

    // THE RELABEL MUTANT, named by grok-splice's map: fold the floor drop into DR-94's unreadable
    // count and this arm still sees "2 image(s) omitted" — but it now tells the operator the source
    // could not be read, when it read perfectly and the BACKEND refused the size. Two events, two
    // counts, two sentences.
    @Test
    fun `an undersized and an unreadable image keep separate counts and reasons - DR-155`() {
        val content = floored(
            """{"model":"m","messages":[{"role":"user","content":[
                {"type":"image","source":{"type":"base64","media_type":"image/png","data":"${png(1, 1)}"}},
                {"type":"image","source":{"type":"base64","media_type":"image/png","data":""}}
            ]}]}""",
        ).messages().single().textContent()
        assertTrue(content.contains("1 image(s) omitted by claude-grok proxy: unreadable image source"), content)
        assertTrue(content.contains("1 image(s) omitted by claude-grok proxy: $FLOOR_REASON"), content)
        assertEquals(2, content.markers(), "exactly one marker per reason, never a merged count: $content")
    }

    // With vision OFF the image is dropped for a reason that already has a marker. Probing anyway
    // would say the same image was omitted twice for two different reasons — the double-marker
    // class DR-94's split exists to prevent. Mutant: drop the supportsVision gate in belowFloor.
    @Test
    fun `a no-vision backend never adds the floor marker as a second story - DR-155`() {
        val content = build(
            """{"model":"m","messages":[{"role":"user","content":[
                {"type":"image","source":{"type":"base64","media_type":"image/png","data":"${png(1, 1)}"}}
            ]}]}""",
            ChatQuirks(providerTag = "claude-grok", supportsVision = false, minImageEdgePx = XAI_FLOOR),
        ).messages().single().textContent()
        assertTrue(content.contains("backend has no vision"), content)
        assertFalse(content.contains(FLOOR_REASON), "one image, one story: $content")
        assertEquals(1, content.markers(), content)
    }

    @Test
    fun `an undersized tool_result image is declared inside the tool output - DR-155`() {
        val req = floored(
            """{"model":"m","messages":[
                {"role":"assistant","content":[{"type":"tool_use","id":"t4","name":"shot","input":{}}]},
                {"role":"user","content":[{"type":"tool_result","tool_use_id":"t4","content":[
                    {"type":"text","text":"took screenshot"},
                    {"type":"image","source":{"type":"base64","media_type":"image/png","data":"${png(1, 1)}"}}
                ]}]}
            ]}""",
        )
        val tool = req.messages().first { it["role"]?.jsonPrimitive?.content == "tool" }
        val content = tool.textContent()
        assertTrue(content.contains("took screenshot"), content)
        assertTrue(content.contains("1 image(s) omitted by claude-grok proxy: $FLOOR_REASON"), content)
        // and no dangling follow-up user message for an image that never mapped
        assertFalse(req.messages().last().toString().contains("images from tool_result"))
    }

    // The realistic tool_result: a real screenshot, a broken source, and a favicon-sized image. All
    // three dispositions must be individually true in one output.
    @Test
    fun `a mixed tool_result marks each drop for what it was and forwards the rest - DR-155`() {
        val req = floored(
            """{"model":"m","messages":[
                {"role":"assistant","content":[{"type":"tool_use","id":"t5","name":"shot","input":{}}]},
                {"role":"user","content":[{"type":"tool_result","tool_use_id":"t5","content":[
                    {"type":"image","source":{"type":"base64","media_type":"image/png","data":"${png(64, 64)}"}},
                    {"type":"image","source":{"type":"base64","media_type":"image/png","data":""}},
                    {"type":"image","source":{"type":"base64","media_type":"image/png","data":"${png(1, 1)}"}}
                ]}]}
            ]}""",
        )
        val content = req.messages().first { it["role"]?.jsonPrimitive?.content == "tool" }.textContent()
        assertTrue(content.contains("1 image(s) omitted by claude-grok proxy: unreadable image source"), content)
        assertTrue(content.contains("1 image(s) omitted by claude-grok proxy: $FLOOR_REASON"), content)
        assertEquals(2, content.markers(), content)
        assertTrue(req.messages().last().toString().contains("base64,${png(64, 64)}"), "the real one forwards")
    }

    // An image splice cannot read is NOT an image splice may delete. "aGk=" is base64 of the word
    // "hi" — the fixture every other suite in this repo uses — and under a floor it must still
    // forward, because a proxy that drops what it fails to parse is a worse defect than the one
    // this row repairs. Mutant: treat a null probe as below the floor.
    @Test
    fun `an image the probe cannot read forwards under the floor - DR-155`() {
        val req = floored(
            """{"model":"m","messages":[{"role":"user","content":[
                {"type":"image","source":{"type":"base64","media_type":"image/png","data":"aGk="}}
            ]}]}""",
        )
        assertTrue(req.toString().contains("data:image/png;base64,aGk="), "unknown must forward")
        assertFalse(req.toString().contains("omitted by"))
    }
}

private val FLOORED = ChatQuirks(providerTag = "claude-grok", minImageEdgePx = XAI_FLOOR)

private fun build(json: String, quirks: ChatQuirks): JsonObject =
    ChatRequestBuilder(quirks)
        .build(
            AnthropicParse.parseAnthropicBody(json).typed,
            upstreamModel = "grok-4.6",
            originalModel = "claude-grok--grok-4.6",
            compact = false,
        )
        .req

private fun floored(json: String): JsonObject = build(json, FLOORED)

private fun JsonObject.messages() = this["messages"]!!.jsonArray.map { it.jsonObject }

private fun JsonObject.textContent(): String = this["content"]?.jsonPrimitive?.content.orEmpty()

private fun String.markers(): Int = split("omitted by").size - 1

// A REAL png header, built rather than pasted, so an arm claiming "this is 1x1" can be checked by
// reading it. Per-format layout coverage (jpeg/gif/webp, truncation, magic-vs-media_type) lives in
// :core's ImageHeaderProbeTest; this dialect only needs a source whose size is beyond doubt.
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
