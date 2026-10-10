// NEW: (2026-09-02) the provider's re-anchor decision for a COMPACT turn. It was null for
// compaction on the claim that "the pre-handoff retry covers it", and the daemon log carried five
// compactions that ended "without response.completed" straight to the client as overloaded_error.
// Pinned through a concrete ResponsesProvider, the seam the gateway actually calls
// (TurnRoundRun), so a future "compact → null" cannot come back through any path this misses.
package splice.dialect.responses.reasoning

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnReasoning
import splice.core.turn.TurnRoute
import splice.core.turn.TurnScope
import splice.core.turn.WatchdogBudget
import splice.dialect.responses.ReasoningSettings
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesProvider
import splice.dialect.responses.ResponsesQuirks
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import kotlin.time.Duration.Companion.seconds

private class CompactProbeProvider : ResponsesProvider(
    tuning = ProviderTuning(
        name = ProviderName(key = "probe", label = "probe"),
        catalog = ModelCatalog(
            discoveryPrefix = "claude-codex",
            models = listOf(ModelEntry(id = "gpt-5.6-sol", label = "sol", contextWindow = 400_000)),
            defaultContextWindow = 400_000,
        ),
        pinnedModel = "gpt-5.6-sol",
        auth = CompactStubAuth,
        locations = ProviderLocations(baseUrl = "https://chatgpt.com/backend-api/codex"),
        watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
    ),
    reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, null, null),
    quirks = ResponsesQuirks(providerTag = "claudex", lite = ResponsesLiteQuirks()),
) {
    override fun extraHeaders(creds: Credentials): Map<String, String> = emptyMap()
}

private object CompactStubAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok", "acct")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "stub")
}

private fun meta(compact: Boolean): TurnMeta = TurnMeta(
    compact = compact,
    reasoning = TurnReasoning(
        showReasoning = ReasoningDisplay.TEXT,
        effort = "high",
        summary = "detailed",
        budgetTokens = 31999,
    ),
    route = TurnRoute(
        stream = true,
        originalModel = "claude-codex--gpt-5.6-sol",
        upstreamModel = "gpt-5.6-sol",
        clientMaxTokens = 8000,
    ),
    scope = TurnScope(conversationKey = "splice-testconvokey"),
)

class ResponsesCompactReanchorSeamTest {

    @Test
    fun `a compact turn is re-anchor eligible - the same policy every other round gets`() {
        val provider = CompactProbeProvider()
        val compact = provider.reanchorPolicy(meta(compact = true))
        assertNotNull(compact, "a torn compaction must restart in the proxy, not surface as overloaded_error")
        assertSame(provider.reanchorPolicy(meta(compact = false)), compact, "one policy, no compact special case")
    }
}
