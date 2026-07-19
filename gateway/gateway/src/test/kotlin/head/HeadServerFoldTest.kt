// PORT-analog of HeadServerIntegrationTest for reasoning-continuation folding (codex 518n-2): a real
// HeadServer (CodexProvider with a fold config + mock ChatGPT upstream) driven over HTTP. Pins:
// fold-and-continue (a truncated round + a clean round fold into ONE downstream response, the
// truncated output discarded, usage summed, the continuation marker in the round-2 upstream body);
// the continuation cap (the head stops and emits the last round honestly); and passthrough parity
// (a non-fold model — sol — reporting the SAME 516 fingerprint does NOT continue).
package head

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.test.runTest
import mock.MockChatGptUpstream
import mock.awaitListening
import mock.freshPort
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.dialect.responses.FoldConfig
import splice.gateway.compact.CompactStats
import splice.gateway.compact.ShadowClassifier
import splice.gateway.head.HeadDeps
import splice.gateway.head.HeadServer
import splice.gateway.perf.PerfStats
import splice.gateway.usage.UsageStore
import splice.provider.codex.CodexProvider
import splice.spi.InflightGate
import splice.spi.ProviderTuning
import splice.spi.UpstreamClient
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

private class FoldFakeAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-test", "acct-test")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeadServerFoldTest {

    private val mock = MockChatGptUpstream()
    private val client = HttpClient(CIO)
    private val port = freshPort()
    private lateinit var head: HeadServer
    private lateinit var tmp: java.nio.file.Path

    private val catalog = ModelCatalog(
        discoveryPrefix = "claude-codex--",
        models = listOf(
            ModelEntry("gpt-5.6-luna", "Luna", contextWindow = 272_000),
            ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000),
        ),
        defaultContextWindow = 272_000,
    )

    @BeforeAll
    fun setUp() = runTest {
        tmp = Files.createTempDirectory("head-fold")
        val provider = CodexProvider(
            tuning = ProviderTuning(
                key = "codex",
                label = "claudex",
                catalog = catalog,
                pinnedModel = "gpt-5.6-luna",
                auth = FoldFakeAuth(),
                baseUrl = mock.baseUrl,
                watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
                loginCommand = "claudex login",
            ),
            showReasoning = ReasoningDisplay.TEXT,
            replayReasoning = false,
            configEffort = "high",
            configSummary = "detailed",
            // luna folds; sol is deliberately NOT in the set (passthrough parity).
            foldConfig = FoldConfig(models = setOf("gpt-5.6-luna")),
        )
        head = HeadServer(
            provider = provider,
            listenPort = port,
            deps = HeadDeps(
                upstream = UpstreamClient(firstByteTimeoutMs = 5_000, totalTimeoutMs = 30_000, maxRetries = 2),
                gate = InflightGate({ 0 }),
                shadow = ShadowClassifier(log = {}),
                compactStats = CompactStats(tmp.resolve("compact.jsonl")),
                usageStore = UsageStore(tmp.resolve("usage.json"), tmp.resolve("ratelimit.json")),
                perfStats = PerfStats(tmp.resolve("perf.jsonl")),
                log = {},
            ),
        )
        head.start()
        awaitListening(port)
    }

    @AfterAll
    fun tearDown() = runTest {
        head.stop()
        client.close()
        mock.stop()
    }

    private suspend fun messages(scenario: String, model: String): String =
        client.post("http://127.0.0.1:$port/v1/messages") {
            header("Content-Type", "application/json")
            setBody(
                """{"model":"$model","stream":true,"max_tokens":8000,
                    "system":"You are a test. SCENARIO:$scenario",
                    "messages":[{"role":"user","content":"go"}]}""",
            )
        }.bodyAsText()

    @Test
    fun `fold-and-continue - a truncated round then a clean round fold into ONE response`() = runTest {
        val before = mock.upstreamBodies.size
        val sse = messages("fold", "claude-codex--gpt-5.6-luna")
        val rounds = mock.upstreamBodies.drop(before)

        // exactly two upstream POSTs: the truncated round, then the continuation
        assertEquals(2, rounds.size, "expected one continuation POST")
        // the round-2 request replays the round-1 reasoning AND carries the continuation marker
        val roundTwo = rounds[1].second
        assertTrue(roundTwo.contains("Continue thinking..."), "marker missing from round-2 input: $roundTwo")
        assertTrue(roundTwo.contains("ENC-TRUNC"), "round-1 encrypted reasoning not replayed: $roundTwo")

        // downstream: the clean round's answer, NOT the discarded tentative output
        assertTrue(sse.contains("FINAL ANSWER"), sse)
        assertFalse(sse.contains("TENTATIVE ANSWER"), "the truncated round's output must be discarded")
        // reasoning from BOTH rounds streamed live (thinking stays visible across the fold)
        assertTrue(sse.contains("Thinking round one."), "round-1 reasoning must stream live")
        assertTrue(sse.contains("Thinking round two."), "round-2 reasoning must stream live")
        // exactly ONE honest terminal (L3)
        assertEquals(1, Regex("event: message_stop").findAll(sse).count(), "exactly one terminal")
        assertTrue(sse.contains("\"stop_reason\":\"end_turn\""))
        // usage summed across rounds (round1 out=600 + round2 out=800 = 1400), not a single round
        assertTrue(sse.contains("\"output_tokens\":1400"), "usage should sum across rounds: $sse")
    }

    @Test
    fun `continuation cap - the head stops and emits the last round honestly`() = runTest {
        val before = mock.upstreamBodies.size
        val sse = messages("foldcap", "claude-codex--gpt-5.6-luna")
        val rounds = mock.upstreamBodies.drop(before)

        // initial round + fold_max_continue (default 3) continuations = 4 POSTs, then stop
        assertEquals(4, rounds.size, "expected 1 initial + 3 continuation POSTs")
        // the last (still-truncated) round is emitted honestly rather than looped forever
        assertTrue(sse.contains("TENTATIVE ANSWER"), sse)
        assertEquals(1, Regex("event: message_stop").findAll(sse).count(), "exactly one terminal")
        assertTrue(sse.contains("\"stop_reason\":\"end_turn\""))
    }

    @Test
    fun `passthrough parity - a non-fold model reporting 516 does NOT continue`() = runTest {
        val before = mock.upstreamBodies.size
        val sse = messages("fold", "claude-codex--gpt-5.6-sol")
        val rounds = mock.upstreamBodies.drop(before)

        // sol is not fold-eligible: exactly one POST, no continuation marker ever sent
        assertEquals(1, rounds.size, "sol must not continue")
        assertFalse(rounds[0].second.contains("Continue thinking..."))
        // the (truncated-fingerprint) round is emitted AS-IS — pure passthrough
        assertTrue(sse.contains("TENTATIVE ANSWER"), sse)
        assertEquals(1, Regex("event: message_stop").findAll(sse).count())
        assertTrue(sse.contains("\"stop_reason\":\"end_turn\""))
    }
}
