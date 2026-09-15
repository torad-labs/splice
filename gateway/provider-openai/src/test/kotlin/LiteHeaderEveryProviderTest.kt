// NEW: V4-28 — the lite header is a declared pair, not a dialect default.
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
    fun `a codex-shaped quirk pair on gpt-6 still emits the lite header`() {
        val built = provider(
            ResponsesQuirks(
                providerTag = "claudex",
                responsesLiteModelRegex = Regex("gpt-5\\.6|gpt-6", RegexOption.IGNORE_CASE),
                responsesLiteHeader = "x-openai-internal-codex-responses-lite",
            ),
        ).buildTurn(body("gpt-6"), compact = false, sessionId = "s")
        assertEquals("true", built.extraHeaders["x-openai-internal-codex-responses-lite"])
    }

    @Test
    fun `an api-key openai head pinned to openai slash gpt-6 emits nothing`() {
        val built = provider(OpenAiQuirks().defaultQuirks())
            .buildTurn(body("openai/gpt-6"), compact = false, sessionId = "s")
        assertNull(built.extraHeaders["x-openai-internal-codex-responses-lite"])
    }

    @Test
    fun `a grok-shaped quirk profile does not emit the lite header`() {
        val built = provider(ResponsesQuirks(providerTag = "claude-grok"))
            .buildTurn(body("grok-4.6"), compact = false, sessionId = "s")
        assertNull(built.extraHeaders["x-openai-internal-codex-responses-lite"])
    }

    @Test
    fun `OpenAiQuirks default carries no lite regex or header`() {
        val quirks = OpenAiQuirks().defaultQuirks()
        assertNull(quirks.responsesLiteModelRegex)
        assertNull(quirks.responsesLiteHeader)
    }

    @Test
    fun `OpenAiQuirks uses the dialect DefaultEffortVocabulary`() {
        assertTrue(OpenAiQuirks().defaultQuirks().effortVocabulary is DefaultEffortVocabulary)
    }

    @Test
    fun `a lite regex without a header name emits no lite header`() {
        val built = provider(
            ResponsesQuirks(
                providerTag = "claudex",
                responsesLiteModelRegex = Regex("gpt-5\\.6|gpt-6", RegexOption.IGNORE_CASE),
            ),
        ).buildTurn(body("gpt-6"), compact = false, sessionId = "s")
        assertNull(built.extraHeaders["x-openai-internal-codex-responses-lite"])
    }

    private fun provider(quirks: ResponsesQuirks) = OpenAiResponsesProvider(
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
            baseUrl = "https://example.invalid/v1",
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
