// NEW: V4-163 — the request bytes the usage default decides: stream_options.include_usage is what
// makes an OpenAI-compatible stream end with a usage frame, and without that frame the head reports
// zero tokens for the whole turn. The assembly arm (app campaign.v4163) picks WHO asks; this pins
// what asking and not asking look like on the wire, and that the overlay's null keeps the profile's
// own answer — the property every unopted head's byte-identity rests on.
package campaign.v4163

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.dialect.chat.ChatQuirks
import splice.dialect.chat.ChatRequestBuilder

private const val TURN = """{"model":"m","messages":[{"role":"user","content":"hi"}]}"""

class StreamUsageRequestTest {

    private fun request(quirks: ChatQuirks): JsonObject = ChatRequestBuilder(quirks)
        .build(
            AnthropicParse.parseAnthropicBody(TURN).typed,
            upstreamModel = "bonsai",
            originalModel = "claude-bonsai--bonsai",
            compact = false,
        )
        .req

    @Test
    fun `a head that asks carries stream_options include_usage`() {
        val options = request(ChatQuirks(providerTag = "bonsai", emitUsageInStream = true))["stream_options"]

        assertTrue(options?.jsonObject?.get("include_usage")?.jsonPrimitive?.boolean == true, "$options")
    }

    @Test
    fun `a head that does not ask sends no stream_options at all`() {
        assertNull(request(ChatQuirks(providerTag = "bonsai"))["stream_options"])
    }

    @Test
    fun `the overlay's null keeps the profile's own answer in both directions`() {
        val off = ChatQuirks(providerTag = "bonsai")
        val on = off.copy(emitUsageInStream = true)

        assertEquals(false, off.withStreamUsageToml(null).emitUsageInStream)
        assertEquals(true, on.withStreamUsageToml(null).emitUsageInStream)
        assertEquals(true, off.withStreamUsageToml(true).emitUsageInStream)
        assertEquals(false, on.withStreamUsageToml(false).emitUsageInStream)
    }
}
