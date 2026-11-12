package splice.app.provider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.dialect.responses.CacheKeyStrategy
import splice.dialect.responses.PromptCachePolicy
import splice.dialect.responses.ReasoningSettings
import splice.dialect.responses.ResponsesBackendQuirks
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesQuirks
import splice.provider.muse.MuseGatewayRoutes
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.ToolNameShortener
import kotlin.time.Duration.Companion.seconds

class MuseAgentRoutingTest {
    @Test
    fun `sessionless agents retain Muse upstream cache and routing affinity`() {
        val provider = provider()
        try {
            val body = AnthropicParse.parseAnthropicBody(
                """{"model":"muse-spark-1.3","messages":[{"role":"user","content":"same opening"}]}""",
            )
            val main = provider.buildTurn(body, compact = false, sessionId = null)
            val first = provider.buildTurn(body.copy(agentId = "agent-a"), compact = false, sessionId = null)
            val second = provider.buildTurn(body.copy(agentId = "agent-b"), compact = false, sessionId = null)
            val header = "x-meta-ai-gateway-session-id"
            val route = requireNotNull(main.extraHeaders[header])
            val cache = requireNotNull(main.requestBody["prompt_cache_key"])

            assertNotEquals(main.meta.scope.conversationKey, first.meta.scope.conversationKey)
            assertNotEquals(main.meta.scope.conversationKey, second.meta.scope.conversationKey)
            assertNotEquals(first.meta.scope.conversationKey, second.meta.scope.conversationKey)
            for (agent in listOf(first, second)) {
                assertEquals(route, agent.extraHeaders[header])
                assertEquals(cache, agent.requestBody["prompt_cache_key"])
                assertNull(agent.extraHeaders["session-id"])
                assertNull(agent.extraHeaders["thread-id"])
            }
        } finally {
            provider.onHeadStop()
        }
    }

    private fun provider(): MuseResponsesProvider = MuseResponsesProvider(
        tuning = ProviderTuning(
            name = ProviderName("muse", "Muse"),
            catalog = ModelCatalog(
                discoveryPrefix = "muse--",
                models = listOf(ModelEntry(id = "muse-spark-1.3", label = "Spark", contextWindow = 400_000)),
                defaultContextWindow = 400_000,
            ),
            pinnedModel = "muse-spark-1.3",
            auth = object : RefreshableAuthProvider {
                override suspend fun credentials(): Credentials = Credentials.Bearer("token", "acct")
                override suspend fun refresh(): Credentials = credentials()
                override suspend fun describe(): AuthDescription = AuthDescription(true, "fixture")
            },
            locations = ProviderLocations(baseUrl = "https://api.meta.ai/v1"),
            watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
        ),
        options = MuseResponsesOptions(
            reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, null, null),
            quirks = ResponsesQuirks(
                providerTag = "muse",
                lite = ResponsesLiteQuirks(),
                backend = ResponsesBackendQuirks(
                    promptCache = PromptCachePolicy(CacheKeyStrategy.SESSION_OR_FIRST_MESSAGE_HASH, "24h"),
                ),
            ),
            headers = emptyMap(),
            toolNames = ToolNameShortener(),
            routes = MuseGatewayRoutes(null, {}),
        ),
    )
}
