// NEW (V4-74): a stop() while a turn is MID-STREAM must DRAIN, not tear the client's socket.
//
// The measured bug: on a daemon restart the client printed "API Error: Connection lost mid-response"
// and did not retry. The cause was not the restart itself but Ktor's own per-engine JVM shutdown
// hook disposing the application scope concurrently with the daemon's ordered stop, so the SSE write
// failed and the turn ended as a conn-reset AFTER content — plus a drain budget (5s) that could not
// outlive the operator's 7-16s deepseek turns even when it did get to run.
//
// The assertions are on WHAT THE CLIENT RECEIVED, never on splice's internal call counts: the row's
// root-cause lesson is that policy-mirroring tests ratified every regression they were written to
// prevent. So the test holds a turn, calls stop(), releases the turn INSIDE the drain window, and
// requires the client's read to complete with a terminal event instead of throwing.
//
// WHY THE HOLD IS LONGER THAN THE OLD DRAIN: this test is also the mutation proof for the ladder.
// Set STOP_DRAIN_NS back to 5s and the 6.5s hold outlives it, the drain times out, the engine stops
// mid-turn and the client's read tears — recorded in the row note with its hash. Under the fixed
// 45s ladder the turn finishes comfortably inside the drain.
package head

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mock.MockChatGptUpstream
import mock.TestResponsesProvider
import mock.awaitListening
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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
import splice.spi.InflightGate
import splice.spi.ProviderTuning
import splice.spi.UpstreamClient
import java.net.ServerSocket
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

// why 6.5s: it must OUTLIVE the old 5s STOP_DRAIN_NS so reverting the ladder to 5s makes the
// drain time out and this test fail (the ladder's mutation proof); under the fixed 45s ladder
// the turn still finishes inside the drain.
private const val DRAIN_HOLD_MS = 6_500L

// why 100ms: the inflight precondition flips once the turn holds a slot; 100ms keeps the 5s
// bound (50 tries) from busy-spinning while still observing a slot promptly.
private const val INFLIGHT_POLL_MS = 100L

private class DrainFakeAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-drain", "acct-drain")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

class HeadServerStopDrainTest {

    /** A real HeadServer over the mock upstream, on an ephemeral port. maxRetries = 1 so the turn
     *  reaches the hold scenario and STAYS there rather than backing off through it. */
    private class Rig(tmp: Path) {
        val mock = MockChatGptUpstream()
        val gate = InflightGate(maxInflight = { 4 }, maxQueued = { 4 })
        val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }
        val port = ServerSocket(0).use { it.localPort }
        val head = HeadServer(
            provider = TestResponsesProvider(
                tuning = ProviderTuning(
                    key = "codex",
                    label = "claudex",
                    catalog = ModelCatalog(
                        discoveryPrefix = "claude-codex--",
                        models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                        defaultContextWindow = 272_000,
                    ),
                    pinnedModel = "gpt-5.6-sol",
                    auth = DrainFakeAuth(),
                    baseUrl = mock.baseUrl,
                    watchdog = WatchdogBudget(30.seconds, 30.seconds, 60.seconds),
                    loginCommand = "claudex login",
                ),
                showReasoning = ReasoningDisplay.TEXT,
                replayReasoning = false,
                configEffort = "high",
                configSummary = "detailed",
            ),
            listenPort = port,
            deps = HeadDeps(
                upstream = UpstreamClient(firstByteTimeoutMs = 5_000, totalTimeoutMs = 60_000, maxRetries = 1),
                inferenceToken = "test-inference-token",
                gate = gate,
                shadow = ShadowClassifier(log = {}),
                compactStats = CompactStats(tmp.resolve("compact.jsonl")),
                usageStore = UsageStore(tmp.resolve("usage.json"), tmp.resolve("ratelimit.json")),
                perfStats = PerfStats(tmp.resolve("perf.jsonl")),
                log = {},
            ),
        )

        suspend fun start() {
            mock.resetHold()
            head.start()
            awaitListening(port)
        }

        suspend fun close() {
            head.stop()
            client.close()
            mock.stop()
        }

        /** The held turn, read to its END: a torn socket throws here, which is the failure this
         *  test exists to catch, so the read itself is part of the assertion. */
        suspend fun heldTurn(): String =
            client.post("http://127.0.0.1:$port/v1/messages") {
                header("Content-Type", "application/json")
                setBody(
                    """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,
                        "system":"You are a test. SCENARIO:hold",
                        "messages":[{"role":"user","content":"go"}]}""",
                )
            }.bodyAsText()

        suspend fun awaitInflight(): Boolean {
            repeat(50) {
                if (gate.snapshot().inflight >= 1) return true
                delay(INFLIGHT_POLL_MS)
            }
            return gate.snapshot().inflight >= 1
        }
    }

    @Test
    fun `a stop during a held turn drains it to its terminal instead of tearing the socket`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val rig = Rig(tmp)
        rig.start()
        try {
            val turn = async(Dispatchers.IO) { rig.heldTurn() }
            assertTrue(rig.awaitInflight(), "precondition: the turn must be holding a slot")

            // THE STOP, issued while the turn is mid-stream, on its own dispatcher so the drain and
            // the turn overlap the way they do under SIGTERM.
            val stopping = async(Dispatchers.IO) { rig.head.stop() }

            // The turn finishes INSIDE the drain window, and the hold outlives the OLD 5s drain on
            // purpose: that is what makes this test the ladder's mutation proof.
            delay(DRAIN_HOLD_MS)
            rig.mock.releaseHold()

            val body = turn.await() // a torn socket fails HERE, by throwing
            stopping.await()

            assertTrue(
                body.contains("message_stop") || body.contains("event: error"),
                "the drained turn must reach the client as a real terminal, not a cut stream; got: " +
                    body.take(300),
            )
        } finally {
            rig.close()
        }
    }
}
