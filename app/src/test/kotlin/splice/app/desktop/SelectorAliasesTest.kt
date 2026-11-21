// gateway-no-heads: every selector the gateway answers is read as a provider and that provider's own model id, with no
// head block in splice.toml: the current `claude-<provider>--<id>`, the gateway's built-in legacy prefixes, and a
// custom prefix the person names in `[door.selector_aliases]`. A selector of a provider splice.toml does not declare
// names nothing.
package splice.app.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.topology.HeadConfig

class SelectorAliasesTest {
    private val providers = listOf("codex", "xai", "inkling")

    @Test
    fun `canonical and legacy selectors both read with no head block at all`() {
        val aliases = SelectorAliases(providers)
        assertEquals(AliasedSelector("inkling", "Small-Q3"), aliases.of("claude-inkling--Small-Q3"))
        assertEquals(AliasedSelector("codex", "gpt-6.2-sol"), aliases.of("claude-codex--gpt-6.2-sol"))
        // an old prefix that named another word than its provider (the grok head ran on xai)
        assertEquals(AliasedSelector("xai", "grok-4.7"), aliases.of("claude-grok--grok-4.7"))
        assertEquals(AliasedSelector("xai", "grok-4.7"), aliases.of("claude-xai--grok-4.7"))
        assertEquals("claude-xai--grok-4.7", aliases.canonical("claude-grok--grok-4.7"))
    }

    @Test
    fun `a custom head prefix is read from the person's explicit alias, or from a head still there`() {
        val explicit = SelectorAliases(providers, explicit = mapOf("claude-local--" to "inkling"))
        assertEquals(AliasedSelector("inkling", "m"), explicit.of("claude-local--m"))
        val head = HeadConfig(provider = "inkling", port = 3110, discoveryPrefix = "claude-box--")
        val fromHead = SelectorAliases(providers, heads = listOf(head))
        assertEquals(AliasedSelector("inkling", "m"), fromHead.of("claude-box--m"))
        assertNull(SelectorAliases(providers).of("claude-box--m"), "with neither, the prefix means nothing")
    }

    @Test
    fun `a provider splice toml does not declare, and a prefix with no model id, name nothing`() {
        val aliases = SelectorAliases(listOf("codex"))
        assertNull(aliases.of("claude-grok--grok-4.7"))
        assertNull(aliases.of("claude-codex--"))
        assertNull(aliases.of("gpt-6.2-sol"))
        assertEquals("gpt-6.2-sol", aliases.canonical("gpt-6.2-sol"))
    }

    @Test
    fun `the explicit alias wins over a built-in prefix, and the longer prefix over the one it begins`() {
        val won = SelectorAliases(providers, explicit = mapOf("claude-codex--" to "inkling"))
        assertEquals("inkling", won.of("claude-codex--m")?.provider)
        val longer = SelectorAliases(providers, explicit = mapOf("claude-x--" to "codex", "claude-x-mini--" to "xai"))
        assertEquals("xai", longer.of("claude-x-mini--m")?.provider)
        assertEquals("codex", longer.of("claude-x--m")?.provider)
    }
}
