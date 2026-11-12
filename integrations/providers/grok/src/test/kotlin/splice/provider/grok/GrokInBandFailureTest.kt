// NEW: failure-handling slice 3 (2026-10-26). A failure xAI sends inside its stream is read once, by grok's own reader
// (GrokReplies before the Responses standard): a code another vendor documents means nothing of its own here.
package splice.provider.grok

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

class GrokInBandFailureTest {
    // cyber_policy is the ChatGPT backend's Trusted-Access refusal (CodexReplies). On grok no table names it, so it
    // reads as an error no reader recognizes, never as a policy refusal borrowed from another vendor's module.
    @Test
    fun `a code only the ChatGPT backend documents is an unrecognized error inside grok's stream`() = runTest {
        val failure = failedRound(
            """{"type":"response.failed","response":{"error":{"code":"cyber_policy",""" +
                """"message":"This request was refused."}}}""",
        )
        assertInstanceOf(FailureCause.Provider.Unrecognized::class.java, failure.cause, "${failure.cause}")
    }

    /** One grok round whose stream carries only [event], as its translator ends it. */
    private suspend fun failedRound(event: String): TurnOutcome.Failure {
        val grok = GrokProvider(
            tuning = ProviderTuning(
                name = ProviderName(key = "grok", label = "grok"),
                catalog = ModelCatalog(
                    discoveryPrefix = "claude-grok--",
                    models = listOf(ModelEntry("grok-4.5", "Grok 4.5", contextWindow = 1_000_000)),
                    defaultContextWindow = 1_000_000,
                ),
                pinnedModel = "grok-4.5",
                auth = BearerAuth,
                locations = ProviderLocations(baseUrl = "https://x"),
                watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
            ),
            reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", null),
        )
        val built = grok.buildTurn(
            AnthropicParse.parseAnthropicBody(
                """{"model":"claude-grok--grok-4.5","stream":true,"max_tokens":64,""" +
                    """"messages":[{"role":"user","content":"hi"}]}""",
            ),
            compact = false,
            sessionId = "in-band",
        )
        val outcome = grok.streamTranslator(built.meta, TurnSignals(clientGone = { false }, watchdogFired = { null }))
            .driveTurn(listOf(Json.parseToJsonElement(event).jsonObject).asFlow(), QuietSink())
        return assertInstanceOf(TurnOutcome.Failure::class.java, outcome)
    }

    private object BearerAuth : RefreshableAuthProvider {
        override suspend fun credentials() = Credentials.Bearer("tok")
        override suspend fun refresh() = credentials()
        override suspend fun describe() = AuthDescription(true, "xai-oauth", emptyMap())
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
