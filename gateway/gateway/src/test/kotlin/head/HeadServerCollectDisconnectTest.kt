// NEW (PR 99, the collect-cancellation question): settles whether a client that hangs up
// mid-turn on the stream:false path still gets its turn CANCELLED. Two comments in the tree
// pulled opposite ways — TurnDriver.driveOneTurn says a client disconnect cancels the PARENT
// call and propagates down, while ClientChannel.launchClientPinger records a MEASURED load test
// where slots stayed pinned for the whole watchdog budget with no downstream writes flowing, and
// collect() is by definition a path with no downstream writes flowing. Only an experiment could
// tell them apart, so: a real HeadServer, a real client socket closed mid-turn, an upstream
// parked on SCENARIO:hold, and a watchdog budget so large that any slot release observed inside
// the assertion window CANNOT be the watchdog.
package head

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mock.MockChatGptUpstream
import mock.awaitListening
import mock.freshPort
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
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
import java.net.Socket
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

private class CollectDisconnectAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-cd", "acct-cd")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeadServerCollectDisconnectTest {

    private val mock = MockChatGptUpstream()
    private val port = freshPort()
    private lateinit var head: HeadServer
    private val gate = InflightGate({ 0 })
    private lateinit var tmp: java.nio.file.Path

    @BeforeAll
    fun setUp() = runBlocking {
        tmp = Files.createTempDirectory("head-collect-disconnect")
        head = HeadServer(
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
                    auth = CollectDisconnectAuth(),
                    baseUrl = mock.baseUrl,
                    // Deliberately enormous: the watchdog must never be the thing that frees the
                    // slot inside this test's window, or the experiment answers nothing.
                    watchdog = WatchdogBudget(600.seconds, 600.seconds, 900.seconds),
                ),
                showReasoning = ReasoningDisplay.TEXT,
                replayReasoning = false,
                configEffort = "high",
                configSummary = "detailed",
            ),
            listenPort = port,
            deps = HeadDeps(
                upstream = UpstreamClient(
                    firstByteTimeoutMs = 600_000,
                    totalTimeoutMs = 900_000,
                    maxRetries = 2,
                ),
                inferenceToken = "test-inference-token",
                gate = gate,
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
    fun tearDown() = runBlocking {
        mock.releaseHold() // never leave a parked upstream thread behind
        head.stop()
        mock.stop()
    }

    /** Write the request head + body on a raw socket: a real client whose close() is a real FIN,
     *  with no HTTP-client connection pool or cancellation semantics in between. The two arms
     *  differ in exactly one byte-range — `stream` — so any difference in outcome is the path. */
    private fun openTurn(stream: Boolean): Socket {
        val body = """{"model":"claude-codex--gpt-5.6-sol","stream":$stream,"max_tokens":64,""" +
            """"system":"You are a test. SCENARIO:hold",""" +
            """"messages":[{"role":"user","content":"go"}]}"""
        val socket = Socket("127.0.0.1", port)
        val request = "POST /v1/messages HTTP/1.1\r\n" +
            "Host: 127.0.0.1:$port\r\n" +
            "Authorization: Bearer test-inference-token\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${body.toByteArray().size}\r\n" +
            "Connection: close\r\n\r\n" + body
        socket.getOutputStream().write(request.toByteArray())
        socket.getOutputStream().flush()
        return socket
    }

    private suspend fun waitFor(capMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + capMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            delay(50)
        }
        return cond()
    }

    /** Drive one turn to the parked-upstream state and hang the client up there. Returns whether
     *  the gate slot came back within [observeMs] — the whole experiment in one number. */
    private suspend fun abandonMidTurnAndPoll(stream: Boolean, observeMs: Long): Boolean {
        mock.resetHold()
        val upstreamBefore = mock.upstreamBodies.size
        val socket = openTurn(stream)
        // Mid-turn means mid-turn: the slot is held AND the upstream leg is open and parked on the
        // hold latch. Asserting on the slot alone would also pass for a request that never reached
        // upstream at all.
        assertTrue(waitFor(15_000) { gate.snapshot().inflight == 1 }, "the turn must hold a gate slot")
        assertTrue(
            waitFor(15_000) { mock.upstreamBodies.size > upstreamBefore },
            "the turn must have reached upstream before the client hangs up",
        )
        socket.close()
        val freed = waitFor(observeMs) { gate.snapshot().inflight == 0 }
        // The hold latch is still armed, so nothing here can be a turn that simply FINISHED.
        assertEquals(1L, mock.holdRelease.count, "the upstream must still be parked for this to mean anything")
        mock.releaseHold()
        // Whatever the outcome, leave the gate empty for the next arm.
        assertTrue(waitFor(30_000) { gate.snapshot().inflight == 0 }, "the released turn must drain")
        return freed
    }

    /** CONTROL ARM — proves the instrument. Same socket, same close(), same parked upstream: on
     *  stream:true the keepalive pinger's write fails, flips clientGone and cancels the turn, so
     *  the slot comes back within a couple of ping intervals. A failure here means the raw-socket
     *  hang-up itself is not observable and the sibling test below proves nothing. */
    @Test
    fun `a stream-true turn abandoned mid-hold gets its gate slot back via the keepalive pinger`() = runBlocking {
        assertTrue(
            abandonMidTurnAndPoll(stream = true, observeMs = 20_000),
            "the stream path must free its slot on a client hang-up: ${gate.snapshot()}",
        )
    }

    /** INVERTED (HD-29). Same socket, same close(), same parked upstream as the control arm:
     *  collect now observes hang-up via Netty closeFuture → ClientChannel.connectionClosed, so
     *  the slot comes back without a pinger and without waiting for the 600s watchdog. Kept
     *  (not deleted) so the characterisation cannot land silently as a removed test. */
    @Test
    fun `a stream-false turn abandoned mid-collect gets its gate slot back via connection-closed`() = runBlocking {
        assertTrue(
            abandonMidTurnAndPoll(stream = false, observeMs = 20_000),
            "the collect path must free its slot on a client hang-up: ${gate.snapshot()}",
        )
    }
}
