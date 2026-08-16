// WALLS for the WS overlay's enable switch (ws-transport WS-3/WS-4).
//
// WHY THESE EXIST: an adversarial reviewer inverted the feature's entire production on/off
// condition (`webSocket == true` -> `webSocket == false`) and the WHOLE GATE STAYED GREEN — 828
// tests, 0 failures. A switch nothing can falsify is worse than no switch: it reads as coverage
// forever while the feature is either permanently inert or permanently on. These tests fail on
// that mutation in both directions.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplayParser
import splice.core.turn.WatchdogBudget
import splice.dialect.responses.ResponsesProvider
import splice.dialect.responses.ResponsesQuirks
import splice.spi.ProviderTuning
import kotlin.time.Duration.Companion.seconds

private class ProbeProvider(quirks: ResponsesQuirks, private val supports: Boolean = true) : ResponsesProvider(
    tuning = ProviderTuning(
        key = "probe",
        label = "probe",
        catalog = ModelCatalog(
            discoveryPrefix = "claude-codex",
            models = listOf(ModelEntry(id = "gpt-5.6-sol", label = "sol", contextWindow = 400_000)),
            defaultContextWindow = 400_000,
        ),
        pinnedModel = "gpt-5.6-sol",
        auth = StubAuth,
        baseUrl = "https://chatgpt.com/backend-api/codex",
        watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
    ),
    showReasoning = ReasoningDisplayParser.from("text"),
    replayReasoning = false,
    configEffort = null,
    configSummary = null,
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

    /** THE DEFAULT-OFF PIN. With the quirk absent no runner exists, so not one line of the overlay
     *  can execute and the request path is byte-identical to before it landed. */
    @Test
    fun `absent quirk means NO ws runner is constructed`() {
        assertNull(ProbeProvider(ResponsesQuirks(providerTag = "claudex")).wsRunner)
    }

    /** THE MUTATION WALL, direction 1: with the quirk ON a runner MUST exist. Inverting the
     *  production condition makes this fail — previously nothing did. */
    @Test
    fun `quirk on constructs a ws runner`() {
        val on = ResponsesQuirks(providerTag = "claudex").withWebSocketToml(true)
        assertNotNull(
            ProbeProvider(on).wsRunner,
            "websocket = true must produce a runner; inverting the production condition leaves the " +
                "feature permanently INERT with every gate green",
        )
    }

    /** THE MUTATION WALL, direction 2: explicit false must NOT construct one. Without this, a
     *  condition inverted the other way turns the overlay permanently ON for operators who
     *  deliberately disabled it. */
    @Test
    fun `quirk explicitly false constructs no runner`() {
        val off = ResponsesQuirks(providerTag = "claudex").withWebSocketToml(false)
        assertNull(ProbeProvider(off).wsRunner)
    }

    /** THE PROVIDER-GATING WALL (review of #72). The quirk table is SHARED by every
     *  openai-responses provider, so `websocket = true` under [providers.xai.quirks] would
     *  otherwise make grok open a WebSocket to api.x.ai and fail every round into SSE. A provider
     *  that has not proven the protocol against its own upstream gets no runner, quirk or not. */
    @Test
    fun `a provider that does not support the protocol gets no runner even with the quirk on`() {
        val on = ResponsesQuirks(providerTag = "claude-grok").withWebSocketToml(true)
        assertNull(
            ProbeProvider(on, supports = false).wsRunner,
            "the shared quirk must not arm the overlay on a provider whose upstream was never probed",
        )
    }

    /** The nullable-overlay contract: ABSENT keeps the provider default, it does not stomp it.
     *  A non-nullable TOML field is how supportsSummary became an unreachable dead lever. */
    @Test
    fun `absent TOML keeps the provider default, and never stomps an on default`() {
        val base = ResponsesQuirks(providerTag = "claudex")
        assertEquals(false, base.withWebSocketToml(null).webSocket, "absent keeps the shipped default")
        val providerDefaultOn = base.copy(webSocket = true)
        assertEquals(
            true,
            providerDefaultOn.withWebSocketToml(null).webSocket,
            "an absent key must not stomp a provider that defaults ON",
        )
        assertEquals(false, providerDefaultOn.withWebSocketToml(false).webSocket, "explicit false still wins")
    }
}
