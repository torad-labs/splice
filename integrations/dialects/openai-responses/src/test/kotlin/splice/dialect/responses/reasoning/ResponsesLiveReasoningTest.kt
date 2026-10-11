// showReasoning, replayReasoning and summary are live: a head already running builds its next turn from the operator's
// current values.
package splice.dialect.responses.reasoning

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.dialect.responses.LiveReasoning
import splice.dialect.responses.ReasoningSettings
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesProvider
import splice.dialect.responses.ResponsesQuirks
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import kotlin.time.Duration.Companion.seconds

private object LiveReasoningAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok", "acct")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "stub")
}

private class LiveReasoningProvider(reasoning: ReasoningSettings) : ResponsesProvider(
    tuning = ProviderTuning(
        name = ProviderName(key = "probe", label = "probe"),
        catalog = ModelCatalog(
            discoveryPrefix = "claude-codex",
            models = listOf(ModelEntry(id = "gpt-5.6-sol", label = "sol", contextWindow = 400_000)),
            defaultContextWindow = 400_000,
        ),
        pinnedModel = "gpt-5.6-sol",
        auth = LiveReasoningAuth,
        locations = ProviderLocations(baseUrl = "https://chatgpt.com/backend-api/codex"),
        watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
    ),
    reasoning = reasoning,
    quirks = ResponsesQuirks(providerTag = "claudex", lite = ResponsesLiteQuirks()),
) {
    override fun extraHeaders(creds: Credentials): Map<String, String> = emptyMap()
}

class ResponsesLiveReasoningTest {

    private val body = AnthropicParse.parseAnthropicBody(
        """{"model":"m","messages":[{"role":"user","content":"hi"}]}""",
    )

    private fun JsonObject.asksForHandle(): Boolean =
        (this["include"] as? JsonArray)?.contains(JsonPrimitive("reasoning.encrypted_content")) == true

    @Test
    fun `the next turn is built from the display and replay the operator has now`() = runTest {
        var now = ReasoningSettings(ReasoningDisplay.OFF, false, "low", "detailed")
        val started = ReasoningSettings(ReasoningDisplay.OFF, false, "high", "detailed", live = LiveReasoning { now })
        val provider = LiveReasoningProvider(started)

        val hidden = provider.buildTurn(body, compact = false, sessionId = null).requestBody
        assertTrue(!hidden.asksForHandle(), "reasoning is off, so no encrypted handle is asked for")
        assertEquals(ReasoningDisplay.OFF, provider.showReasoning)

        now = ReasoningSettings(ReasoningDisplay.TEXT, true, "low", "concise")
        val shown = provider.buildTurn(body, compact = false, sessionId = null)
        assertTrue(shown.requestBody.asksForHandle(), "shown reasoning asks for the encrypted handle")
        assertEquals(ReasoningDisplay.TEXT, shown.meta.reasoning.showReasoning)
        assertEquals(ReasoningDisplay.TEXT, provider.showReasoning)
        assertTrue(provider.replayReasoning)
    }
}
