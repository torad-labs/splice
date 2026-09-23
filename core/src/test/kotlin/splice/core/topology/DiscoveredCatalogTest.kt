// NEW: 2026-09-22 — a head's catalog is its provider's declared rows plus what the provider's list
// endpoint published at daemon start (ProviderConfig.catalogFor's `discovered`). These pin the merge:
// which rows join, what window each takes, and that nothing about a declared row moves.
package splice.core.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.model.DiscoveredModel
import splice.core.model.ExtraWindow
import splice.core.model.ModelEntry
import splice.core.model.WindowRule

class DiscoveredCatalogTest {

    private val provider = ProviderConfig(
        dialect = Dialect.OPENAI_CHAT,
        baseUrl = "https://api.x.ai/v1",
        auth = AuthConfig("api-key", env = "XAI_API_KEY"),
        models = listOf(
            ModelEntry("grok-4.6", label = "Grok 4.6", contextWindow = 500_000),
            ModelEntry("grok-4.3[1m]", label = "Grok 4.3 1M", contextWindow = 1_000_000),
            ModelEntry("grok-build-latest", label = "Grok Build", contextWindow = 256_000),
        ),
    )
    private val head = HeadConfig("xai", 4104, "claude-grok--", "grok-4.6")

    private fun ids(discovered: List<DiscoveredModel>, on: ProviderConfig = provider, pinned: HeadConfig = head) =
        on.catalogFor(pinned, discovered = discovered).availableModelIds()

    @Test
    fun `nothing discovered is exactly the declared catalog`() {
        assertEquals(provider.models.map { it.id }, ids(emptyList()))
        assertEquals(ids(emptyList()), provider.catalogFor(head).availableModelIds())
    }

    @Test
    fun `discovered models follow the declared rows in endpoint order, and a declared row keeps its model`() {
        val discovered = listOf(
            DiscoveredModel("grok-4.7", contextWindow = 500_000),
            // The declared row wins: its window and label are decisions, the endpoint's are facts.
            DiscoveredModel("grok-4.6", label = "endpoint label", contextWindow = 2_000_000),
            DiscoveredModel("grok-4.20", contextWindow = 1_000_000),
        )
        val catalog = provider.catalogFor(head, discovered = discovered)
        assertEquals(
            listOf("grok-4.6", "grok-4.3[1m]", "grok-build-latest", "grok-4.7", "grok-4.20"),
            catalog.availableModelIds(),
        )
        val declared = catalog.models.first { it.id == "grok-4.6" }
        assertEquals(500_000L, declared.contextWindow)
        assertEquals("Grok 4.6", declared.label)
        assertFalse(declared.discovered)
        assertTrue(catalog.models.first { it.id == "grok-4.7" }.discovered)
        // The catalog is also the "proxies its own models only" gate: a discovered id passes it.
        assertTrue(catalog.contains("grok-4.20"))
    }

    @Test
    fun `a tier row covers its bare id and an alias row covers the model it aliases`() {
        val discovered = listOf(
            DiscoveredModel("grok-4.3", contextWindow = 1_000_000),
            DiscoveredModel("grok-4.5", aliases = listOf("grok-4.5-latest", "grok-build-latest")),
        )
        assertEquals(provider.models.map { it.id }, ids(discovered), "both are already declared under another spelling")
    }

    @Test
    fun `the discovery filter keeps a model out, and exclude star turns discovery off`() {
        val discovered = listOf(DiscoveredModel("grok-4.7"), DiscoveredModel("grok-imagine-video"))
        val filtered = provider.copy(discovery = ModelDiscoveryConfig(exclude = listOf("grok-imagine-*")))
        assertEquals(provider.models.map { it.id } + "grok-4.7", ids(discovered, filtered))
        val included = provider.copy(discovery = ModelDiscoveryConfig(include = listOf("grok-4.*")))
        assertEquals(provider.models.map { it.id } + "grok-4.7", ids(discovered, included))
        val off = provider.copy(discovery = ModelDiscoveryConfig(exclude = listOf("*")))
        assertEquals(provider.models.map { it.id }, ids(discovered, off))
        // A glob is a glob: the dot in "grok-4.*" is literal, not "any character".
        val literal = provider.copy(discovery = ModelDiscoveryConfig(include = listOf("grok-4.*")))
        assertEquals(provider.models.map { it.id }, ids(listOf(DiscoveredModel("grok-4x7")), literal))
    }

    @Test
    fun `a discovered window is the operator's exact id, then the published ceiling, then the rules`() {
        val windowed = provider.copy(
            extraWindows = listOf(ExtraWindow("pinned-by-id", 128_000)),
            windowRules = listOf(WindowRule("ruled-", 64_000)),
            defaultContextWindow = 32_000,
        )
        val catalog = windowed.catalogFor(
            head,
            discovered = listOf(
                DiscoveredModel("pinned-by-id", contextWindow = 999_000),
                DiscoveredModel("published", contextWindow = 400_000),
                DiscoveredModel("ruled-model"),
                DiscoveredModel("defaulted"),
            ),
        )
        val window = catalog.models.associate { it.id to it.contextWindow }
        assertEquals(128_000L, window["pinned-by-id"], "the operator's exact-id window outranks the endpoint")
        assertEquals(400_000L, window["published"])
        assertEquals(64_000L, window["ruled-model"])
        assertEquals(32_000L, window["defaulted"])
        val floor = provider.catalogFor(head, discovered = listOf(DiscoveredModel("unwindowed")))
        assertEquals(200_000L, floor.models.first { it.id == "unwindowed" }.contextWindow)
    }

    @Test
    fun `an allowlist may name a discovered model, which is a tier candidate for that head`() {
        val allowlisted = head.copy(models = listOf(HeadModel("grok-4.6", "opus"), HeadModel("grok-4.7", "sonnet")))
        val catalog = provider.catalogFor(allowlisted, discovered = listOf(DiscoveredModel("grok-4.7")))
        assertEquals(listOf("grok-4.6", "grok-4.7"), catalog.availableModelIds())
        // Named by the head, so it is the head's decision: a tier candidate, or its sonnet slot would
        // be dropped as naming a model the launch cannot place.
        assertEquals(listOf("grok-4.6", "grok-4.7"), catalog.tierModelIds())
    }

    // 2026-09-23 (review): a retired model, or a start whose discovery timed out, once threw here and
    // took the whole head down with it. The row is dropped instead and HeadBoot names it.
    @Test
    fun `an allowlisted model the endpoint did not list is dropped, and only a provider that lists nothing refuses it`() {
        val allowlisted = head.copy(models = listOf(HeadModel("grok-4.6", "opus"), HeadModel("grok-4.7", "sonnet")))
        assertEquals(listOf("grok-4.6"), provider.catalogFor(allowlisted).availableModelIds())
        val off = provider.copy(discovery = ModelDiscoveryConfig(exclude = listOf("*")))
        val failure = assertThrows(IllegalArgumentException::class.java) { off.catalogFor(allowlisted) }
        assertTrue(failure.message.orEmpty().contains("which lists no models"), failure.message)
    }

    // A provider with no rows is its endpoint's list, which can omit the pinned id: retired, filtered
    // out, or spelled only as another model's alias. Its row stays, or every default turn is refused.
    @Test
    fun `the pinned model is served whatever the endpoint lists`() {
        val bare = provider.copy(models = emptyList(), discovery = ModelDiscoveryConfig(exclude = listOf("grok-4.6")))
        val listed = listOf(DiscoveredModel("grok-4.6-0709", aliases = listOf("grok-4.6")), DiscoveredModel("grok-4.7"))
        val catalog = bare.catalogFor(head, discovered = listed)
        assertTrue(catalog.contains("grok-4.6"), "${catalog.availableModelIds()}")
        assertEquals(listOf("grok-4.6-0709", "grok-4.7", "grok-4.6"), catalog.availableModelIds())
        val allowlisted = head.copy(models = listOf(HeadModel("grok-4.6", "opus"), HeadModel("grok-4.7", "sonnet")))
        val named = bare.catalogFor(allowlisted, discovered = listed)
        assertEquals(listOf("grok-4.6", "grok-4.7"), named.availableModelIds())
    }

    @Test
    fun `a head-wide window never raises a published ceiling, and still sets the declared and unpublished rows`() {
        val capped = head.copy(contextWindow = 1_000_000)
        val catalog = provider.catalogFor(
            capped,
            discovered = listOf(
                DiscoveredModel("small-model", contextWindow = 262_144),
                DiscoveredModel("unpublished"),
            ),
        )
        val window = catalog.models.associate { it.id to it.contextWindow }
        assertEquals(262_144L, window["small-model"])
        assertEquals(1_000_000L, window["unpublished"])
        assertEquals(1_000_000L, window["grok-4.6"], "a declared row takes the head's window as before")
    }

    @Test
    fun `a provider with no rows serves what its endpoint lists, and its pinned model when nothing answered`() {
        val bare = provider.copy(models = emptyList())
        val listed = listOf(DiscoveredModel("grok-4.7"), DiscoveredModel("grok-4.6"))
        assertEquals(listOf("grok-4.7", "grok-4.6"), ids(listed, bare))
        val unanswered = bare.catalogFor(head)
        assertEquals(listOf("grok-4.6"), unanswered.availableModelIds())
        // The pinned fallback is the operator's own model, so it may stand behind a tier.
        assertEquals(listOf("grok-4.6"), unanswered.tierModelIds())
    }

    @Test
    fun `only declared rows are tier candidates`() {
        val catalog = provider.catalogFor(head, discovered = listOf(DiscoveredModel("grok-code-mini")))
        assertEquals(provider.models.map { it.id }, catalog.tierModelIds())
        assertTrue("grok-code-mini" in catalog.availableModelIds())
    }
}
