import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.model.ModelEntry
import splice.core.topology.AuthConfig
import splice.core.topology.DaemonConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.topology.catalogFor
import splice.core.topology.configOverrides
import java.nio.file.Files

class TopologyConfigOverridesTest {

    private val topology = Topology(
        daemon = DaemonConfig(controlPort = 4123),
        providers = mapOf(
            "codex" to ProviderConfig(
                dialect = Dialect.OPENAI_RESPONSES,
                baseUrl = "https://toml.example/codex",
                auth = AuthConfig("chatgpt-oauth", file = "~/custom/codex.json"),
                models = listOf(ModelEntry("toml-codex", contextWindow = 100_000)),
            ),
            "grok" to ProviderConfig(
                dialect = Dialect.OPENAI_RESPONSES,
                baseUrl = "https://toml.example/grok",
                auth = AuthConfig("grok-oauth", file = "~/custom/grok.json"),
                models = listOf(ModelEntry("toml-grok", contextWindow = 200_000)),
            ),
        ),
        heads = mapOf(
            "codex" to HeadConfig("codex", 4101, "claude-codex--", "toml-codex"),
            "grok" to HeadConfig("grok", 4102, "claude-grok--", "toml-grok"),
        ),
    )

    @Test
    fun `topology seeds every legacy management knob it owns`() {
        val layer = topology.configOverrides()
        assertEquals("4123", layer["controlPort"])
        assertEquals("4101", layer["port"])
        assertEquals("toml-codex", layer["pinnedModel"])
        assertEquals("https://toml.example/codex", layer["chatgptApiBase"])
        assertEquals("~/custom/codex.json", layer["codexAuthPath"])
        assertEquals("4102", layer["grokPort"])
        assertEquals("toml-grok", layer["grokModel"])
        assertEquals("https://toml.example/grok", layer["xaiApiBase"])
        assertEquals("~/custom/grok.json", layer["grokAuthPath"])
    }

    @Test
    fun `environment wins over topology for restart-applied settings`() {
        val paths = StatePaths(baseOverride = Files.createTempDirectory("topology-config"))
        val service = ConfigService(
            paths,
            headOverrides = topology.configOverrides(),
            envReader = { name ->
                when (name) {
                    "SPLICE_CONTROL_PORT" -> "5123"
                    "CLAUDEX_PINNED_MODEL" -> "env-codex"
                    else -> null
                }
            },
        )
        assertEquals(5123, service.getConfig().controlPort)
        assertEquals("env-codex", service.getConfig().pinnedModel)
    }

    @Test
    fun `context override changes exact models and fallback consistently`() {
        val provider = topology.providers.getValue("codex")
        val head = topology.heads.getValue("codex")
        val catalog = provider.catalogFor(head, contextWindowOverride = 333_000)
        assertEquals(333_000, catalog.contextWindowFor("toml-codex"))
        assertEquals(333_000, catalog.contextWindowFor("future-model"))
    }
}
