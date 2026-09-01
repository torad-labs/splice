// The compact-turn watchdog budget, driven through the REAL production path — HeadServer over HTTP,
// TurnDriveFactory wiring the budget, the idlepre mock acknowledging and then going silent — because
// a direct test of WatchdogBudget.forCompact proves the arithmetic while nothing proves the factory
// ever calls it (the shape of gap DR-7's acceptance wall exists to close).
//
// One head PER ARM, same 1s first-output / 20s streamIdle tiers, different whole-turn wall. A NORMAL
// turn that goes silent after the handshake is reaped by the 1s first-output cap and says so; its
// wall is far away (30s) because the turn spans two upstream attempts (the WS round, then the SSE
// re-serve) and sharing the compact arm's 4s wall let a slow CI runner reach the wall first (coverage
// run 33549293551 failed this arm at the first-output assertion; locally it took 3.7s). A COMPACT
// turn's first-output cap IS the total cap, so the only thing that can end it is its 4s whole-turn
// wall — which surfaces as the cancellation seal's generic watchdog wording. Live provenance
// (2026-09-01 14:07): the first compaction on the corrected tier died at "no first output within
// the 300s first-output cap".
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
import mock.awaitListening
import mock.freshPort
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
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

private class CapFakeAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-cap", "acct-cap")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeadServerCompactCapTest {

    private val mock = MockChatGptUpstream()
    private val client = HttpClient(CIO) {
        defaultRequest { bearerAuth("test-inference-token") }
    }

    @AfterAll
    fun tearDown() {
        client.close()
        mock.stop()
    }

    private fun head(port: Int, watchdog: WatchdogBudget): HeadServer {
        val tmp = Files.createTempDirectory("head-compact-cap")
        return HeadServer(
            provider = CodexProvider(
                tuning = ProviderTuning(
                    key = "codex",
                    label = "claudex",
                    catalog = ModelCatalog(
                        discoveryPrefix = "claude-codex--",
                        models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                        defaultContextWindow = 272_000,
                    ),
                    pinnedModel = "gpt-5.6-sol",
                    auth = CapFakeAuth(),
                    baseUrl = mock.baseUrl,
                    watchdog = watchdog,
                    loginCommand = "claudex login",
                ),
                showReasoning = ReasoningDisplay.TEXT,
                replayReasoning = false,
                configEffort = "high",
                configSummary = "detailed",
            ),
            listenPort = port,
            deps = HeadDeps(
                upstream = UpstreamClient(firstByteTimeoutMs = 20_000, totalTimeoutMs = 30_000, maxRetries = 1),
                inferenceToken = "test-inference-token",
                gate = InflightGate({ 0 }),
                shadow = ShadowClassifier(log = {}),
                compactStats = CompactStats(tmp.resolve("compact.jsonl")),
                usageStore = UsageStore(tmp.resolve("usage.json"), tmp.resolve("ratelimit.json")),
                perfStats = PerfStats(tmp.resolve("perf.jsonl")),
                log = {},
            ),
        )
    }

    // Drives one turn through a head built for this arm's budget; the mock stalls 6s per attempt.
    private suspend fun turnOn(watchdog: WatchdogBudget, system: String): String {
        val port = freshPort()
        val server = head(port, watchdog)
        server.start()
        awaitListening(port)
        try {
            return turn(port, system)
        } finally {
            server.stop()
        }
    }

    private suspend fun turn(port: Int, system: String): String =
        client.post("http://127.0.0.1:$port/v1/messages") {
            header("Content-Type", "application/json")
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":8000,
                    "system":"$system",
                    "messages":[{"role":"user","content":"go"}]}""",
            )
        }.bodyAsText()

    @Test
    fun `a normal turn silent after the handshake is reaped by the first-output cap`() = runTest {
        val sse = turnOn(WatchdogBudget(1.seconds, 20.seconds, 30.seconds), "You are a test. SCENARIO:idlepre")
        assertTrue(sse.contains("overloaded_error"), "a stall is an honest, retryable failure: $sse")
        assertTrue(sse.contains("first-output cap"), "the 1s first-output tier must be the one that fired: $sse")
        assertFalse(sse.contains("stalled (watchdog)"), "the whole-turn wall must not have been reached: $sse")
    }

    // The system prompt carries Claude Code's verbatim summarizer marker, so the gateway classifies
    // the turn as a compaction (Compact.kt) and TurnDriveFactory hands it forCompact().
    @Test
    fun `a compact turn silent after the handshake survives the first-output cap and dies only on the total cap`() =
        runTest {
            val sse = turnOn(
                WatchdogBudget(1.seconds, 20.seconds, 4.seconds),
                "SCENARIO:idlepre You are tasked with summarizing conversations for another agent.",
            )
            assertTrue(sse.contains("overloaded_error"), "a stall is an honest, retryable failure: $sse")
            assertFalse(
                sse.contains("first-output cap"),
                "a compaction's pre-output silence must not be judged on the 1s first-output tier: $sse",
            )
            // The whole-turn cap cancels the TURN, so it surfaces through the cancellation seal's
            // generic watchdog wording (as HeadServerFoldTest's NF-03 arm pins), not a tier-named
            // message — which is exactly the discriminator: the 1s tier would have said its name.
            assertTrue(sse.contains("stalled (watchdog)"), "only the whole-turn wall may end it: $sse")
        }
}
