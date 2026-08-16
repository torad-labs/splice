// NEW (post-review, campaign claude-head): the TOML -> PassthroughQuirks overlay, pinned at the
// one place a misread silently corrupts the wire. The overlay uses null for "keep the head's base
// profile", which leaves `block_allowlist = []` as the only thing an operator can write to mean
// "no allowlist" — and read literally that is an allowlist permitting NOTHING.
package splice.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
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

    private val assembly = PassthroughAssembly()

    private val kimiBase = PassthroughQuirksDefaults().kimi("kimi")

    // Without the isNotEmpty guard this yields an EMPTY allowlist, and the builder then drops every
    // content block of every message — the upstream receives an empty conversation, with no error
    // and nothing logged. Empty means OFF.
    @Test
    fun `an empty block_allowlist means OFF, never an allowlist that permits nothing`() {
        val quirks = assembly.passthroughQuirks(provider(QuirksConfig(blockAllowlist = emptyList())), kimiBase)
        assertEquals(kimiBase.blockAllowlist, quirks.blockAllowlist, "empty must fall back to the base")
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
