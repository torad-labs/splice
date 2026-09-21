// V4-39: an allowlist-dropped message must never reach the wire with an empty content array.
//
// THE FIXTURE IS THE LIVE SHAPE, not a hand-invented one, because that is the whole reason this
// defect is real rather than theoretical: Claude Code sends exactly this — a user message whose
// entire content is ONE base64 image block — the first time the operator pastes a screenshot with
// no accompanying text. The allowlist drops `image` (DeepSeek's compatibility table does not list
// it), the message was left with nothing, and the turn 400d. Shape and the real allowlist below are
// taken from the shipped deepseek profile, so a change to either reddens this file.
package splice.dialect.anthropic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.util.LogSink
import java.util.Base64

class EmptiedMessageTest {

    private val json = Json

    /** The shipped deepseek allowlist, verbatim from config/splice.example.toml. */
    private val deepSeek = PassthroughQuirks(
        providerTag = "claude-deepseek",
        blockAllowlist = setOf(
            "text",
            "thinking",
            "tool_use",
            "tool_result",
            "server_tool_use",
            "web_search_tool_result",
        ),
    )

    // THE LIVE SHAPE. A bare screenshot paste is one image block and nothing else.
    @Test
    fun `a bare screenshot paste never becomes an empty content array`() {
        val scrubbed = scrub(
            deepSeek,
            """{"role":"user","content":[{"type":"image","source":{"type":"base64",""" +
                """"media_type":"image/png","data":"${png()}"}}]}""",
        )
        val content = scrubbed.single().jsonObject.getValue("content").jsonArray

        assertFalse(content.isEmpty(), "an empty content array is the defect this row exists for")
        assertEquals(1, content.size)
        assertEquals("text", content.single().jsonObject.getValue("type").jsonPrimitive.content)
        assertTrue(
            content.single().jsonObject.getValue("text").jsonPrimitive.content
                .contains("1 content block(s) omitted by claude-deepseek proxy"),
            content.single().jsonObject.toString(),
        )
        assertFalse(scrubbed.toString().contains("image"), "the image itself must not ride")
    }

    @Test
    fun `a text-plus-image message keeps its text and loses only the image`() {
        val content = scrub(
            deepSeek,
            """{"role":"user","content":[{"type":"text","text":"what is wrong here?"},""" +
                """{"type":"image","source":{"type":"base64","media_type":"image/png","data":"${png()}"}}]}""",
        ).single().jsonObject.getValue("content").jsonArray

        assertEquals(1, content.size, "the text survives and no marker is added")
        assertEquals("what is wrong here?", content.single().jsonObject.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun `a message whose blocks all survive is byte-identical`() {
        val request = """{"role":"user","content":[{"type":"text","text":"hello"},{"type":"text","text":"again"}]}"""
        val before = json.parseToJsonElement(request).jsonObject

        assertEquals(
            before.toString(),
            scrub(deepSeek, request).single().toString(),
            "the fix must not move a byte on the path that was already correct",
        )
    }

    @Test
    fun `an already-empty array and a bare string are the client's shape and are left alone`() {
        assertEquals(
            """{"role":"user","content":[]}""",
            scrub(deepSeek, """{"role":"user","content":[]}""").single().toString(),
        )
        assertEquals(
            """{"role":"user","content":"just text"}""",
            scrub(deepSeek, """{"role":"user","content":"just text"}""").single().toString(),
        )
    }

    // Every head but the ones that opted in has NO allowlist, and must be byte-identical to before
    // this existed — including the very image the allowlisted head now answers with a sentence.
    @Test
    fun `with no allowlist the same image rides upstream untouched`() {
        val request = """{"role":"user","content":[{"type":"image","source":{"type":"base64",""" +
            """"media_type":"image/png","data":"${png()}"}}]}"""

        assertEquals(
            json.parseToJsonElement(request).jsonObject.toString(),
            scrub(PassthroughQuirks(providerTag = "kimi"), request).single().toString(),
        )
    }

    // THE COMPOSITION of the two features, which nothing else pins: one call both reports the drop
    // on the anomaly channel (83a76be1) and leaves the message with a substitute block (V4-39).
    // A fake sink collecting into a list — never a real one.
    @Test
    fun `an emptied message both logs its dropped type and carries the substitute block`() {
        val logged = mutableListOf<String>()
        val scrubber = PassthroughMessageScrubber(
            deepSeek,
            PassthroughCacheControl(false),
            log = LogSink { line -> logged += line },
        )

        val content = scrubber.scrubMessages(
            json.parseToJsonElement(
                """[{"role":"user","content":[{"type":"image","source":{"type":"base64",""" +
                    """"media_type":"image/png","data":"${png()}"}}]}]""",
            ),
        ).single().jsonObject.getValue("content").jsonArray

        assertEquals(1, logged.size, "one line for the drop, latched per type: $logged")
        assertTrue(logged.single().contains("'image'"), logged.single())
        assertEquals(1, content.size, "and the message still tells the model something")
        assertTrue(
            content.single().jsonObject.getValue("text").jsonPrimitive.content.contains("omitted by"),
            content.single().toString(),
        )
    }

    /** Takes ONE message and returns the scrubbed MESSAGES ARRAY, which is what scrubMessages
     *  consumes — the single-message shape above is the readable form, the array is the wire's. */
    private fun scrub(quirks: PassthroughQuirks, message: String): JsonArray =
        PassthroughMessageScrubber(quirks, PassthroughCacheControl(false))
            .scrubMessages(json.parseToJsonElement("[$message]"))
}

/** A structurally real PNG, the way Claude Code encodes a screenshot: the bytes a paste carries. */
private fun png(): String {
    val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
        be32(IHDR_LEN) + "IHDR".toByteArray(Charsets.US_ASCII) + be32(SCREENSHOT_EDGE) + be32(SCREENSHOT_EDGE) +
        byteArrayOf(PNG_BIT_DEPTH, PNG_COLOR_RGBA, 0, 0, 0)
    return Base64.getEncoder().encodeToString(bytes)
}

private fun be32(v: Int): ByteArray =
    byteArrayOf((v ushr BITS_24).toByte(), (v ushr BITS_16).toByte(), (v ushr BITS_8).toByte(), v.toByte())

private const val IHDR_LEN = 13
private const val SCREENSHOT_EDGE = 1512
private const val PNG_BIT_DEPTH: Byte = 8
private const val PNG_COLOR_RGBA: Byte = 6
private const val BITS_8 = 8
private const val BITS_16 = 16
private const val BITS_24 = 24
