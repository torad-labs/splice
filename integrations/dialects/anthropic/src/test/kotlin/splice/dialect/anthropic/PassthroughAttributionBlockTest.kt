// The passthrough head keeps Claude Code's attribution block. Anthropic reads `x-anthropic-billing-header`
// for attribution, and the typed system text drops it for the translated dialects (AnthropicWireCodecs), so this pins the
// other side of that split: the passthrough builds its `system` from the client's RAW body, byte for byte, first block
// included. If it ever read the typed text, the block would leave Anthropic's requests along with the translated ones.
package splice.dialect.anthropic

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse

private const val ATTRIBUTION = "x-anthropic-billing-header: cc_version=2.1.284.12a; cc_entrypoint=sdk-cli;"

class PassthroughAttributionBlockTest {
    private val request = """{"model":"m","system":[
        {"type":"text","text":"$ATTRIBUTION"},
        {"type":"text","text":"You are a Claude agent.","cache_control":{"type":"ephemeral"}},
        {"type":"text","text":"Be terse."}],
        "messages":[{"role":"user","content":"hi"}]}"""

    private fun systemTexts(quirks: PassthroughQuirks): List<String> {
        val built = PassthroughRequestBuilder(quirks).build(
            AnthropicParse.parseAnthropicBody(request),
            upstreamModel = "m",
            originalModel = "claude-splice--m",
            compact = false,
        )
        return built.req.getValue("system").jsonArray.map { it.jsonObject.getValue("text").jsonPrimitive.content }
    }

    @Test
    fun `the faithful passthrough head forwards the attribution block as the first system block`() {
        val texts = systemTexts(PassthroughQuirks(providerTag = "claude-splice"))

        assertEquals(listOf(ATTRIBUTION, "You are a Claude agent.", "Be terse."), texts)
    }

    @Test
    fun `a passthrough that strips cache control still forwards it`() {
        val texts = systemTexts(PassthroughQuirks(providerTag = "claude-muse", stripCacheControl = true))

        assertTrue(texts.first() == ATTRIBUTION, "$texts")
        assertEquals(3, texts.size)
    }
}
