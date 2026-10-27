// NEW (rig's finding, 2026-10-24): an interrupted NON-STREAM compaction must hold its collected
// answer for the byte-identical retry instead of throwing the whole prefill away with the call.
// Drives the REAL production path: HeadServer on an ephemeral port, a raw socket client that
// hangs up mid-compaction (the Esc), and a held upstream whose release lets the detached drive
// finish cleanly. The retry then arrives while the upstream is parked again: answered from the
// hold with NO new upstream turn — exactly what hack-program never got from the collect path.
package splice.head

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    // The SAME request with stream:true: Claude Code's first attempt streams, and when that attempt dies its
    // fallback re-sends these bytes as stream:false (rig's finding, hack-program on glml53, 2026-10-25 00:58 CT).
    private val streamBody = body.replace("\"stream\":false", "\"stream\":true")

    private fun post(port: Int, json: String = body): Socket {
        val socket = Socket("127.0.0.1", port)
        val request = "POST /v1/messages HTTP/1.1\r\n" +
            "Host: 127.0.0.1:$port\r\n" +
            "Authorization: Bearer test-inference-token\r\n" +
            "Content-Type: application/json\r\n" +
            "x-claude-code-session-id: sess-collect-retry\r\n" +
            "Content-Length: ${json.toByteArray().size}\r\n" +
            "Connection: close\r\n\r\n" + json
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
                upstream = UpstreamClient(totalTimeoutMs = 900_000),
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
            // The head must see the client gone before the upstream answers. On a loaded machine the close can land
            // after a release that follows it at once, and the turn then ends as an ordinary delivered one.
            assertTrue(
                waitFor(20_000) { held.lines.any { "client left mid-turn" in it } },
                "precondition: the head notices the client left before the upstream answers:" +
                    " ${held.lines.joinToString("")}",
            )

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

    /** The streamed first attempt, detached: it reached upstream and holds its slot, then its client left. */
    private suspend fun detachStreamingCompaction(held: HeldHead) {
        val first = post(held.head.port, streamBody)
        assertTrue(
            waitFor(20_000) { held.mock.upstreamBodies.size == 1 },
            "precondition: the streaming compaction must reach upstream: ${held.lines.joinToString("")}",
        )
        assertTrue(waitFor(20_000) { held.gate.snapshot().inflight == 1 }, "the streaming compaction holds its slot")
        first.close()
        assertTrue(
            waitFor(20_000) { held.lines.any { "compaction continues detached" in it } },
            "the streaming compaction must detach: ${held.lines.joinToString("")}",
        )
    }

    /** The ONE Messages body a collect answer carries, read off the raw HTTP response. */
    private fun messageOf(answer: String): JsonObject {
        assertTrue("event:" !in answer, "a collect retry reads ONE messages body, not SSE frames: $answer")
        return Json.parseToJsonElement(answer.substringAfter("\r\n\r\n")).jsonObject
    }

    /** What the hold scenario streamed, collected: one text block saying "held", ended cleanly. */
    private fun assertStreamedAnswerCollected(answer: String) {
        val message = messageOf(answer)
        assertEquals("message", message["type"]?.jsonPrimitive?.content, answer)
        val texts = message["content"]?.jsonArray.orEmpty().map { it.jsonObject["text"]?.jsonPrimitive?.content }
        assertEquals(listOf("held"), texts, "the retry gets the streamed answer's text: $answer")
        assertEquals("end_turn", message["stop_reason"]?.jsonPrimitive?.content, answer)
    }

    /** RED before the fix (rig, 2026-10-25): the non-stream fallback looked only at non-stream holds, so it
     *  started a second ~40 minute read beside the detached streamed one and starved it. */
    @Test
    fun `a non-stream retry of a running detached streaming compaction follows it and costs no upstream turn`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val held = startHeldHead(tmp)
        try {
            detachStreamingCompaction(held)
            // The fallback arrives while the streamed read is still parked, as it did 65 ms after the detach.
            val retry = post(held.head.port)
            assertTrue(
                waitFor(20_000) {
                    held.lines.any { "collecting its recording" in it } || held.mock.upstreamBodies.size > 1
                },
                "the retry must be prepared while the stream still runs: ${held.lines.joinToString("")}",
            )
            assertEquals(1, held.mock.upstreamBodies.size, "the non-stream retry must not start a second upstream read")

            held.mock.releaseHold()
            val answer = readAnswer(retry)
            assertStreamedAnswerCollected(answer)
            assertEquals(1, held.mock.upstreamBodies.size, "the whole exchange cost one upstream read")
        } finally {
            held.mock.releaseHold()
            held.head.stop()
        }
    }

    @Test
    fun `a non-stream retry after a detached streaming compaction finished is answered from its recording`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val held = startHeldHead(tmp)
        try {
            detachStreamingCompaction(held)
            held.mock.releaseHold()
            assertTrue(
                waitFor(20_000) { held.lines.any { "held for a byte-identical retry" in it } },
                "the detached stream must finish and keep its answer: ${held.lines.joinToString("")}",
            )
            // The hold stays released: a retry that wrongly went upstream would finish, and be counted.
            val answer = readAnswer(post(held.head.port))
            assertEquals(1, held.mock.upstreamBodies.size, "the non-stream retry must not start a second upstream read")
            assertStreamedAnswerCollected(answer)
        } finally {
            held.mock.releaseHold()
            held.head.stop()
        }
    }
}
