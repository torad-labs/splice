// NEW: desktop-door — Anthropic's own model IDs, in every spelling Claude Code sends them, are a tier.
package splice.client.models

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AnthropicTiersTest {
    private val tiers = AnthropicTiers()

    @Test
    fun `every spelling of an Anthropic ID is its tier`() {
        assertEquals("opus", tiers.tierOf("claude-opus-5-5"))
        assertEquals("opus", tiers.tierOf("anthropic.claude-opus-5-5"))
        assertEquals("opus", tiers.tierOf("us.anthropic.claude-opus-4-1-20250805-v1:0"))
        assertEquals("sonnet", tiers.tierOf("claude-3-7-sonnet-20250219"))
        assertEquals("haiku", tiers.tierOf("claude-haiku-5-5"))
        assertEquals("fable", tiers.tierOf("claude-fable-5-2"))
        assertEquals("opus", tiers.tierOf("opus"))
    }

    @Test
    fun `every spelling of one Anthropic model names that model, and a tier's alias names none`() {
        assertEquals("claude-opus-5-5", tiers.modelOf("claude-opus-5-5"))
        assertEquals("claude-opus-5-5", tiers.modelOf("anthropic.claude-opus-5-5"))
        assertEquals("claude-haiku-4-5", tiers.modelOf("global.anthropic.claude-haiku-4-5-20251001-v1:0"))
        assertNull(tiers.modelOf("opus"))
        assertNull(tiers.modelOf("grok-4.7"))
        assertNull(tiers.modelOf("claude--claude-opus-5-5"))
    }

    @Test
    fun `a model that is not Anthropic's is no tier`() {
        assertNull(tiers.tierOf("gpt-5.5"))
        assertNull(tiers.tierOf("claude-astra--astra-large"))
        assertNull(tiers.tierOf(""))
    }
}
