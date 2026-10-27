package splice.provider.openai

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.dialect.responses.ReasoningSettings
import splice.dialect.responses.ResponsesBackendQuirks
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import kotlin.time.Duration.Companion.seconds

class OpenAiResponsesWebSocketCapabilityTest {
    @Test
    fun `public OpenAI Responses transport is available when opted in`() {
        val quirks = OpenAiQuirks().defaultQuirks().copy(
            backend = ResponsesBackendQuirks(webSocket = true),
        )

        assertNotNull(provider(quirks).wsRunner)
    }

    @Test
    fun `public OpenAI Responses transport remains opt in by default`() {
        assertNull(provider(OpenAiQuirks().defaultQuirks()).wsRunner)
    }

    private fun provider(quirks: splice.dialect.responses.ResponsesQuirks) = OpenAiResponsesProvider(
        tuning = ProviderTuning(
            name = ProviderName(key = "openai", label = "OpenAI"),
            catalog = ModelCatalog(
                discoveryPrefix = "claude-openai--",
                models = listOf(ModelEntry("gpt-5-pro", "GPT-5 Pro", contextWindow = 400_000)),
                defaultContextWindow = 400_000,
            ),
            pinnedModel = "gpt-5-pro",
            auth = ApiKeyAuthProvider("OPENAI_API_KEY", envReader = { "test-key" }),
            locations = ProviderLocations(baseUrl = "https://api.openai.com/v1"),
            watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
        ),
        reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
        quirks = quirks,
    )
}
