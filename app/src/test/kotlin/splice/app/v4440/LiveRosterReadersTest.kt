// NEW: V4-440 — a roster changed after assembly reaches membership, discovery, pricing and the launch picker.
package splice.app.v4440

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.auth.SignInPlanner
import splice.app.daemon.HeadCatalogs
import splice.app.daemon.TopologyWindows
import splice.app.head.LaunchSpecFactory
import splice.app.provider.HeadBuildInputs
import splice.app.provider.HeadModelsSource
import splice.app.provider.ModelRosters
import splice.app.provider.ProviderBuild
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.model.DiscoveredModel
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates
import splice.core.model.TurnPrice
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.turn.WatchdogBudget
import splice.models.discovery.Discovery
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private const val NEW_MODEL = "synthetic-new"
private const val MODEL_URL = "https://synthetic.example.test/v1/models"

class LiveRosterReadersTest {
    @Test
    fun `a roster refresh reaches every retained catalog and a newly launched picker`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val paths = StatePaths(baseOverride = tmp)
        val provider = ProviderConfig(
            dialect = Dialect.OPENAI_CHAT,
            baseUrl = "https://synthetic.example.test/v1",
            auth = AuthConfig("api-key", env = "SYNTHETIC_KEY"),
            models = listOf(ModelEntry("synthetic-original", contextWindow = 128_000)),
        )
        val head = HeadConfig("synthetic", 0, "claude-synthetic--", "synthetic-original")
        var models = listOf(DiscoveredModel("synthetic-original"))
        val rosters = ModelRosters(paths, {}, HeadModelsSource { _, _ -> Discovery.Found(MODEL_URL, models) })
        rosters.resolve(mapOf("synthetic" to provider))
        val topology = Topology(providers = mapOf("synthetic" to provider), heads = mapOf("synthetic" to head))
        val config = ConfigService(paths, envReader = { null })
        val signIn = SignInPlanner()
        val inputs = HeadBuildInputs(config, signIn, rosters)
        val ctx = ProviderBuild(
            key = "synthetic",
            head = head,
            providerCfg = provider,
            catalog = provider.catalogFor(head, discovered = rosters.forHead("synthetic")),
            watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
            cfg = config.getConfig("synthetic"),
            loginCommand = "",
            discovered = rosters,
        )
        val windows = windowsFor(topology, inputs)
        try {
            val live = windows.attach(ctx, false)
            val catalog = live.catalog
            val price = TurnPrice(catalog)
            val spec = LaunchSpecFactory(topology, signIn, MgmtKey(paths), inputs).launchSpecFor(live, 3098, false)
            assertFalse(catalog.contains(NEW_MODEL))
            models = models + DiscoveredModel(
                NEW_MODEL, "New synthetic", 256_000,
                rates = ModelRates(input = 2.0, cacheRead = 0.5, output = 8.0),
            )

            rosters.resolve(mapOf("synthetic" to provider))

            assertReaders(catalog, price)
            assertPicker(spec.withWindows(catalog), catalog)
            models = models.map { it.copy(label = "Renamed synthetic") }
            rosters.resolve(mapOf("synthetic" to provider))
            assertPicker(spec.withWindows(catalog), catalog, "Renamed synthetic")
        } finally {
            windows.close()
        }
    }

    private fun windowsFor(topology: Topology, inputs: HeadBuildInputs) = TopologyWindows(
        null,
        topology,
        "",
        HeadCatalogs { key, h, p, _ -> inputs.catalogFor(key, h, p, false) },
        {},
    )

    private fun assertReaders(catalog: ModelCatalog, price: TurnPrice) {
        assertTrue(catalog.contains(NEW_MODEL), "turn admission must see the refreshed roster")
        assertTrue(catalog.discoveryRows().any { it.id == catalog.wrap(NEW_MODEL) }, "/v1/models")
        assertEquals("New synthetic", catalog.labelFor(NEW_MODEL), "status line name")
        assertEquals(256_000L, catalog.contextWindowFor(NEW_MODEL))
        assertEquals(10.0, price.usd(NEW_MODEL, mapOf("in_tokens" to 1_000_000L, "out_tokens" to 1_000_000L)))
    }

    private fun assertPicker(spec: splice.launch.LaunchSpec, catalog: ModelCatalog, label: String = "New synthetic") {
        assertEquals(catalog.availableModelIds(), spec.availableModelIds)
        assertEquals(label, spec.modelLabels[NEW_MODEL])
        val option = (spec.modelOptionsCache as JsonArray).single {
            it.jsonObject.getValue("value").jsonPrimitive.content == NEW_MODEL
        }.jsonObject
        assertEquals(label, option.getValue("label").jsonPrimitive.content)
        assertEquals("256000", option.getValue("context_window").jsonPrimitive.content)
        assertFalse(NEW_MODEL in spec.tiers.candidates.orEmpty(), "discovery never adds a tier slot")
    }
}
