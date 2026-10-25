// NEW (rig's finding, 2026-10-24): an interrupted NON-STREAM compaction must hold its collected
// answer for the byte-identical retry instead of throwing the whole prefill away with the call.
// Drives the REAL production path: HeadServer on an ephemeral port, a raw socket client that
// hangs up mid-compaction (the Esc), and a held upstream whose release lets the detached drive
// finish cleanly. The retry then arrives while the upstream is parked again: answered from the
// hold with NO new upstream turn — exactly what hack-program never got from the collect path.
package splice.head

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
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
import splice.dialect.responses.ReasoningSettings
import splice.upstream.ProviderLocations
import splice.upstream.ProviderTuning
import splice.upstream.Waiter
import splice.upstream.retry.InflightGate
import splice.upstream.transport.UpstreamClient
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

private const val POLL_MS = 50L

private class CollectRetryAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-ccr", "acct-ccr")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

/** The started head plus the seams this scenario watches: its held-upstream mock, its admission
 *  gate snapshot, and every line the head logged. */
private data class HeldHead(
    val mock: MockChatGptUpstream,
    val gate: InflightGate,
    val head: HeadServer,
    val lines: CopyOnWriteArrayList<String>,
)

class CollectCompactionRetryTest {

    // stream:false is the whole point: Claude Code's auto-compaction on heads like hack-program.
    private val body = """{"model":"claude-codex--gpt-5.6-sol","stream":false,"max_tokens":64,""" +
        """"system":"SCENARIO:hold You are tasked with summarizing conversations for another agent.",""" +
        """"messages":[{"role":"user","content":"compact"}]}"""

    private fun post(port: Int): Socket {
        val socket = Socket("127.0.0.1", port)
        val request = "POST /v1/messages HTTP/1.1\r\n" +
            "Host: 127.0.0.1:$port\r\n" +
            "Authorization: Bearer test-inference-token\r\n" +
            "Content-Type: application/json\r\n" +
            "x-claude-code-session-id: sess-collect-retry\r\n" +
            "Content-Length: ${body.toByteArray().size}\r\n" +
            "Connection: close\r\n\r\n" + body
        socket.getOutputStream().write(request.toByteArray())
        socket.getOutputStream().flush()
        return socket
    }

    /** Reads ONE collected JSON answer to end-of-stream (Connection: close did its work). */
    private fun readAnswer(socket: Socket): String =
        socket.getInputStream().bufferedReader().readText().also { socket.close() }

    private suspend fun waitFor(capMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + capMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            delay(POLL_MS)
        }
        return cond()
    }

    /** The real production wiring this scenario drives: an ephemeral-port HeadServer whose only
     *  upstream is a held MockChatGptUpstream behind a four-slot admission gate, with every log
     *  line captured for the serving assertions. */
    private suspend fun startHeldHead(tmp: Path): HeldHead {
        val mock = MockChatGptUpstream()
        val waiter = object : Waiter {
            override suspend fun wait(ms: Long) = Unit
        }
        val gate = InflightGate({ 4 }, { 4 })
        val lines = CopyOnWriteArrayList<String>()
        val head = HeadServer(
            provider = TestResponsesProvider(
                tuning = ProviderTuning(
                    name = splice.upstream.ProviderName(key = "codex", label = "claudex"),
                    catalog = ModelCatalog(
                        discoveryPrefix = "claude-codex--",
                        models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                        defaultContextWindow = 272_000,
                    ),
                    pinnedModel = "gpt-5.6-sol",
                    auth = CollectRetryAuth(),
                    locations = ProviderLocations(baseUrl = mock.baseUrl),
                    watchdog = WatchdogBudget(600.seconds, 600.seconds, 900.seconds),
                    loginCommand = "claudex login",
                ),
                reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
            ),
            listenPort = 0,
            deps = headDeps(
                tmp = tmp,
                upstream = UpstreamClient(totalTimeoutMs = 900_000, maxRetries = 1),
                gate = gate,
                log = { lines += it },
                seams = HeadDeps.HeadSeams(waiter = waiter),
            ),
        )
        head.start()
        awaitListening(head.port)
        return HeldHead(mock, gate, head, lines)
    }

    @Test
    fun `an interrupted non-stream compaction holds its answer and the identical retry costs no upstream turn`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val held = startHeldHead(tmp)
        try {
            // FIRST ATTEMPT: reach upstream, hold a slot, then the client hangs up mid-compaction.
            val first = post(held.head.port)
            assertTrue(
                waitFor(20_000) { held.mock.upstreamBodies.size == 1 },
                "precondition: the compaction must reach upstream before the client leaves:" +
                    " ${held.lines.joinToString("")}",
            )
            assertTrue(waitFor(20_000) { held.gate.snapshot().inflight == 1 }, "the collect holds its slot")
            first.close() // the Esc

            // With no client left, release the hold so the detached drive can end cleanly and keep its answer.
            held.mock.releaseHold()
            assertTrue(
                waitFor(20_000) { held.lines.any { "its collected answer held for a byte-identical retry" in it } },
                "the interrupted collect compaction must detach and hold its answer: ${held.lines.joinToString("")}",
            )

            // RETRY, byte-identical, while the upstream is parked again: answered from the hold alone.
            held.mock.resetHold()
            val answer = readAnswer(post(held.head.port))
            assertEquals(1, held.mock.upstreamBodies.size, "the retry must not start another upstream turn")
            assertTrue(
                "\"type\":\"message\"" in answer,
                "a collect retry reads ONE messages body, not SSE frames: $answer",
            )
            assertTrue(
                held.lines.any { "collect compaction answer served from its hold" in it },
                "the serving side names its source: ${held.lines.joinToString("")}",
            )
        } finally {
            held.mock.releaseHold() // never leave a parked upstream thread behind
            held.head.stop()
        }
    }
}
