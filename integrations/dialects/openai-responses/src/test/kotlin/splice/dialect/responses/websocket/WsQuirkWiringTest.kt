// The WebSocket transport's enable switch: off unless the operator turns it on, and never armed on a provider
// whose upstream was not probed for the protocol.
package splice.dialect.responses.websocket

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.dialect.responses.ReasoningSettings
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesProvider
import splice.dialect.responses.ResponsesQuirks
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import kotlin.time.Duration.Companion.seconds

private class ProbeProvider(quirks: ResponsesQuirks, private val supports: Boolean = true) : ResponsesProvider(
    tuning = ProviderTuning(
        name = ProviderName(key = "probe", label = "probe"),
        catalog = ModelCatalog(
            discoveryPrefix = "claude-codex",
            models = listOf(ModelEntry(id = "gpt-5.6-sol", label = "sol", contextWindow = 400_000)),
            defaultContextWindow = 400_000,
        ),
        pinnedModel = "gpt-5.6-sol",
        auth = StubAuth,
        locations = ProviderLocations(baseUrl = "https://chatgpt.com/backend-api/codex"),
        watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
    ),
    reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, null, null),
    quirks = quirks,
) {
    /** Stands in for a provider that has PROVEN the protocol against its own upstream (codex). */
    override val supportsWebSocket: Boolean get() = supports

    override fun extraHeaders(creds: Credentials): Map<String, String> = emptyMap()
}

private object StubAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok", "acct")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "stub")
}

class WsQuirkWiringTest {

    /** With the quirk absent no runner exists: the transport ships default off. */
    @Test
    fun `absent quirk means NO ws runner is constructed`() {
        assertNull(ProbeProvider(ResponsesQuirks(providerTag = "claudex", lite = ResponsesLiteQuirks())).wsRunner)
    }

    @Test
    fun `quirk on constructs a ws runner`() {
        val on = ResponsesQuirks(providerTag = "claudex", lite = ResponsesLiteQuirks()).withWebSocketToml(true)
        assertNotNull(
            ProbeProvider(on).wsRunner,
            "websocket = true must produce a runner",
        )
    }

    @Test
    fun `quirk explicitly false constructs no runner`() {
        val off = ResponsesQuirks(providerTag = "claudex", lite = ResponsesLiteQuirks()).withWebSocketToml(false)
        assertNull(ProbeProvider(off).wsRunner)
    }

    /** The quirk table is shared by every openai-responses provider, so `websocket = true` under
     *  [providers.xai.quirks] must not make grok open a WebSocket to api.x.ai. A provider that has not
     *  proven the protocol against its own upstream gets no runner, quirk or not. */
    @Test
    fun `a provider that does not support the protocol gets no runner even with the quirk on`() {
        val on = ResponsesQuirks(providerTag = "claude-grok", lite = ResponsesLiteQuirks()).withWebSocketToml(true)
        assertNull(
            ProbeProvider(on, supports = false).wsRunner,
            "the shared quirk must not arm the overlay on a provider whose upstream was never probed",
        )
    }
}
