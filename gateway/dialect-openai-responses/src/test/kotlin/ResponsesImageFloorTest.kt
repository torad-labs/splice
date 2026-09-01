// NEW (DR-155): the vendor minimum-edge floor on the Responses wire. The live claude-grok head
// rides the chat dialect, so the failure measured in the DR-152 soak reached xAI through
// ChatWireMapper — but the vendor constraint belongs to the VENDOR, not to whichever dialect a head
// happens to select, and GrokQuirks carries the same floor for any Responses-dialect grok head.
//
// Two of these arms exist to pin what this row deliberately did NOT change: an unreadable
// tool_result image is still dropped silently here (a real gap, and not this one), and a compact
// turn still drops every image with no probe at all.
//
// The png builder below is duplicated from :core's ImageHeaderProbeTest on purpose — test source
// sets are not visible across Gradle modules, and per-format layout coverage lives there.
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplayParser
import splice.dialect.responses.BuildOptions
import splice.dialect.responses.InjectPriorReasoning
import splice.dialect.responses.RequestEncryptedReasoning
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesRequestBuilder
import java.util.Base64

class ResponsesImageFloorTest {

    // The honesty claim. Falling through to the existing marker would print "unsupported source",
    // which is false in a way an operator cannot debug: the source was read perfectly and the
    // BACKEND refused the size. Mutant: route the floor drop through imagePart's null.
    @Test
    fun `an undersized image says it was undersized, not that the source was unsupported - DR-155`() {
        val items = floored(
            """{"model":"m","messages":[{"role":"user","content":[
                {"type":"image","source":{"type":"base64","media_type":"image/png","data":"${png(1, 1)}"}}
            ]}]}""",
        )
        val text = items.single().textContent()
        assertEquals("[image omitted by claude-grok proxy: $FLOOR_REASON]", text)
        assertFalse(text.contains("unsupported source"), "the wrong story would be undebuggable: $text")
    }

    @Test
    fun `an image exactly at the floor still becomes an input_image - DR-155`() {
        val items = floored(
            """{"model":"m","messages":[{"role":"user","content":[
                {"type":"image","source":{"type":"base64","media_type":"image/png","data":"${png(8, 8)}"}}
            ]}]}""",
        )
        assertTrue(items.toString().contains("data:image/png;base64,${png(8, 8)}"), "8x8 must forward")
        assertFalse(items.toString().contains("omitted by"))
    }

    // THE DEFAULT IS THE SAFETY ARGUMENT: a head that did not opt in never decodes and never drops.
    // Mutant: give minImageEdgePx a non-null default.
    @Test
    fun `with no floor configured the same 1x1 becomes an input_image - DR-155`() {
        val items = items(
            """{"model":"m","messages":[{"role":"user","content":[
                {"type":"image","source":{"type":"base64","media_type":"image/png","data":"${png(1, 1)}"}}
            ]}]}""",
            CODEX,
        )
        assertTrue(items.toString().contains("data:image/png;base64,${png(1, 1)}"), "no floor, no drop")
        assertFalse(items.toString().contains("omitted by"))
    }

    // A screenshot tool whose output silently lost its image is the exact regression the v25 marker
    // doctrine exists to prevent, and this path emitted NOTHING for a dropped image before DR-155.
    @Test
    fun `an undersized tool_result image is declared instead of vanishing - DR-155`() {
        val items = floored(
            """{"model":"m","messages":[
                {"role":"assistant","content":[{"type":"tool_use","id":"t3","name":"shot","input":{}}]},
                {"role":"user","content":[{"type":"tool_result","tool_use_id":"t3","content":[
                    {"type":"text","text":"out"},
                    {"type":"image","source":{"type":"base64","media_type":"image/png","data":"${png(1, 1)}"}}
                ]}]}
            ]}""",
        )
        val marker = items.last().textContent()
        assertEquals("[1 image(s) from tool_result t3 omitted by claude-grok proxy: $FLOOR_REASON]", marker)
        assertFalse(items.toString().contains("images from tool_result"), "no follow-up for a dropped image")
        assertTrue(items.any { it["output"]?.jsonPrimitive?.content == "out" }, "the text output is untouched")
    }

    // SCOPE PIN, not an endorsement: an unmappable tool_result image is still dropped in silence
    // here, unlike the message walk which marks it. That is a real gap and it is NOT DR-155's — this
    // arm exists so the silence is a recorded decision rather than something nobody noticed, and so
    // a future repair of it reds here and gets read.
    @Test
    fun `an unreadable tool_result image is still silent, which this row did not change - DR-155`() {
        val items = floored(
            """{"model":"m","messages":[
                {"role":"assistant","content":[{"type":"tool_use","id":"t7","name":"shot","input":{}}]},
                {"role":"user","content":[{"type":"tool_result","tool_use_id":"t7","content":[
                    {"type":"text","text":"out"},
                    {"type":"image","source":{"type":"base64","media_type":"image/png","data":""}}
                ]}]}
            ]}""",
        )
        assertFalse(items.toString().contains("omitted by"), "unchanged behaviour: $items")
    }

    // An image splice cannot read is not an image splice may delete. "aGk=" is base64 of the word
    // "hi" — the fixture every other suite here uses — and it must still forward under a floor.
    @Test
    fun `an image the probe cannot read forwards under the floor - DR-155`() {
        val items = floored(
            """{"model":"m","messages":[{"role":"user","content":[
                {"type":"image","source":{"type":"base64","media_type":"image/png","data":"aGk="}}
            ]}]}""",
        )
        assertTrue(items.toString().contains("data:image/png;base64,aGk="), "unknown must forward")
        assertFalse(items.toString().contains("omitted by"))
    }

    // Compact is a text-only summarizer and returns before any image work. Probing there would cost
    // a decode per image on the one turn shape that has no use for the answer.
    @Test
    fun `a compact turn drops every image without probing or marking - DR-155`() {
        val items = items(
            """{"model":"m","messages":[{"role":"user","content":[
                {"type":"image","source":{"type":"base64","media_type":"image/png","data":"${png(1, 1)}"}}
            ]}]}""",
            FLOORED,
            opts(compact = true),
        )
        assertFalse(items.toString().contains("omitted by"), "no marker on compact: $items")
        assertFalse(items.toString().contains("input_image"))
    }
}

private val FLOORED = ResponsesQuirks(providerTag = "claude-grok", minImageEdgePx = XAI_FLOOR)
private val CODEX = ResponsesQuirks(providerTag = "claudex")

private fun opts(compact: Boolean = false) = BuildOptions(
    compact = compact,
    originalModel = "claude-grok--grok-4.6",
    upstreamModel = "grok-4.6",
    configEffort = null,
    configSummary = null,
    showReasoning = ReasoningDisplayParser.from("text"),
    replayReasoning = InjectPriorReasoning(false),
    includeEncryptedReasoning = RequestEncryptedReasoning(!compact),
    sessionId = null,
    decodeReasoningEnvelope = { data ->
        buildJsonObject { put("decoded", JsonPrimitive(data)) }
    },
)

private fun items(
    json: String,
    quirks: ResponsesQuirks,
    options: BuildOptions = opts(),
): List<JsonObject> {
    val parsed = AnthropicParse.parseAnthropicBody(json)
    return ResponsesRequestBuilder(quirks).build(parsed.typed, parsed.raw, options)
        .req["input"]!!.jsonArray.map { it.jsonObject }
}

private fun floored(json: String): List<JsonObject> = items(json, FLOORED)

private fun JsonObject.textContent(): String = this["content"]?.jsonPrimitive?.content.orEmpty()

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
