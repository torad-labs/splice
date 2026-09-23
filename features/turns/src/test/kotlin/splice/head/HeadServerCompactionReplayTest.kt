// NEW (2026-09-05): a compaction outlives its client. Claude Code aborts an auto-compaction at 600 s
// of wall clock and retries the same bytes minutes later; before this, every abort cancelled a 600 s
// upstream read. Driven through the REAL production path: a real HeadServer, a raw client socket
// closed mid-turn (a real FIN, as in HeadServerCollectDisconnectTest), an upstream parked on
// SCENARIO:hold, then the byte-identical retry served from the recording with no second upstream
// turn — and the gate slot travelling with the detached drive, not with the dead call.
package splice.head

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
import splice.upstream.ProviderTuning
import splice.upstream.retry.InflightGate
import splice.upstream.transport.UpstreamClient
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

private class CompactionReplayAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-cr", "acct-cr")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeadServerCompactionReplayTest {

    private val mock = MockChatGptUpstream()

    // A getter: the head binds port 0, and a stop/start rebinds a fresh one the tests must follow.
    private val port: Int get() = head.port
    private val gate = InflightGate({ 0 })
    private val lines = CopyOnWriteArrayList<String>()
    private lateinit var head: HeadServer
    private val client = HttpClient(CIO) {
        defaultRequest { bearerAuth("test-inference-token") }
    }

    @BeforeAll
    fun setUp() = runBlocking {
        val tmp = Files.createTempDirectory("head-compaction-replay")
        head = HeadServer(
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
                    auth = CompactionReplayAuth(),
                    baseUrl = mock.baseUrl,
                    // Enormous on purpose: nothing in this test may end the turn but the hold release.
                    watchdog = WatchdogBudget(600.seconds, 600.seconds, 900.seconds),
                ),
                showReasoning = ReasoningDisplay.TEXT,
                replayReasoning = false,
                configEffort = "high",
                configSummary = "detailed",
            ),
            listenPort = 0,
            deps = headDeps(
                tmp = tmp,
                upstream = UpstreamClient(firstByteTimeoutMs = 600_000, totalTimeoutMs = 900_000, maxRetries = 2),
                gate = gate,
                log = { lines += it },
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
        client.close()
    }

    // Claude Code's verbatim summarizer marker makes this a compaction (CompactClassifier); the
    // scenario parks the upstream after its first delta ("held") until the test releases it.
    private val body = """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,""" +
        """"system":"SCENARIO:hold You are tasked with summarizing conversations for another agent.",""" +
        """"messages":[{"role":"user","content":"compact"}]}"""

    /** A real client whose close() is a real FIN — no HTTP-client pool or cancellation semantics. */
    private fun openCompaction(): Socket {
        val socket = Socket("127.0.0.1", port)
        val request = "POST /v1/messages HTTP/1.1\r\n" +
            "Host: 127.0.0.1:$port\r\n" +
            "Authorization: Bearer test-inference-token\r\n" +
            "Content-Type: application/json\r\n" +
            "x-claude-code-session-id: sess-compaction\r\n" +
            "Content-Length: ${body.toByteArray().size}\r\n" +
            "Connection: close\r\n\r\n" + body
        socket.getOutputStream().write(request.toByteArray())
        socket.getOutputStream().flush()
        return socket
    }

    // A deadline poll, the rule's sanctioned shape: log lines, gate and mock state change server-side
    // (a detached drive outlives its call), and none of them offers a signal to await.
    private suspend fun waitFor(capMs: Long, cond: () -> Boolean): Boolean {
        val pollMs = 50L
        val deadline = System.currentTimeMillis() + capMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            delay(pollMs)
        }
        return cond()
    }

    /** [since]: lines are shared across the class's tests; a fragment one test also logs is only
     *  evidence when it appears after the mark the caller took. */
    private fun logged(fragment: String, since: Int = 0): Boolean =
        lines.drop(since).any { it.contains(fragment) }

    /** RED before the fix (review 2026-09-05, splice-astra): stop() cancelled the detached scope and
     *  start() reused the same driver, so the first compaction after a head restart launched into a
     *  dead scope — an empty 200 with the slot handed off to nobody. The mock's happy path (no
     *  SCENARIO marker) streams a whole answer; only the restart is under test here. */
    @Test
    fun `a compaction after a head stop and start is still driven whole and its slot comes back`() = runBlocking {
        head.stop()
        head.start()
        awaitListening(port)
        val upstreamBefore = mock.upstreamBodies.size
        val sse = post(body.replace("SCENARIO:hold ", ""))
        assertTrue(
            sse.contains("event: message_stop"),
            "a whole answer after the restart: $sse\n${lines.joinToString("")}",
        )
        assertTrue(!sse.contains("event: error"), "no error frame after the restart: $sse")
        assertEquals(upstreamBefore + 1, mock.upstreamBodies.size, "the compaction went upstream")
        assertTrue(waitFor(5_000) { gate.snapshot().inflight == 0 }, "the slot must come back: ${gate.snapshot()}")
    }

    private suspend fun post(json: String): String =
        client.post("http://127.0.0.1:$port/v1/messages") {
            header("Content-Type", "application/json")
            header("x-claude-code-session-id", "sess-compaction")
            setBody(json)
        }.bodyAsText()

    @Test
    fun `a compaction whose client hangs up finishes detached and its answer is replayed to the retry`() = runBlocking {
        mock.resetHold()
        // V4-139: every wait below reads only lines logged AFTER this mark. The in-flight follower
        // test runs first in this class and logs both fragments; unmarked, the detach wait passed on
        // ITS line, the hold was released before this compaction had detached, the turn finished
        // attached with nothing recorded, and the retry went upstream (4 bodies, not 3).
        val mark = lines.size
        val upstreamBefore = mock.upstreamBodies.size
        val socket = openCompaction()
        assertTrue(waitFor(15_000) { mock.upstreamBodies.size > upstreamBefore }, "the compaction must reach upstream")
        assertTrue(waitFor(15_000) { gate.snapshot().inflight == 1 }, "the compaction must hold a gate slot")
        socket.close()
        // Either detach path may notice the FIN first (Ktor cancelling the call, or the keepalive
        // pinger's failed write, a real race in this run: review of PR 137); both log the shared
        // DETACHED_NOTE, which is the fact under test. The slot assertion below holds for both.
        assertTrue(waitFor(20_000) { logged("compaction continues detached", mark) }, lines.drop(mark).joinToString())
        assertEquals(1L, mock.holdRelease.count, "the upstream must still be parked: nothing here has finished")
        assertEquals(1, gate.snapshot().inflight, "the slot travels with the detached drive, not the dead call")

        mock.releaseHold()
        assertTrue(waitFor(20_000) { logged("held for a byte-identical retry", mark) }, lines.drop(mark).joinToString())
        assertTrue(waitFor(10_000) { gate.snapshot().inflight == 0 }, "the slot comes back when the upstream turn ends")
        val upstreamAfterFirst = mock.upstreamBodies.size

        val sse = post(body)
        val again = if (sse.contains("held")) "" else "\nsecond try: ${post(body)}"
        assertTrue(
            sse.contains("held"),
            "the retry must receive the recorded answer: $sse$again\n${lines.joinToString("")}",
        )
        assertTrue(sse.contains("event: message_stop"), "the recorded answer must be whole: $sse")
        assertEquals(upstreamAfterFirst, mock.upstreamBodies.size, "the retry must not start a second upstream turn")
        assertTrue(logged("replaying its answer, no upstream turn", mark), lines.drop(mark).joinToString())
    }

    /** Review of PR 137: a retry that arrives while the compaction is still driving follows the live
     *  recording. It must not hold a second gate slot for the wait (the drive holds one), and it
     *  receives the whole answer from the one upstream turn. */
    @Test
    fun `a retry that arrives while the compaction is still in flight follows it on the drive's slot`() = runBlocking {
        mock.resetHold()
        val mark = lines.size
        val upstreamBefore = mock.upstreamBodies.size
        val socket = openCompaction()
        assertTrue(waitFor(15_000) { mock.upstreamBodies.size > upstreamBefore }, "the compaction must reach upstream")
        assertTrue(waitFor(15_000) { gate.snapshot().inflight == 1 }, "the compaction must hold a gate slot")
        socket.close()
        assertTrue(waitFor(20_000) { logged("compaction continues detached", mark) }, lines.drop(mark).joinToString())

        val retry = async { post(body) }
        assertTrue(waitFor(10_000) { logged("still running", mark) }, lines.drop(mark).joinToString())
        assertTrue(waitFor(5_000) { gate.snapshot().inflight == 1 }, "one compaction, one slot: ${gate.snapshot()}")
        assertEquals(1L, mock.holdRelease.count, "the upstream must still be parked: the retry is following it")
        assertEquals(1, gate.snapshot().inflight, "the follower rides the drive's slot, it does not hold its own")

        mock.releaseHold()
        val sse = retry.await()
        assertTrue(sse.contains("held"), "the follower gets the recorded answer: $sse")
        assertTrue(sse.contains("event: message_stop"), "the follower gets the whole answer: $sse")
        assertEquals(upstreamBefore + 1, mock.upstreamBodies.size, "one upstream turn served both attempts")
        assertTrue(waitFor(10_000) { gate.snapshot().inflight == 0 }, "every slot comes back: ${gate.snapshot()}")
        // Logged after the response is written: the client can be back before the server gets there.
        assertTrue(waitFor(5_000) { logged("the retry cost no upstream turn", mark) }, lines.drop(mark).joinToString())
        assertTrue(waitFor(5_000) { logged("compaction answer replayed") }, lines.joinToString())
        assertTrue(waitFor(5_000) { gate.snapshot().inflight == 0 }, "the replay releases its own slot")
    }
}
