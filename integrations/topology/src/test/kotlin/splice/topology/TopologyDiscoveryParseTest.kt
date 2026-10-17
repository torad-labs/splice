// NEW: 2026-09-23 — `discovery = { include, exclude }` on a provider, through the REAL topology
// parser. The parser refuses keys it does not know, so a discovery key it could not decode would fail
// every splice CLI call and the daemon's next boot on the operator's own splice.toml; the catalog
// tests build ModelDiscoveryConfig directly and cannot see that seam.
package splice.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import splice.core.model.DiscoveredModel
import splice.core.topology.ModelDiscoveryConfig
import splice.core.topology.Topology

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
    fun `family is a discovery key, inline or as a sub-table, and its absence keeps the other keys`() {
        val inline = provider("""discovery = { family = "vast" }""")
        assertEquals("vast", inline.family)
        assertEquals(ModelDiscoveryConfig(family = "vast"), inline.discovery)

        val table = TopologyLoader.parse(
            """
            [providers.bonsai]
            dialect = "openai-chat"
            base_url = "http://127.0.0.1:8100/v1"
            auth = { kind = "api-key", env = "BONSAI_API_KEY" }

            [providers.bonsai.discovery]
            include = ["bonsai-*"]
            family = "vast"
            """.trimIndent(),
        ).providers.getValue("bonsai")
        assertEquals(ModelDiscoveryConfig(include = listOf("bonsai-*"), family = "vast"), table.discovery)

        val none = provider("""discovery = { include = ["openai/*"] }""")
        assertEquals(null, none.family)
        assertEquals(ModelDiscoveryConfig(include = listOf("openai/*")), none.discovery)
    }

    @Test
    fun `a family written at the provider's top level, where it used to live, is refused rather than ignored`() {
        assertThrows<Exception> { provider("""family = "vast"""") }
    }

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

    @Test
    fun `an explicit serve ceiling decodes independently of the compaction target`() {
        val parsed = provider(
            """extra_windows = [{ id = "synthetic-model", context_window = 400000, max_context_window = 872000 }]""",
        )
        assertEquals(400_000L, parsed.extraWindows.single().contextWindow)
        assertEquals(872_000L, parsed.extraWindows.single().maxContextWindow)
        assertThrows<IllegalArgumentException> {
            provider(
                """extra_windows = [{ id = "synthetic-model", context_window = 400000, max_context_window = 0 }]""",
            )
        }
    }

    @Test
    fun `tier mappings reject unknown repeated and competing declarations`() {
        val invalid = listOf(
            """model_slots = { unknown = "m1" }""",
            """model_slots = { opus = "" }""",
            """model_slots = { opus = "m1", sonnet = "m1" }""",
            """model_slots = { opus = "m1", OPUS = "m2" }""",
            """model_slots = { opus = "m1", opus = "m2" }""",
            """model_slots = { "opus" = "m1", 'opus' = "m2" }""",
            """model_slots = { opus = "m1" }
               model_slots = { sonnet = "m2" }""",
            """[heads.one.model_slots]
               opus = "m1"
               opus = "m2" """,
        )
        invalid.forEach { extra ->
            assertThrows<IllegalArgumentException>(extra) { withHead(extra) }
        }
        val mixed = assertThrows<IllegalArgumentException> {
            withHead(
                """models = [{ id = "m1", slot = "opus" }]
                   model_slots = { sonnet = "m1" }""",
            )
        }
        assertTrue(mixed.message.orEmpty().contains("keep"), mixed.message)
        assertTrue(mixed.message.orEmpty().contains("model_slots"), mixed.message)
    }

    @Test
    fun `quoted tier keys normalize like bare keys without restricting the catalog`() {
        for (declaration in listOf(
            """model_slots = { "Opus" = "m1", 'sonnet' = "m2" }""",
            """[heads.one.model_slots]
               "Opus" = "m1"
               'sonnet' = "m2" """,
        )) {
            val topology = withHead(declaration)
            val head = topology.heads.getValue("one")
            val catalog = topology.providers.getValue("p").catalogFor(head, discovered = listOf(DiscoveredModel("m2")))
            assertEquals(mapOf("m1" to "opus", "m2" to "sonnet"), head.tierSlots())
            assertEquals(emptyMap<String, String>(), catalog.unmappedTiers)
        }
    }

    @Test
    fun `mapped tiers require a declared or discovered served id`() {
        val topology = withHead("""model_slots = { opus = "m1", sonnet = "m2" }""")
        val head = topology.heads.getValue("one")
        val provider = topology.providers.getValue("p")
        assertEquals(listOf("m1"), provider.catalogFor(head).models.map { it.id })
        val unlisting = provider.copy(discovery = ModelDiscoveryConfig(exclude = listOf("*")))
        val failure = assertThrows<IllegalArgumentException> { unlisting.catalogFor(head) }
        assertTrue(failure.message.orEmpty().contains("model_slots.sonnet"), failure.message)
        val catalog = provider.catalogFor(head, discovered = listOf(DiscoveredModel("m2")))
        assertEquals(listOf("m1", "m2"), catalog.models.map { it.id })
        assertEquals(mapOf("m1" to "opus", "m2" to "sonnet"), head.tierSlots())
    }

    @Test
    fun `separate tiers respect an explicit serving allowlist`() {
        val topology = withHead(
            """models = [{ id = "m1" }]
               model_slots = { opus = "m1" }""",
        )
        val head = topology.heads.getValue("one")
        val provider = topology.providers.getValue("p")
        assertEquals(mapOf("m1" to "opus"), head.tierSlots())
        assertEquals(
            listOf("m1"),
            provider.catalogFor(head, discovered = listOf(DiscoveredModel("m2"))).models.map { it.id },
        )
        assertThrows<IllegalArgumentException> {
            provider.catalogFor(
                head.copy(modelSlots = mapOf("opus" to "m2")),
                discovered = listOf(DiscoveredModel("m2")),
            )
        }
    }

    private fun withHead(extra: String): Topology = TopologyLoader.parse(
        """
        [providers.p]
        dialect = "openai-chat"
        base_url = "https://example.invalid/v1"
        auth = { kind = "api-key", env = "FIXTURE_KEY" }
        models = [{ id = "m1", context_window = 64000 }]
        [heads.one]
        provider = "p"
        port = 3105
        discovery_prefix = "claude-one--"
        pinned_model = "m1"
        ${extra.trimIndent()}
        """.trimIndent(),
    )
}
