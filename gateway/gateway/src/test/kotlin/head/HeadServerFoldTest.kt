// PORT-analog of HeadServerIntegrationTest for reasoning-continuation folding (codex 518n-2): a real
// HeadServer (CodexProvider with a fold config + mock ChatGPT upstream) driven over HTTP. Pins:
// fold-and-continue (a truncated round + a clean round fold into ONE downstream response, the
// truncated output discarded, usage summed, the continuation marker in the round-2 upstream body);
// the continuation cap (the head stops and emits the last round honestly); and passthrough parity
// (a non-fold model — sol — reporting the SAME 516 fingerprint does NOT continue).
// ADDED 2026-07-26: the turn-scoped summary-dedup WIRING — rounds share one SharedSummaryParts, so a
// section the continuation round re-titles reaches the client exactly once (review of #58).
package head

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.test.runTest
import mock.MockChatGptUpstream
import mock.SUMMARY_SECTION_A
import mock.SUMMARY_SECTION_B
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

private fun countOf(haystack: String, needle: String): Int =
    Regex(Regex.escape(needle)).findAll(haystack).count()

/** Occurrences of [needle] in the LIVE thinking stream only. This head runs ReasoningDisplay.TEXT,
 *  which additionally mirrors the final round's summary into a trailing text block — a separate
 *  (pre-existing) rendering concern, not the per-round dedup this pins. */
private fun thinkingCountOf(sse: String, needle: String): Int =
    sse.lineSequence().filter { it.contains("\"thinking_delta\"") }.count { it.contains(needle) }

private class FoldFakeAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-test", "acct-test")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeadServerFoldTest {

    private val mock = MockChatGptUpstream()
    private val client = HttpClient(CIO) {
        defaultRequest { bearerAuth("test-inference-token") }
    }
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
                inferenceToken = "test-inference-token",
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

    // ADDED 2026-07-26 (review of #58): SummaryTurnDedupTest pins the MECHANISM by handing one
    // SharedSummaryParts to two translators by hand; nothing pinned the WIRING that makes rounds
    // share it in production. A meta rebuilt per round — or an explicit copy(summaryParts = ...)
    // slipped into the fold loop — keeps every unit test green and brings the mirror duplication
    // straight back. This drives the real head: HeadServer -> TurnDriver -> per-round translator.
    @Test
    fun `turn-scoped summary dedup - a continuation round's re-titled section reaches the client once`() = runTest {
        val before = mock.upstreamBodies.size
        val sse = messages("foldsummary", "claude-codex--gpt-5.6-luna")
        assertEquals(2, mock.upstreamBodies.size - before, "expected one continuation POST")

        // round 2 restates section A verbatim (upstream sent it twice, downstream must show it once)
        assertEquals(
            1,
            thinkingCountOf(sse, SUMMARY_SECTION_A),
            "the continuation round's re-titled section must be suppressed turn-scoped: $sse",
        )
        assertEquals(1, countOf(sse, SUMMARY_SECTION_A), "the re-titled section leaked into the payload: $sse")
        // ...while the section only round 2 produced still lands — the summary stays complete
        assertEquals(1, thinkingCountOf(sse, SUMMARY_SECTION_B), "a genuinely-new section was suppressed: $sse")
        assertTrue(sse.contains("FINAL ANSWER"), sse)
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

    /** A dedicated head whose totalCap (1s) is far tighter than its idle budgets — the NF-03 rig. */
    private fun tightCapHead(gate: InflightGate, capPort: Int): HeadServer = HeadServer(
        provider = CodexProvider(
            tuning = ProviderTuning(
                key = "codex",
                label = "claudex",
                catalog = catalog,
                pinnedModel = "gpt-5.6-luna",
                auth = FoldFakeAuth(),
                baseUrl = mock.baseUrl,
                watchdog = WatchdogBudget(10.seconds, 10.seconds, 1.seconds),
                loginCommand = "claudex login",
            ),
            showReasoning = ReasoningDisplay.TEXT,
            replayReasoning = false,
            configEffort = "high",
            configSummary = "detailed",
        ),
        listenPort = capPort,
        deps = HeadDeps(
            upstream = UpstreamClient(firstByteTimeoutMs = 20_000, totalTimeoutMs = 20_000, maxRetries = 1),
            inferenceToken = "test-inference-token",
            gate = gate,
            shadow = ShadowClassifier(log = {}),
            compactStats = CompactStats(tmp.resolve("cap-compact.jsonl")),
            usageStore = UsageStore(tmp.resolve("cap-usage.json"), tmp.resolve("cap-ratelimit.json")),
            perfStats = PerfStats(tmp.resolve("cap-perf.jsonl")),
            log = {},
        ),
    )

    /** DR-7's acceptance rig: a SHORT streamIdle (1s) with generous firstByte and totalCap, so the
     *  only thing that can fire is the mid-stream idle watchdog. The fold head above cannot express
     *  this — its 3s idle is longer than the stall is useful for. */
    private fun stallHead(stallPort: Int): HeadServer = HeadServer(
        provider = CodexProvider(
            tuning = ProviderTuning(
                key = "codex",
                label = "claudex",
                catalog = catalog,
                pinnedModel = "gpt-5.6-luna",
                auth = FoldFakeAuth(),
                baseUrl = mock.baseUrl,
                watchdog = WatchdogBudget(20.seconds, 1.seconds, 60.seconds),
                loginCommand = "claudex login",
            ),
            showReasoning = ReasoningDisplay.TEXT,
            replayReasoning = false,
            configEffort = "high",
            configSummary = "detailed",
            foldConfig = FoldConfig(models = setOf("gpt-5.6-luna")),
        ),
        listenPort = stallPort,
        deps = HeadDeps(
            upstream = UpstreamClient(firstByteTimeoutMs = 20_000, totalTimeoutMs = 60_000, maxRetries = 2),
            inferenceToken = "test-inference-token",
            gate = InflightGate({ 0 }),
            shadow = ShadowClassifier(log = {}),
            compactStats = CompactStats(tmp.resolve("stall-compact.jsonl")),
            usageStore = UsageStore(tmp.resolve("stall-usage.json"), tmp.resolve("stall-ratelimit.json")),
            perfStats = PerfStats(tmp.resolve("stall-perf.jsonl")),
            log = {},
        ),
    )

    // DR-7, THE acceptance wall. A round that streams reasoning and then stalls mid-part used to
    // lose everything: the idle watchdog cancelled the whole turn subtree, ResponsesTerminalDecision
    // built its Failure with NO partial, and BOTH continuation gates (FoldRounds and
    // ReanchorContinuation) vetoed unconditionally on watchdogFired. The client got a stalled
    // terminal and the reasoning it had already been shown was orphaned.
    //
    // This drives the REAL production path — HeadServer over HTTP, two upstream POSTs, one client
    // stream — because the direct-controller version of this test is the fake that let the gap
    // survive: it proved the controller would continue if asked, while nothing ever asked it.
    @Test
    fun `a round stalled mid-reasoning salvages its partial and continues - DR-7`() = runTest {
        val stallPort = freshPort()
        val stallServer = stallHead(stallPort)
        stallServer.start()
        awaitListening(stallPort)
        try {
            val before = mock.upstreamBodies.size
            val sse = client.post("http://127.0.0.1:$stallPort/v1/messages") {
                header("Content-Type", "application/json")
                setBody(
                    """{"model":"claude-codex--gpt-5.6-luna","stream":true,"max_tokens":8000,
                        "system":"You are a test. SCENARIO:foldstall",
                        "messages":[{"role":"user","content":"go"}]}""",
                )
            }.bodyAsText()
            val rounds = mock.upstreamBodies.drop(before)

            assertEquals(2, rounds.size, "the stalled round must be continued, not abandoned: $sse")
            assertTrue(
                rounds[1].second.contains("ENC-STALL"),
                "round 2 must replay the stalled round's reasoning: ${rounds[1].second}",
            )
            // the reasoning the client was already shown stays shown, and round 2's answer lands
            assertTrue(sse.contains("Thinking round one."), "the salvaged reasoning must survive: $sse")
            assertTrue(sse.contains("FINAL ANSWER"), "the continuation's answer must reach the client: $sse")
            assertFalse(sse.contains("NEVER REACHES THE CLIENT"), "post-stall bytes must not appear: $sse")
            // ONE honest terminal, and it is a clean end — not a stalled/cancelled one (L3)
            assertEquals(1, Regex("event: message_stop").findAll(sse).count(), "exactly one terminal: $sse")
            assertTrue(sse.contains("\"stop_reason\":\"end_turn\""), "expected a clean end_turn: $sse")
            assertFalse(sse.contains("stalled ("), "no stall error may reach the client: $sse")
        } finally {
            stallServer.stop()
        }
    }

    @Test
    fun `totalCap reaps a turn stalled BEFORE upstream headers and frees the slot - NF-03`() = runTest {
        // The window launchIn never covered: the mock sleeps 3s before sending response headers,
        // no stream ever opens, and this head's totalCap is 1s. Pre-fix, nothing sampled the cap
        // here — the turn ran the full stall while pinning its gate slot. The verify-spec sketched
        // a rounds-sum-past-cap fold instead, but round N's stream-scoped poller already samples
        // whole-turn elapsed mid-stream, so that case was green BEFORE the fix; this one is the
        // honest red→green.
        val gate = InflightGate(maxInflight = { 1 }, maxQueued = { 0 })
        val capPort = freshPort()
        val capHead = tightCapHead(gate, capPort)
        capHead.start()
        awaitListening(capPort)
        try {
            val t0 = System.currentTimeMillis()
            val sse = client.post("http://127.0.0.1:$capPort/v1/messages") {
                header("Content-Type", "application/json")
                setBody(
                    """{"model":"claude-codex--gpt-5.6-luna","stream":true,"max_tokens":64,
                        "system":"You are a test. SCENARIO:stall",
                        "messages":[{"role":"user","content":"go"}]}""",
                )
            }.bodyAsText()
            val tookMs = System.currentTimeMillis() - t0

            assertTrue(sse.contains("\"type\":\"error\""), "expected an honest error terminal: $sse")
            assertTrue(sse.contains("stalled (watchdog)"), "expected the watchdog-named reason: $sse")
            assertTrue(tookMs < 2_500, "reaped by the 1s cap, not the 3s stall (took ${tookMs}ms)")
            // the slot must come back within ~one poll interval, not ride the stall
            val deadline = System.currentTimeMillis() + 2_000
            while (System.currentTimeMillis() < deadline && gate.snapshot().inflight != 0) {
                Thread.sleep(50)
            }
            assertEquals(0, gate.snapshot().inflight, "the reaped turn must release its gate slot")
        } finally {
            capHead.stop()
        }
    }
}
