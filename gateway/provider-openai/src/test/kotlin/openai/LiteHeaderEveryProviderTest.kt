// NEW: the lite header rides every responses provider gated on quirks.responsesLiteModelRegex,
// including OpenAiResponsesProvider with OpenAiQuirks (V4-20 F3). Lives here, not in the dialect:
// a dialect must not depend on a concrete provider (HD-11).
package openai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplayParser
import splice.core.turn.WatchdogBudget
import splice.dialect.responses.DefaultEffortVocabulary
import splice.dialect.responses.ResponsesQuirks
import splice.provider.openai.OpenAiQuirks
import splice.provider.openai.OpenAiResponsesProvider
import splice.spi.ProviderTuning
import kotlin.time.Duration.Companion.seconds

class LiteHeaderEveryProviderTest {

    @Test
    fun `OpenAiQuirks uses the dialect DefaultEffortVocabulary`() {
        assertTrue(OpenAiQuirks().defaultQuirks().effortVocabulary is DefaultEffortVocabulary)
    }

    @Test
    fun `a gpt-6 turn on OpenAiResponsesProvider sends the lite header`() {
        val built = provider().buildTurn(body("gpt-6"), compact = false, sessionId = "s")
        assertEquals("true", built.extraHeaders["x-openai-internal-codex-responses-lite"])
    }

    @Test
    fun `a non-lite model omits the header`() {
        val built = provider().buildTurn(body("gpt-4o"), compact = false, sessionId = "s")
        assertNull(built.extraHeaders["x-openai-internal-codex-responses-lite"])
    }

    @Test
    fun `nulling the quirks lite regex drops the header on gpt-6`() {
        val built = provider(OpenAiQuirks().defaultQuirks().copy(responsesLiteModelRegex = null))
            .buildTurn(body("gpt-6"), compact = false, sessionId = "s")
        assertNull(built.extraHeaders["x-openai-internal-codex-responses-lite"])
    }

    private fun provider(
        quirks: ResponsesQuirks = OpenAiQuirks().defaultQuirks(),
    ) = OpenAiResponsesProvider(
        tuning = ProviderTuning(
            key = "openai",
            label = "openai",
            catalog = ModelCatalog(
                discoveryPrefix = "claude-openai--",
                models = listOf(ModelEntry(id = "gpt-6", contextWindow = 1_000_000)),
                defaultContextWindow = 1_000_000,
            ),
            pinnedModel = "gpt-6",
            auth = LiteAuth,
            baseUrl = "https://api.openai.com/v1",
            watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
        ),
        showReasoning = ReasoningDisplayParser.from("text"),
        replayReasoning = false,
        configEffort = null,
        configSummary = null,
        quirks = quirks,
    )

    private fun body(model: String) = AnthropicParse.parseAnthropicBody(
        """{"model":"$model","stream":true,"max_tokens":16,"messages":[{"role":"user","content":"Hi"}]}""",
    )
}

private object LiteAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok", null)
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "stub")
}
