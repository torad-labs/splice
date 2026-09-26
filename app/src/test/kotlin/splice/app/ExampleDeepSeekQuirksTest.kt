// NEW: the example's DeepSeek head carries the quirks that ARE the DeepSeek integration (V4-35). Moved
// out of DeepSeekProfileTest in LAYOUT-01: the `splice add` profile lives in features/configuration,
// the example it is checked against is app's resource, and this arm reads only the example.
package splice.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.topology.Dialect
import splice.topology.TopologyLoader

class ExampleDeepSeekQuirksTest {

    /** The reasoning_cache scar in ExampleConfigTest's roster test is exactly this assertion's reason for
     *  existing, and it applies harder here: block_allowlist and strip_cache_control ARE the
     *  DeepSeek integration. If either were decorative — parsed and then ignored — every replayed
     *  signed-thinking turn would still ship a redacted_thinking block DeepSeek reject, and the
     *  head would fail in a way no unit test of the profile source could see. */
    @Test
    fun `deepseek quirks reach the parsed fields rather than decorating the example`() {
        val topology = TopologyLoader.parse(exampleToml())
        val deepseek = topology.providers[topology.heads["claude-deepseek"]!!.provider]!!
        assertEquals(Dialect.ANTHROPIC_PASSTHROUGH, deepseek.dialect)
        assertEquals("api-key", deepseek.auth.kind)
        assertEquals(true, deepseek.quirks.stripCacheControl)
        val allowed = deepseek.quirks.blockAllowlist!!
        assertTrue("thinking" in allowed, "thinking is supported and must ride")
        assertTrue("redacted_thinking" !in allowed, "DeepSeek reject redacted_thinking")
    }

    /** The committed example off the classpath, as ExampleConfigTest reads it: a TESTED artifact,
     *  so this reads the real file rather than a fixture. */
    private fun exampleToml(): String =
        checkNotNull(javaClass.getResourceAsStream("/splice.example.toml")) {
            "splice.example.toml is not on the classpath"
        }.bufferedReader().use { it.readText() }
}
