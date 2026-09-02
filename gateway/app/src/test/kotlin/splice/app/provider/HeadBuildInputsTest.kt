package splice.app.provider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.app.SignInPlanner
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import java.nio.file.Files

class HeadBuildInputsTest {

    @Test
    fun `head key text cannot opt an unrelated provider into Grok overrides`() {
        val config = ConfigService(
            statePaths = StatePaths(baseOverride = Files.createTempDirectory("head-build-inputs")),
            headOverrides = mapOf(
                "grokPort" to "4999",
                "grokModel" to "grok-override",
                "xaiApiBase" to "https://grok.invalid",
            ),
            envReader = { null },
        )
        val inputs = HeadBuildInputs(config, SignInPlanner())
        val provider = ProviderConfig(
            dialect = Dialect.OPENAI_RESPONSES,
            baseUrl = "https://openrouter.example",
            auth = AuthConfig("api-key"),
        )
        val head = HeadConfig(
            provider = "openrouter",
            port = 4107,
            discoveryPrefix = "claude-router--",
            pinnedModel = "router-model",
        )
        val cfg = config.getConfig("not-grok")

        assertEquals(head, inputs.resolveHeadConfig(head, provider, cfg))
        assertEquals(provider, inputs.resolveProviderConfig(provider, cfg))
    }

    // DR-80 (assembly sweep): with two-plus heads of a legacy kind nothing is seeded into the
    // shared layer, so the unconditional legacy overwrite replaced each head's declared
    // port/model/base with the knob DEFAULTS (and with first-head-wins seeding, with the first
    // head's values). The resolve side now gates on sole-head-of-kind.
    @Test
    fun `a non-sole legacy head keeps its declared port, model and base - DR-80`() {
        val config = ConfigService(
            statePaths = StatePaths(baseOverride = Files.createTempDirectory("head-build-inputs")),
            headOverrides = emptyMap(), // what the shared layer holds when a kind has two heads
            envReader = { null },
        )
        val inputs = HeadBuildInputs(config, SignInPlanner())
        val provider = ProviderConfig(
            dialect = Dialect.OPENAI_RESPONSES,
            baseUrl = "https://second-codex.example",
            auth = AuthConfig("chatgpt-oauth"),
            models = listOf(splice.core.model.ModelEntry("m-b", contextWindow = 100_000)),
        )
        val head = HeadConfig("codex-b", 4202, "claude-two--", "m-b")

        val build = inputs.providerContext("two", head, provider, legacyKnobsGovern = false)

        assertEquals(head, build.head)
        assertEquals(provider, build.providerCfg)
    }
}
