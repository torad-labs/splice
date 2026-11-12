// NEW: failure-handling slice 3 (2026-10-26). A failure claudex's backend sends inside its stream is read once, by
// claudex's own reader (CodexReplies before the Responses standard), as the same error over HTTP is.
package splice.provider.codex

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.failure.FailureCause
import splice.core.index.WireBlockIndex
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnOutcome
import splice.core.turn.WatchdogBudget
import splice.dialect.responses.ReasoningSettings
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.TurnSignals
import splice.upstream.sse.WireSink
import kotlin.time.Duration.Companion.seconds

class CodexInBandFailureTest {
    // CodexReplies: the backend's own websocket capacity, which its error type spells as a rate limit.
    @Test
    fun `codex's websocket connection limit inside its stream is an overload the turn waits out`() = runTest {
        val failure = failedRound(
            """{"type":"response.failed","response":{"error":{"type":"rate_limit_error",""" +
                """"code":"websocket_connection_limit_reached","message":"Too many open connections."}}}""",
        )
        assertInstanceOf(FailureCause.Provider.Overloaded::class.java, failure.cause, "${failure.cause}")
    }

    /** One claudex round whose stream carries only [event], as its translator ends it. */
    private suspend fun failedRound(event: String): TurnOutcome.Failure {
        val codex = CodexProvider(
            tuning = ProviderTuning(
                name = ProviderName(key = "codex", label = "claudex"),
                catalog = ModelCatalog(
                    discoveryPrefix = "claude-codex--",
                    models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000L)),
                    defaultContextWindow = 272_000L,
                ),
                pinnedModel = "gpt-5.6-sol",
                auth = BearerAuth,
                locations = ProviderLocations(baseUrl = "https://x"),
                watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
            ),
            reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
            quirks = CodexQuirks().defaultQuirks(),
            accountIdHeader = false,
        )
        val built = codex.buildTurn(
            AnthropicParse.parseAnthropicBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,""" +
                    """"messages":[{"role":"user","content":"hi"}]}""",
            ),
            compact = false,
            sessionId = "in-band",
        )
        val outcome = codex.streamTranslator(built.meta, TurnSignals(clientGone = { false }, watchdogFired = { null }))
            .driveTurn(listOf(Json.parseToJsonElement(event).jsonObject).asFlow(), QuietSink())
        return assertInstanceOf(TurnOutcome.Failure::class.java, outcome)
    }

    private object BearerAuth : RefreshableAuthProvider {
        override suspend fun credentials() = Credentials.Bearer("tok")
        override suspend fun refresh() = credentials()
        override suspend fun describe() = AuthDescription(true, "chatgpt-oauth", emptyMap())
    }

    private class QuietSink : WireSink {
        private var next = 0
        override suspend fun openText(): WireBlockIndex = WireBlockIndex(next++)
        override suspend fun openThinking(): WireBlockIndex = WireBlockIndex(next++)
        override suspend fun openTool(id: String, name: String): WireBlockIndex = WireBlockIndex(next++)
        override suspend fun textDelta(index: WireBlockIndex, text: String) = Unit
        override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) = Unit
        override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) = Unit
        override suspend fun closeBlock(index: WireBlockIndex) = Unit
        override suspend fun closeAll() = Unit
        override suspend fun addTextBlock(text: String) = Unit
        override suspend fun addRedactedThinking(data: String) = Unit
    }
}
