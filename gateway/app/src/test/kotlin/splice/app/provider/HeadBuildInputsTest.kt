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
}
