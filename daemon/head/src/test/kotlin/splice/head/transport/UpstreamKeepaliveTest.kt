// NEW: V4-125 — the row's headline criterion, driven through the REAL production path (HeadServer
// over HTTP, TurnDriveFactory wiring the budget, the mock acknowledging and then going quiet).
//
// THE CASE THIS EXISTS FOR is the one the operator's ruling is about (IDLE IS A PROBE): an upstream
// that sends headers, then says nothing for longer than the idle tier, and is NOT dead. Before this
// row the SSE path passed no path pulse at all, so the watchdog read its NEVER_PINGED_MS default and
// reaped that round BY CONSTRUCTION — the tier was a verdict on a transport with no way to answer it.
// The turn now holds, the upstream speaks when it is ready, and the client sees a NORMAL SUCCESS.
//
// SCENARIO:prefill is exactly that shape and is used rather than a new mock scenario: 1.5s of silence,
// then content, then response.completed. The tier is set BELOW that silence so the breach genuinely
// happens, which is what makes the hold the thing under test rather than an incidental.
//
// "perf shows the hold" is asserted from the PERF ROW, not from a field reached into the server — the
// turn line is the artifact an operator actually reads, and its `watchdog=held(...)` clause is the
// claim that the silence was judged and survived rather than never noticed. Asserting the ABSENCE of
// `watchdog=idle` alongside it is the other half: no reap happened.
package splice.head.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.test.runTest
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
import splice.head.HeadServer
import splice.head.MockChatGptUpstream
import splice.head.TestResponsesProvider
import splice.head.awaitListening
import splice.head.freshPort
import splice.head.headDeps
import splice.upstream.ProviderTuning
import splice.upstream.transport.UpstreamClient
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private class KeepaliveFakeAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-ka", "acct-ka")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UpstreamKeepaliveTest {

    private val mock = MockChatGptUpstream()
    private val client = HttpClient(CIO) {
        defaultRequest { bearerAuth("test-inference-token") }
    }

    /** Where the head's own log goes, so the TURN LINE can be read. The line is the artifact that
     *  carries the watchdog verdict — [splice.head.turn.TurnLine] renders it — and it is NOT the
     *  same thing as the perf JSONL row, which carries counters and no watchdog clause. */
    private val logLines = mutableListOf<String>()

    @AfterAll
    fun tearDown() {
        client.close()
        mock.stop()
    }

    private fun head(port: Int, tmp: Path, watchdog: WatchdogBudget): HeadServer = HeadServer(
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
                auth = KeepaliveFakeAuth(),
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
        // Generous upstream timeouts: this arm is about the WATCHDOG tier, and a socket timeout
        // firing underneath it would end the round for a reason the assertions could not name.
        deps = headDeps(
            tmp = tmp,
            upstream = UpstreamClient(firstByteTimeoutMs = 20_000, totalTimeoutMs = 30_000, maxRetries = 1),
            log = { logLines += it },
        ),
    )

    private suspend fun turn(port: Int, system: String): String = client.post("http://127.0.0.1:$port/v1/messages") {
        header("content-type", "application/json")
        setBody(
            """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":8000,
                "system":"$system",
                "messages":[{"role":"user","content":"go"}]}""",
        )
    }.bodyAsText()

    @Test
    fun `a silent-but-alive upstream past the tier completes the turn when it speaks`() = runTest {
        val tmp = Files.createTempDirectory("head-keepalive")
        val port = freshPort()
        // The tier sits BELOW the mock's 1.5s prefill silence, so the breach is real: a 1s
        // first-output cap is passed at ~1s while the connection stays open and un-errored.
        val server = head(port, tmp, WatchdogBudget(1.seconds, 1.seconds, 30.seconds))
        server.start()
        awaitListening(port)
        val sse = try {
            turn(port, "You are a test. SCENARIO:prefill")
        } finally {
            server.stop()
        }

        // 1. THE TURN SUCCEEDED. This is the whole point: the silence did not become an error, and
        // the content the upstream eventually sent reached the client.
        assertTrue(
            sse.contains("summary after slow prefill"),
            "the upstream spoke after the stall and the client must have heard it: $sse",
        )
        assertFalse(
            sse.contains("overloaded_error"),
            "no idle tier may reach the client as an error any more: $sse",
        )

        // 2. THE TURN LINE SHOWS THE HOLD, and shows no reap. Both halves are asserted, because the
        // hold clause alone would also be satisfied by a round that was held and then reaped later.
        val turnLines = logLines.filter { "turn compact=" in it }
        assertTrue(turnLines.isNotEmpty(), "the turn line is where the verdict is written; log: $logLines")
        val line = turnLines.joinToString("\n")
        assertTrue(
            line.contains("watchdog=held"),
            "the turn line must record that the silence was judged and held, not never noticed: $line",
        )
        assertFalse(
            line.contains("watchdog=idle"),
            "the round was reaped by the idle tier, which is the behaviour this row removes: $line",
        )
    }
}
