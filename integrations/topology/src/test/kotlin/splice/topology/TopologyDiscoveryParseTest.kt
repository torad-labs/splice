// NEW: 2026-09-23 — `discovery = { include, exclude }` on a provider, through the REAL topology
// parser. The parser refuses keys it does not know, so a discovery key it could not decode would fail
// every splice CLI call and the daemon's next boot on the operator's own splice.toml; the catalog
// tests build ModelDiscoveryConfig directly and cannot see that seam.
package splice.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.topology.ModelDiscoveryConfig

class TopologyDiscoveryParseTest {

    private fun provider(discovery: String) = TopologyLoader.parse(
        """
        [providers.openrouter]
        dialect = "openai-chat"
        base_url = "https://openrouter.ai/api/v1"
        auth = { kind = "api-key", env = "OPENROUTER_API_KEY" }
        $discovery
        """.trimIndent(),
    ).providers.getValue("openrouter")

    @Test
    fun `include and exclude reach the provider as written`() {
        val parsed = provider("""discovery = { include = ["openai/*", "qwen/*"], exclude = ["*:batch", "*:free"] }""")
        assertEquals(
            ModelDiscoveryConfig(include = listOf("openai/*", "qwen/*"), exclude = listOf("*:batch", "*:free")),
            parsed.discovery,
        )
        assertTrue(parsed.discovery.admits("openai/gpt-6-sol"))
        assertFalse(parsed.discovery.admits("openai/gpt-6-sol:batch"))
        assertFalse(parsed.discovery.admits("google/gemini-3-pro"))
    }

    @Test
    fun `one list alone parses, and a provider that names none admits every published model`() {
        val off = provider("""discovery = { exclude = ["*"] }""")
        assertEquals(ModelDiscoveryConfig(exclude = listOf("*")), off.discovery)
        assertEquals(ModelDiscoveryConfig(), provider("").discovery)
    }
}
