// Refreshed roster membership respects accepted TOML windows, declared cards and head allowlists.
package splice.app.roster

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.daemon.HeadCatalogs
import splice.app.daemon.TopologyWindows
import splice.app.provider.ProviderBuild
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.model.DiscoveredModel
import splice.core.model.HeadDiscoveredModels
import splice.core.model.ModelRates
import splice.core.topology.Topology
import splice.core.turn.WatchdogBudget
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.time.Duration.Companion.seconds

private const val WINDOW_BOOT = """
[providers.synthetic]
dialect = "openai-chat"
base_url = "https://synthetic.example.test/v1"
auth = { kind = "api-key", env = "SYNTHETIC_KEY" }
[[providers.synthetic.models]]
id = "synthetic-original"
label = "Declared original"
context_window = 128000
rates = { input = 3.0, cache_read = 1.0, output = 9.0 }
[heads.synthetic]
provider = "synthetic"
port = 3201
discovery_prefix = "claude-synthetic--"
pinned_model = "synthetic-original"
"""

class RosterWindowJoinTest {
    @Test
    fun `accepted window edits still clamp newly published models to their provider ceiling`(
        @TempDir tmp: Path,
    ) {
        val file = tmp.resolve("splice.toml")
        Files.writeString(file, WINDOW_BOOT)
        var models = listOf(DiscoveredModel("synthetic-original"))
        val boot = TopologyLoader.parse(WINDOW_BOOT)
        val source = HeadDiscoveredModels { models }
        val windows = windows(file, boot, source)
        try {
            val catalog = windows.attach(context(tmp, boot, source), false).catalog
            assertEquals(128_000L, catalog.clientLaunchWindow)
            val edited = WINDOW_BOOT + "\ncontext_window = 400000\n"
            Files.writeString(file, edited)
            Files.setLastModifiedTime(file, FileTime.fromMillis(5_000))
            assertEquals(400_000L, catalog.clientLaunchWindow)
            assertFalse(windows.stale(), "the window edit was accepted before discovery")
            models += DiscoveredModel(
                "synthetic-small", "Small new", 256_000,
                rates = ModelRates(input = 2.0, cacheRead = 0.5, output = 8.0),
            )
            assertEquals(256_000L, catalog.contextWindowFor("synthetic-small"))
            assertEquals(400_000L, catalog.live().headWindow)
            assertEquals(400_000L, catalog.contextWindowFor("synthetic-original"))
            assertEquals("Small new", catalog.labelFor("synthetic-small"))
            assertEquals(TopologyLoader.sha256Hex(edited.toByteArray()), windows.digest())
        } finally {
            windows.close()
        }
    }

    @Test
    fun `a live compaction target edit retains discovery and takes an explicit serve ceiling`(@TempDir tmp: Path) {
        val file = tmp.resolve("splice.toml")
        Files.writeString(file, WINDOW_BOOT)
        val models = listOf(DiscoveredModel("synthetic-original", contextWindow = 272_000, maxContextWindow = 872_000))
        val source = HeadDiscoveredModels { models }
        val boot = TopologyLoader.parse(WINDOW_BOOT)
        val windows = windows(file, boot, source)
        try {
            val catalog = windows.attach(context(tmp, boot, source), false).catalog
            assertEquals(872_000L, catalog.live().models.single().maxContextWindow)
            val edited = WINDOW_BOOT.replace(
                "auth = { kind = \"api-key\", env = \"SYNTHETIC_KEY\" }",
                """auth = { kind = "api-key", env = "SYNTHETIC_KEY" }
extra_windows = [{ id = "synthetic-original", context_window = 400000, max_context_window = 800000 }]""",
            )
            Files.writeString(file, edited)
            Files.setLastModifiedTime(file, FileTime.fromMillis(5_000))
            assertEquals(400_000L, catalog.contextWindowFor("synthetic-original"))
            assertEquals(800_000L, catalog.live().extraWindows.single().maxContextWindow)
            assertEquals(872_000L, catalog.live().models.single().maxContextWindow)
            assertFalse(windows.stale())
        } finally {
            windows.close()
        }
    }

    @Test
    fun `refresh preserves declared rows and cards while removing only discovered membership`(@TempDir tmp: Path) {
        val boot = TopologyLoader.parse(WINDOW_BOOT)
        var models = listOf(
            DiscoveredModel("synthetic-original", "Vendor original", 64_000, rates = rates(1.0)),
            DiscoveredModel("synthetic-extra", rates = rates(2.0)),
        )
        val source = HeadDiscoveredModels { models }
        val windows = windows(null, boot, source)
        try {
            val catalog = windows.attach(context(tmp, boot, source), false).catalog
            assertEquals("Declared original", catalog.labelFor("synthetic-original"))
            assertEquals(3.0, catalog.live().models.first().rates?.input)
            assertTrue(catalog.contains("synthetic-extra"))
            models = listOf(DiscoveredModel("synthetic-extra", "Changed name", rates = rates(4.0)))
            assertEquals("Changed name", catalog.labelFor("synthetic-extra"))
            assertEquals(4.0, catalog.live().models.last().rates?.input)
            models = emptyList()
            assertFalse(catalog.contains("synthetic-extra"))
            assertTrue(catalog.contains("synthetic-original"))
            assertEquals(3.0, catalog.live().models.single().rates?.input)
        } finally {
            windows.close()
        }
    }

    @Test
    fun `a head allowlist admits a missing listed model only when discovery publishes it`(@TempDir tmp: Path) {
        val boot = TopologyLoader.parse(
            WINDOW_BOOT + """
models = [{ id = "synthetic-original", slot = "opus" }, { id = "synthetic-selected" }]
""",
        )
        var models = emptyList<DiscoveredModel>()
        val source = HeadDiscoveredModels { models }
        val windows = windows(null, boot, source)
        try {
            val catalog = windows.attach(context(tmp, boot, source), false).catalog
            assertFalse(catalog.contains("synthetic-selected"))
            models = listOf(DiscoveredModel("synthetic-selected"), DiscoveredModel("synthetic-other"))
            assertTrue(catalog.contains("synthetic-selected"))
            assertFalse(catalog.contains("synthetic-other"))
            assertEquals(listOf("synthetic-original", "synthetic-selected"), catalog.availableModelIds())
            models = emptyList()
            assertEquals(listOf("synthetic-original"), catalog.availableModelIds())
        } finally {
            windows.close()
        }
    }

    private fun rates(input: Double) = ModelRates(input = input, cacheRead = 0.5, output = 8.0)

    private fun windows(file: Path?, boot: Topology, source: HeadDiscoveredModels) = TopologyWindows(
        file,
        boot,
        TopologyLoader.sha256Hex(WINDOW_BOOT.toByteArray()),
        HeadCatalogs { key, head, provider, _ -> provider.catalogFor(head, discovered = source.forHead(key)) },
        {},
    )

    private fun context(tmp: Path, topology: Topology, source: HeadDiscoveredModels): ProviderBuild {
        val head = topology.heads.getValue("synthetic")
        val provider = topology.providers.getValue("synthetic")
        return ProviderBuild(
            key = "synthetic",
            head = head,
            providerCfg = provider,
            catalog = provider.catalogFor(head, discovered = source.forHead("synthetic")),
            watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
            cfg = ConfigService(StatePaths(baseOverride = tmp), envReader = { null }).getConfig("synthetic"),
            loginCommand = "",
            discovered = source,
        )
    }
}
