// resolveHeadKey: user-supplied head names arrive as the topology key OR the wrapper command
// (the shim passes argv[0]). The starter's `openrouter` head installs as `claudeor` — v0.1.1's
// first-run launch broke on exactly this mismatch.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.model.ModelEntry
import splice.core.topology.AuthConfig
import splice.core.topology.ClaudeWrapperConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology

class TopologyResolveHeadKeyTest {

    private fun head(command: String? = null) = HeadConfig(
        provider = "openrouter",
        port = 3101,
        discoveryPrefix = "claude-openrouter--",
        pinnedModel = "m",
        claude = ClaudeWrapperConfig(command = command),
    )

    private val provider = ProviderConfig(
        dialect = Dialect.OPENAI_CHAT,
        baseUrl = "https://openrouter.example/api/v1",
        auth = AuthConfig("api-key", env = "OPENROUTER_API_KEY"),
        models = listOf(ModelEntry("m", contextWindow = 200_000)),
    )

    private val topology = Topology(
        providers = mapOf("openrouter" to provider),
        heads = mapOf(
            "openrouter" to head(command = "claudeor"),
            "claudex" to head(command = null), // command defaults to the key
        ),
    )

    @Test
    fun `resolves a topology key verbatim`() {
        assertEquals("openrouter", topology.resolveHeadKey("openrouter"))
        assertEquals("claudex", topology.resolveHeadKey("claudex"))
    }

    @Test
    fun `resolves a wrapper command to its topology key`() {
        assertEquals("openrouter", topology.resolveHeadKey("claudeor"))
    }

    @Test
    fun `unknown names resolve to null`() {
        assertNull(topology.resolveHeadKey("nope"))
    }

    @Test
    fun `a key match wins over any command match`() {
        val shadowed = Topology(
            providers = mapOf("openrouter" to provider),
            heads = mapOf(
                "a" to head(command = "b"),
                "b" to head(command = "other"),
            ),
        )
        assertEquals("b", shadowed.resolveHeadKey("b"))
    }

    @Test
    fun `two heads sharing a command are ambiguous, not unknown`() {
        val ambiguous = Topology(
            providers = mapOf("openrouter" to provider),
            heads = mapOf(
                "a" to head(command = "dup"),
                "b" to head(command = "dup"),
            ),
        )
        // resolveHeadKey collapses ambiguity to null (same as unknown), so callers that must
        // distinguish the two use resolveHeadKeys, which lists BOTH colliding keys.
        assertNull(ambiguous.resolveHeadKey("dup"))
        assertEquals(listOf("a", "b"), ambiguous.resolveHeadKeys("dup").sorted())
        assertEquals(emptyList<String>(), ambiguous.resolveHeadKeys("nope"))
    }
}
