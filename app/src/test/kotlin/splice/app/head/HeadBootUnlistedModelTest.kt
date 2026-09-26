// NEW: 2026-09-23 — a head whose `models` list names a model its endpoint did not list at this start.
// The topology drops that row and keeps the head (Topology.modelsFor); this file pins that the drop is
// SAID, because a dropped row the log never names is a picker entry that silently vanished.
package splice.app.head

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.HeadAssembly
import splice.app.control.ManagedHead
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.auth.Credentials
import splice.core.config.StatePaths
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.HeadModel
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.diagnostics.logs.HeadLogSource
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.RateLimitView
import splice.usage.quota.UsageView
import java.nio.file.Path

class HeadBootUnlistedModelTest {

    @TempDir
    lateinit var tmp: Path

    private val provider = ProviderConfig(
        dialect = Dialect.OPENAI_CHAT,
        baseUrl = "https://api.x.ai/v1",
        auth = AuthConfig("api-key", env = "XAI_API_KEY"),
        models = listOf(ModelEntry("grok-4.6", contextWindow = 500_000)),
    )

    /** Assembly as the daemon does it for this test's purpose: the head carries its catalog, built from
     *  the provider's rows with nothing discovered — the start where the endpoint listed nothing new. */
    private val assembly = HeadAssembly { key, head, provider -> managed(key, provider.catalogFor(head)) }

    private fun boot(head: HeadConfig): List<String> {
        val logs = mutableListOf<String>()
        val failed = HeadBoot().assembleDaemonHeads(
            Topology(providers = mapOf("xai" to provider), heads = mapOf("xai" to head)),
            StatePaths(baseOverride = tmp.resolve("state")),
            mutableMapOf(),
            logs::add,
            assembly,
        )
        assertEquals(emptyMap<String, String>(), failed, "a model the endpoint did not list never fails the head")
        return logs
    }

    @Test
    fun `a listed model the endpoint did not list is named, and the head still starts`() {
        val head = HeadConfig("xai", 4104, "claude-grok--", "grok-4.6")
        val logs = boot(head.copy(models = listOf(HeadModel("grok-4.6", "opus"), HeadModel("grok-4.7", "sonnet"))))
        val named = logs.single { "models list names" in it }
        assertTrue(named.startsWith("[xai][boot] models list names grok-4.7, which its endpoint did not list"), named)
        assertTrue(boot(head.copy(models = listOf(HeadModel("grok-4.6", "opus")))).none { "models list names" in it })
    }

    private fun managed(key: String, catalog: ModelCatalog): ManagedHead = ManagedHead(
        head = object : Head {
            override val key: String = key
            override val label: String = key
            override val port: Int = 0
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
            override fun healthSnapshot(): HeadHealth = HeadHealth(true, true, 0, "test")
        },
        auth = object : AuthProvider {
            override suspend fun credentials(): Credentials? = null
            override suspend fun describe() = AuthDescription(false, "test", emptyMap())
        },
        usage = HeadUsageSource { UsageView(0, 0, RateLimitView(null, null, null)) },
        compact = object : HeadCompactSource {
            override fun summary(tailN: Int): CompactView = CompactView(0, emptyMap(), emptyList())
        },
        logs = object : HeadLogSource {
            override fun tail(lines: Int): String = ""
            override fun path(): String = ""
        },
        warnPct = 80,
        warnTokens5h = 0,
        catalog = catalog,
    )
}
