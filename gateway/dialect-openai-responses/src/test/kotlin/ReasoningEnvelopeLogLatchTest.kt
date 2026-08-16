// NEW: CMP-002 (review 2026-08-15, follow-up to PT-001) — decodeReasoningEnvelope logged one
// daemon.log line per undecodable redacted_thinking block, no latch. appendRedactedThinking
// (ResponsesRequestBuilder) runs it per block in the client transcript on every non-compact
// replay-on turn, so a transcript carrying several foreign/expired envelopes logged one line per
// block, per turn, for the life of the session — transcript-length-proportional, synchronous,
// inside request building. ResponsesProvider.buildTurn now wraps decodeReasoningEnvelope with a
// per-build latch (same idiom as PassthroughStreamTranslator.unmappedIndexLogged): the anomaly is
// still visible, but at most once per build.
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.parse.parseAnthropicBody
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.dialect.responses.ResponsesProvider
import splice.dialect.responses.ResponsesQuirks
import splice.spi.ProviderTuning
import kotlin.time.Duration.Companion.seconds

private object LatchProbeAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok", "acct")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "stub")
}

/** Minimal concrete provider (mirrors WsQuirkWiringTest's ProbeProvider) with a capturing [log]
 *  sink and replay-reasoning ON, so appendRedactedThinking actually drives decodeReasoningEnvelope. */
private class LatchProbeProvider(logs: MutableList<String>) : ResponsesProvider(
    tuning = ProviderTuning(
        key = "probe",
        label = "probe",
        catalog = ModelCatalog(
            discoveryPrefix = "claude-codex",
            models = listOf(ModelEntry(id = "gpt-5.6-sol", label = "sol", contextWindow = 400_000)),
            defaultContextWindow = 400_000,
        ),
        pinnedModel = "gpt-5.6-sol",
        auth = LatchProbeAuth,
        baseUrl = "https://chatgpt.com/backend-api/codex",
        watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
    ),
    showReasoning = ReasoningDisplay.from("text"),
    replayReasoning = true,
    configEffort = null,
    configSummary = null,
    quirks = ResponsesQuirks(providerTag = "claudex"),
    log = { logs.add(it) },
) {
    override fun extraHeaders(creds: Credentials): Map<String, String> = emptyMap()
}

class ReasoningEnvelopeLogLatchTest {

    @Test
    fun `several undecodable redacted_thinking blocks log exactly once per build`() = runTest {
        val logs = mutableListOf<String>()
        val provider = LatchProbeProvider(logs)
        // Five foreign/garbled envelopes across two assistant turns in one transcript — none of
        // them base64-decode to the splice-reasoning v1 shape, so all five hit the drop path.
        val body = parseAnthropicBody(
            """{"model":"m","messages":[
                {"role":"assistant","content":[
                    {"type":"redacted_thinking","data":"not-a-real-envelope-1"},
                    {"type":"redacted_thinking","data":"not-a-real-envelope-2"},
                    {"type":"redacted_thinking","data":"not-a-real-envelope-3"}
                ]},
                {"role":"user","content":"go on"},
                {"role":"assistant","content":[
                    {"type":"redacted_thinking","data":"not-a-real-envelope-4"},
                    {"type":"redacted_thinking","data":"not-a-real-envelope-5"}
                ]}
            ]}""",
        )
        provider.buildTurn(body, compact = false, sessionId = null)
        assertTrue(
            logs.size == 1,
            "5 undecodable envelopes in one build must log once, not once per block: $logs",
        )
        assertTrue(logs.single().contains("dropped an unusable reasoning envelope"), logs.single())
    }
}
