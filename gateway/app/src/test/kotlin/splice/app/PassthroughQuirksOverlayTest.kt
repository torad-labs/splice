// NEW (post-review, campaign claude-head): the TOML -> PassthroughQuirks overlay, pinned at the
// one place a misread silently corrupts the wire. The overlay uses null for "keep the head's base
// profile", which leaves `block_allowlist = []` as the only thing an operator can write to mean
// "no allowlist" — and read literally that is an allowlist permitting NOTHING.
package splice.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import splice.app.provider.QuirksOverlay
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.core.topology.QuirksConfig
import splice.dialect.passthrough.PassthroughQuirks
import splice.dialect.passthrough.PassthroughQuirksDefaults

private fun provider(quirks: QuirksConfig) = ProviderConfig(
    dialect = Dialect.ANTHROPIC_PASSTHROUGH,
    baseUrl = "https://example.invalid",
    auth = AuthConfig("api-key"),
    quirks = quirks,
)

class PassthroughQuirksOverlayTest {

    private val assembly = QuirksOverlay()

    private val kimiBase = PassthroughQuirksDefaults().kimi("kimi")

    // Without the isNotEmpty guard this yields an EMPTY allowlist, and the builder then drops every
    // content block of every message — the upstream receives an empty conversation, with no error
    // and nothing logged. Empty means OFF.
    @Test
    fun `an empty block_allowlist means OFF, never an allowlist that permits nothing`() {
        val quirks = assembly.passthroughQuirks(provider(QuirksConfig(blockAllowlist = emptyList())), kimiBase)
        assertNull(quirks.blockAllowlist, "empty must turn the allowlist OFF")
    }

    @Test
    fun `a declared allowlist replaces the base`() {
        val quirks = assembly.passthroughQuirks(provider(QuirksConfig(blockAllowlist = listOf("text"))), kimiBase)
        assertEquals(setOf("text"), quirks.blockAllowlist)
    }

    // ABSENT keeps the base — this is what makes a splice.toml written before these knobs existed
    // keep serving a kimi head unchanged.
    @Test
    fun `absent knobs keep the base profile`() {
        val quirks = assembly.passthroughQuirks(provider(QuirksConfig()), kimiBase)
        assertEquals(kimiBase, quirks)
    }

    @Test
    fun `an explicitly declared knob still wins over the base`() {
        val quirks = assembly.passthroughQuirks(
            provider(QuirksConfig(stripCacheControl = false, mfjs = false)),
            kimiBase,
        )
        assertEquals(false, quirks.stripCacheControl)
        assertEquals(false, quirks.mfjsSanitize)
    }

    // DR-121: compact_effort's TOML field is shared with the codex knob, whose vocabulary
    // includes "medium" — a value the kimi ladder never emits. Pre-fix the overlay returned it
    // raw, so every thinking-carrying compact turn shipped output_config.effort "medium" and
    // 400ed until the TOML was fixed. The wall is the quirk type's own init, so it fires at
    // assembly (daemon boot), naming the fix, instead of riding the wire.
    @Test
    fun `compact_effort outside the kimi rungs fails at assembly, never rides the wire - DR-121`() {
        val ex = assertThrows<IllegalArgumentException> {
            assembly.passthroughQuirks(provider(QuirksConfig(compactEffort = "medium")), kimiBase)
        }
        assertTrue("compact_effort" in ex.message!!, ex.message)
        // the legal vocabulary rides in the message — the operator learns the fix from the failure
        assertTrue("low|high|max" in ex.message!!, ex.message)
    }

    @Test
    fun `compact_effort on a kimi rung still overlays cleanly - DR-121 control`() {
        val quirks = assembly.passthroughQuirks(provider(QuirksConfig(compactEffort = "high")), kimiBase)
        assertEquals("high", quirks.compactEffort)
    }

    // A neutral base (the claude head) declares nothing and must stay faithful.
    @Test
    fun `a neutral base with no declarations stays faithful`() {
        val neutral = PassthroughQuirks(providerTag = "claude-splice")
        val quirks = assembly.passthroughQuirks(provider(QuirksConfig()), neutral)
        assertEquals(false, quirks.stripCacheControl)
        assertEquals(false, quirks.mfjsSanitize)
        assertEquals(false, quirks.synthesizeSignatures)
        assertNull(quirks.blockAllowlist)
    }
}
