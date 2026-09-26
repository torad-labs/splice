// NEW: four cells for api-key responses GrokProvider vs OpenAiResponsesProvider (V4-21 D3).
// Registry ids xai and grok, plus the dated session-id cache_key compatibility arm.
package splice.app.provider

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.QuirksConfig
import splice.core.turn.WatchdogBudget
import splice.provider.grok.GrokProvider
import splice.provider.openai.OpenAiResponsesProvider
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class ApiKeyResponsesArmTest {

    @Test
    fun `xai plus default cache key selects GrokProvider`(@TempDir tmp: Path) {
        assertTrue(wired(tmp, "xai", "first-message-hash") is GrokProvider)
    }

    @Test
    fun `xai plus session-id selects GrokProvider`(@TempDir tmp: Path) {
        assertTrue(wired(tmp, "xai", "session-id") is GrokProvider)
    }

    @Test
    fun `another name plus session-id selects GrokProvider`(@TempDir tmp: Path) {
        assertTrue(wired(tmp, "openai", "session-id") is GrokProvider)
    }

    @Test
    fun `another name plus default cache key selects OpenAiResponsesProvider`(@TempDir tmp: Path) {
        assertTrue(wired(tmp, "openai", "first-message-hash") is OpenAiResponsesProvider)
    }

    @Test
    fun `grok alias plus default cache key selects GrokProvider`(@TempDir tmp: Path) {
        assertTrue(wired(tmp, "grok", "first-message-hash") is GrokProvider)
    }

    private fun wired(tmp: Path, providerId: String, cacheKey: String) =
        ApiKeyResponsesArm().apiKeyResponsesProvider(ctx(tmp, providerId, cacheKey), "label").provider

    private fun ctx(tmp: Path, providerId: String, cacheKey: String): ProviderBuild {
        val key = "head"
        val state = StatePaths(baseOverride = tmp.resolve("state"))
        Files.createDirectories(state.stateDir)
        val config = ConfigService(state, envReader = { null })
        return ProviderBuild(
            key = key,
            head = HeadConfig(
                provider = providerId,
                port = 3102,
                discoveryPrefix = "claude-$providerId--",
                pinnedModel = "m",
            ),
            providerCfg = ProviderConfig(
                dialect = Dialect.OPENAI_RESPONSES,
                baseUrl = "https://example.test/v1",
                auth = AuthConfig(kind = "api-key"),
                quirks = QuirksConfig(cacheKey = cacheKey),
            ),
            catalog = ModelCatalog(
                discoveryPrefix = "claude-$providerId--",
                models = listOf(ModelEntry(id = "m", contextWindow = 128_000)),
                defaultContextWindow = 128_000,
            ),
            watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
            cfg = config.getConfig(key),
            loginCommand = "login",
        )
    }
}
