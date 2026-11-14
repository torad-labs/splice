// NEW (V4-74): a stop() while a turn is MID-STREAM must never tear the client's socket.
//
// The measured bug: on a daemon restart the client printed "API Error: Connection lost mid-response". The cause
// was Ktor's own per-engine JVM shutdown hook disposing the application scope concurrently with the daemon's
// ordered stop, so the SSE write failed and the turn ended as a conn-reset AFTER content.
//
// The assertions are on WHAT THE CLIENT RECEIVED, never on splice's internal call counts. Since Oct 29, 2026 a
// restart does not wait for a running turn: it hands it the restart line at once, so the next daemon takes over in
// seconds (Marcos: "we already send a message saying the server is restarting and so the user knows about it, then
// we just have the new daemon take over").
package splice.head

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.perf.OutcomeTag
import splice.core.perf.OutcomeTags
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.core.util.AsyncFileIo
import splice.dialect.responses.ReasoningSettings
import splice.upstream.Provider
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.Waiter
import splice.upstream.codemode.ProcessWaiter
import splice.upstream.retry.InflightGate
import splice.upstream.transport.UpstreamClient
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

// why 6.5s: a drain this test sets itself, long enough that a request sent half a second into it still meets the
// draining head.
private const val DRAIN_HOLD_MS = 6_500L

// why 10s: the cut's seal waits at most 2s and the engine stop about 2.5s; 10s leaves room for a loaded host and is
// still far below the 45s wait a restart used to hold the next daemon for.
private const val TAKEOVER_BOUND_MS = 10_000L

// why 1.5s: a turn held past the drain is cut when the budget is spent, so the budget is the test's wait; 1.5s
// is long enough for the stop to start draining before it gives up, and short enough to cost the run nothing.
private const val SHORT_DRAIN_MS = 1_500L

// why 100ms: the inflight precondition flips once the turn holds a slot; 100ms keeps the 5s
// bound (50 tries) from busy-spinning while still observing a slot promptly.
private const val INFLIGHT_POLL_MS = 100L

// why 10ms: the paced tail leaves the head within about a second of the slot releasing, so the stop must be
// issued inside that window and a 100ms poll would spend a tenth of it.
private const val RELEASE_POLL_MS = 10L

// why 500ms: the stop closes admission before anything else, so half a second puts the request well inside a drain
// the held turn keeps open for 6.5s.
private const val DRAINING_SETTLE_MS = 500L

// why 10s: a closed connection ends the read at once; this only bounds a head that answered nothing and kept it open.
private const val RAW_READ_TIMEOUT_MS = 10_000

// why 10s: the hold scenario streams its first delta the moment the upstream answers; this only bounds a head that
// never delivers it, so a failure reads as one instead of hanging.
private const val CONTENT_WAIT_MS = 10_000L

// why 600: a burst this size is paced over the head's whole one-second window, not written in one go.
private const val BURST_DELTAS = 600

// Failure-handling slice 2: the head's line for a restart (final-design.md section 10).
//
// Slice 4 reads the client reaction record for what follows it: this rig's streamed turn is cut after its deltas
// reached the client, where 2.1.310 keeps what it holds and sends nothing again (inband-overloaded-after-content),
// so the line asks the person; the buffered answer's 529 is one the client re-sends itself (http-529-non-stream).
private const val RESTART_SAID = "[splice] splice restarted during this turn."
private const val CLIENT_RESENDS = "Claude Code sends this turn again."
private const val PERSON_RESENDS = "Send the message again."

/** The words of the operator's stop, which a restart never says. */
private const val CONSOLE_STOP = "stopped from the splice console"

private class DrainFakeAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-drain", "acct-drain")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

/** The rig's provider, declared able to finish a turn beside the next daemon (#523), as the Anthropic passthrough
 *  is: what a retiring head keeps is decided by that one answer, whatever dialect the turn speaks. */
private class FinishingProvider(base: Provider) : Provider by base {
    override val finishesTurnsWhenRetired: Boolean get() = true
}

/** An upstream that answers every request with one burst of small text deltas and its completion, then ends.
 *  The head paces a burst out to its client over up to a second, so the client write outlives the turn. */
private class BurstUpstream : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val asked = AtomicBoolean(false)
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { ex ->
            ex.requestBody.use { it.readAllBytes() }
            asked.set(true)
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.use { out ->
                out.write(event("""{"type":"response.output_item.added","output_index":0,"item":{"type":"message"}}"""))
                repeat(BURST_DELTAS) {
                    out.write(event("""{"type":"response.output_text.delta","output_index":0,"delta":"burst "}"""))
                }
                out.write(event("""{"type":"response.output_item.done","output_index":0}"""))
                out.write(
                    event(
                        """{"type":"response.completed","response":{"id":"rburst","status":"completed",""" +
                            """"output":[],"usage":{"input_tokens":10,"output_tokens":5}}}""",
                    ),
                )
            }
        }
        server.start()
    }

    private fun event(json: String): ByteArray = "data: $json\n\n".toByteArray()

    override fun close() = server.stop(0)
}

class HeadServerStopDrainTest {

    /** A real HeadServer over the mock upstream, on an OS-assigned port it binds itself (0) and
     *  reports after start. maxRetries = 1 so the turn reaches the hold scenario and STAYS there
     *  rather than backing off through it. */
    private class Rig(
        tmp: Path,
        watchdog: WatchdogBudget = WatchdogBudget(30.seconds, 30.seconds, 60.seconds),
        waiter: Waiter = ProcessWaiter(),
        stopDrainMs: Long = DEFAULT_STOP_DRAIN_MS,
        upstreamUrl: String? = null,
        finishing: Boolean = false,
    ) {
        val mock = MockChatGptUpstream()
        val cutAt = AtomicLong(0L)
        val gate = InflightGate(maxInflight = { 4 }, maxQueued = { 4 })
        val client = HttpClient(CIO) {
            engine { requestTimeout = 90_000 }
            defaultRequest { bearerAuth("test-inference-token") }
        }
        val port: Int get() = head.port
        val head = HeadServer(
            provider = responses(watchdog, upstreamUrl ?: mock.baseUrl)
                .let { if (finishing) FinishingProvider(it) else it },
            listenPort = 0,
            deps = headDeps(
                tmp = tmp,
                upstream = UpstreamClient(totalTimeoutMs = 900_000),
                gate = gate,
                seams = HeadDeps.HeadSeams(waiter = waiter),
                log = { line ->
                    if (line.contains("with the restart line")) cutAt.compareAndSet(0L, System.nanoTime())
                },
            ).copy(policy = HeadDeps.HeadPolicy(stopDrainMs = stopDrainMs)),
        )

        private fun responses(watchdog: WatchdogBudget, baseUrl: String): Provider =
            TestResponsesProvider(
                tuning = ProviderTuning(
                    name = ProviderName(key = "codex", label = "claudex"),
                    catalog = ModelCatalog(
                        discoveryPrefix = "claude-codex--",
                        models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                        defaultContextWindow = 272_000,
                    ),
                    pinnedModel = "gpt-5.6-sol",
                    auth = DrainFakeAuth(),
                    locations = ProviderLocations(baseUrl = baseUrl),
                    watchdog = watchdog,
                    loginCommand = "claudex login",
                ),
                reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
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
        suspend fun heldTurn(stream: Boolean = true): HttpResponse =
            client.post("http://127.0.0.1:$port/v1/messages") {
                header("Content-Type", "application/json")
                setBody(heldBody(stream))
            }

        /** [heldTurn] as the bytes of one HTTP/1.1 request, for a test that holds the connection itself. */
        fun heldRequest(): String = heldBody(stream = true).let { body ->
            "POST /v1/messages HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
                "Authorization: Bearer test-inference-token\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${body.toByteArray().size}\r\n\r\n$body"
        }

        private fun heldBody(stream: Boolean, scenario: String = "hold") =
            """{"model":"claude-codex--gpt-5.6-sol","stream":$stream,"max_tokens":64,
                "system":"You are a test. SCENARIO:$scenario",
                "messages":[{"role":"user","content":"go"}]}"""

        /** A streamed turn whose upstream has sent nothing yet (SCENARIO:holdstart), read to its end. */
        suspend fun unansweredStream(): String =
            client.post("http://127.0.0.1:$port/v1/messages") {
                header("Content-Type", "application/json")
                setBody(heldBody(stream = true, scenario = "holdstart"))
            }.bodyAsText()

        /** The held turn read to its END as its status and body: a streamed one through [heldStream], a buffered one
         *  through [heldTurn]. */
        suspend fun heldRead(stream: Boolean, reached: CompletableDeferred<Unit>): Pair<Int, String> =
            if (stream) heldStream(reached) else heldTurn(false).let { it.status.value to it.bodyAsText() }

        /** The held turn as a client reads a stream, to its END like [heldTurn]: [reached] completes when the first
         *  content delta arrives, so a test that cuts the turn cuts one whose content the client holds. A slot held
         *  says only that the turn was admitted: the line a cut writes names where the stream stood (content reached
         *  or not), and the upstream's first delta may still be on its way when the slot is first seen taken. */
        suspend fun heldStream(
            reached: CompletableDeferred<Unit>,
            body: String = heldBody(stream = true),
            session: String? = null,
        ): Pair<Int, String> =
            client.preparePost("http://127.0.0.1:$port/v1/messages") {
                header("Content-Type", "application/json")
                session?.let { header("x-claude-code-session-id", it) }
                setBody(body)
            }.execute { response ->
                val channel = response.bodyAsChannel()
                val body = StringBuilder()
                while (true) {
                    val line = channel.readLine() ?: break
                    body.append(line).append('\n')
                    if (line.startsWith("event: content_block_delta")) reached.complete(Unit)
                }
                response.status.value to body.toString()
            }

        /** [heldStream] for a compaction: Claude Code's summarizer marker makes the turn one (CompactClassifier), and
         *  with a session it runs off its client's call, as every compaction does. */
        suspend fun heldCompaction(reached: CompletableDeferred<Unit>): Pair<Int, String> = heldStream(
            reached,
            """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,""" +
                """"system":"SCENARIO:hold You are tasked with summarizing conversations for another agent.",""" +
                """"messages":[{"role":"user","content":"compact"}]}""",
            session = "sess-compaction",
        )

        suspend fun awaitUpstreamAsked(asked: AtomicBoolean): Boolean {
            repeat(50) {
                if (asked.get()) return true
                delay(INFLIGHT_POLL_MS)
            }
            return asked.get()
        }

        suspend fun awaitReleased(): Boolean {
            repeat(500) {
                if (gate.snapshot().inflight == 0) return true
                delay(RELEASE_POLL_MS)
            }
            return gate.snapshot().inflight == 0
        }

        suspend fun awaitInflight(): Boolean {
            repeat(50) {
                if (gate.snapshot().inflight >= 1) return true
                delay(INFLIGHT_POLL_MS)
            }
            return gate.snapshot().inflight >= 1
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a turn beyond the drain receives a restart overload before its socket closes`(
        stream: Boolean,
        @TempDir tmp: Path,
    ) = runBlocking {
        val rig = Rig(tmp, WatchdogBudget(600.seconds, 600.seconds, 900.seconds), stopDrainMs = SHORT_DRAIN_MS)
        rig.start()
        try {
            val reached = CompletableDeferred<Unit>()
            val turn = async(Dispatchers.IO) { rig.heldRead(stream, reached) }
            assertTrue(rig.awaitInflight(), "precondition: a real client still holds this request")
            // The streamed turn is cut after its deltas reached the client (RESTART_SAID's notes above), so the cut
            // waits for the first one: a held slot only says the turn was admitted.
            if (stream) withTimeout(CONTENT_WAIT_MS) { reached.await() }
            val began = System.nanoTime()
            val stopping = async(Dispatchers.IO) { rig.head.stop() }
            val (status, body) = turn.await()
            val receivedAt = System.nanoTime()
            stopping.await()
            val stoppedAt = System.nanoTime()
            assertTrue(rig.cutAt.get() > began, "the measured cut must follow the drain")
            println(
                "stream=$stream restart_cut_terminal_ns=${receivedAt - rig.cutAt.get()} stop_ns=${stoppedAt - began}",
            )
            assertEquals(if (stream) 200 else 529, status)
            assertTrue(body.contains("overloaded_error"), "a connected client must receive an automatic-retry error")
            val next = if (stream) PERSON_RESENDS else CLIENT_RESENDS
            assertTrue(body.contains("$RESTART_SAID $next"), "the terminal must name the owner of the cut: $body")
            assertTrue(!body.contains(CONSOLE_STOP), "a restart never impersonates a user stop")
            assertTrue(AsyncFileIo.drain())
            val outcome = Files.readAllLines(tmp.resolve("perf.jsonl"))
                .map { Json.parseToJsonElement(it).jsonObject }
                .single()["outcome"]?.jsonPrimitive?.content
            assertEquals(OutcomeTag.RESTARTED.wire, outcome)
            assertTrue(OutcomeTags.isFailed(outcome.orEmpty()))
            assertTrue(!OutcomeTags.isStopped(outcome.orEmpty()))
            rig.mock.releaseHold()
            rig.head.start()
            awaitListening(rig.port)
            val retry = rig.heldTurn(stream)
            val retryBody = retry.bodyAsText()
            assertEquals(
                200,
                retry.status.value,
                "the same request is accepted after restart without an operator stop mark",
            )
            assertTrue(!retryBody.contains("overloaded_error"), "the restarted driver must accept new turn jobs")
            assertTrue(retryBody.contains(if (stream) "message_stop" else "stop_reason"))
        } finally {
            rig.mock.releaseHold()
            rig.close()
        }
    }

    // Eli, Oct 28 (Simon's report): restart drains moved Haiku and Sonnet sessions onto their Opus fallbacks for good.
    // Claude Code 2.1.312 moves a session with a fallback model onto it after three overloaded answers in a row, and at
    // once after any other 5xx, but it never changes model on a connection error, which it retries ten times. A request
    // that reaches a draining head therefore meets a closed connection and no HTTP answer at all, and its retry reaches
    // the next daemon. Nothing reached the upstream for it, so nothing is lost or done twice.
    @Test
    fun `a request that reaches a draining head meets a closed connection and no HTTP answer`(
        @TempDir tmp: Path,
    ): Unit = runBlocking {
        val rig = Rig(tmp, WatchdogBudget(600.seconds, 600.seconds, 900.seconds), stopDrainMs = DRAIN_HOLD_MS)
        rig.start()
        try {
            val held = async(Dispatchers.IO) { runCatching { rig.heldTurn().bodyAsText() } }
            assertTrue(rig.awaitInflight(), "precondition: a held turn keeps the head draining")
            val stopping = async(Dispatchers.IO) { rig.head.stop() }
            delay(DRAINING_SETTLE_MS)
            val answer = rawRequest(rig.port)
            assertEquals("", answer, "a draining head must answer with no status at all, got: ${answer.take(200)}")
            rig.mock.releaseHold()
            stopping.await()
            held.await()
        } finally {
            rig.mock.releaseHold()
            rig.close()
        }
    }

    // #523: a restart used to cut every running turn. Claude Code does not send again a turn cut after its answer
    // began, so that session stopped until someone typed into it: 8 of the 14 such cuts on one machine, Nov 10 to 12,
    // 2026. A retiring head now finishes the answers in progress while the next daemon takes everything else.
    @Test
    fun `a retiring head finishes the answer in progress and turns a new request away`(
        @TempDir tmp: Path,
    ): Unit = runBlocking {
        val rig = Rig(tmp, WatchdogBudget(600.seconds, 600.seconds, 900.seconds), finishing = true)
        rig.start()
        try {
            val reached = CompletableDeferred<Unit>()
            val turn = async(Dispatchers.IO) { rig.heldStream(reached).second }
            withTimeout(CONTENT_WAIT_MS) { reached.await() }

            assertEquals(1, rig.head.retire(), "the turn already answering is kept")
            val answer = rawRequest(rig.port)
            assertEquals("", answer, "a retired head answers a new request with no status: ${answer.take(200)}")

            rig.mock.releaseHold()
            val body = turn.await()
            assertTrue(body.contains("message_stop"), "the client reads its answer to the end: ${body.takeLast(300)}")
            assertTrue(!body.contains("event: error"), "no error replaces the rest of the answer")
            assertTrue(!body.contains(RESTART_SAID), "the turn was not cut")
            withTimeout(TAKEOVER_BOUND_MS) { rig.head.finish(TAKEOVER_BOUND_MS) }
        } finally {
            rig.mock.releaseHold()
            rig.close()
        }
    }

    // #525: the answer a retiring head finishes went out on a connection its client keeps open, and the client's next
    // request arrived on it, at the head that was leaving: turned away unanswered, sent again, and only then served by
    // the next daemon (five such requests at one restart, Nov 13, 2026). The head closes the connection when the
    // answer has ended, so the next request opens a new one.
    @Test
    fun `a retiring head closes the connection of an answer it finished`(@TempDir tmp: Path): Unit = runBlocking {
        val rig = Rig(tmp, WatchdogBudget(600.seconds, 600.seconds, 900.seconds), finishing = true)
        rig.start()
        try {
            Socket("127.0.0.1", rig.port).use { socket ->
                socket.soTimeout = RAW_READ_TIMEOUT_MS
                socket.getOutputStream().apply {
                    write(rig.heldRequest().toByteArray())
                    flush()
                }
                val answer = socket.getInputStream().bufferedReader()
                val began = generateSequence(answer::readLine).first { it.startsWith("event: content_block_delta") }
                assertTrue(began.isNotEmpty(), "the answer is on its way before the head retires")

                assertEquals(1, rig.head.retire(), "the turn already answering is kept")
                rig.mock.releaseHold()

                // Reads to the end of the connection: one the head left open ends this read on the socket's timeout.
                val rest = answer.readText()
                assertTrue(rest.contains("message_stop"), "the answer is read to its end: ${rest.takeLast(300)}")
            }
            withTimeout(TAKEOVER_BOUND_MS) { rig.head.finish(TAKEOVER_BOUND_MS) }
        } finally {
            rig.mock.releaseHold()
            rig.close()
        }
    }

    // #525: a compaction runs off its client's call, on the head's detached scope, and a retiring head ended that
    // whole scope: on Nov 12, 2026 a restart cancelled a compaction 77 seconds into its answer, and the client's retry
    // took 402 seconds. One that is answering a client still connected is kept like any other turn.
    @Test
    fun `a retiring head finishes a compaction that is already answering its client`(
        @TempDir tmp: Path,
    ): Unit = runBlocking {
        val rig = Rig(tmp, WatchdogBudget(600.seconds, 600.seconds, 900.seconds), finishing = true)
        rig.start()
        try {
            val reached = CompletableDeferred<Unit>()
            val turn = async(Dispatchers.IO) { rig.heldCompaction(reached).second }
            withTimeout(CONTENT_WAIT_MS) { reached.await() }

            assertEquals(1, rig.head.retire(), "the compaction already answering is kept")

            rig.mock.releaseHold()
            val body = turn.await()
            assertTrue(body.contains("message_stop"), "the client reads its summary to the end: ${body.takeLast(300)}")
            assertTrue(!body.contains("event: error"), "no error replaces the rest of the summary")
            assertTrue(!body.contains(RESTART_SAID), "the compaction was not cut")
            withTimeout(TAKEOVER_BOUND_MS) { rig.head.finish(TAKEOVER_BOUND_MS) }
        } finally {
            rig.mock.releaseHold()
            rig.close()
        }
    }

    @Test
    fun `a retiring head still cuts a turn its upstream has not begun answering, which its client sends again`(
        @TempDir tmp: Path,
    ): Unit = runBlocking {
        val rig = Rig(tmp, WatchdogBudget(600.seconds, 600.seconds, 900.seconds), finishing = true)
        rig.mock.resetStartHold()
        rig.start()
        try {
            val turn = async(Dispatchers.IO) { rig.unansweredStream() }
            assertTrue(rig.awaitInflight(), "precondition: the turn holds a slot and waits on its upstream")

            assertEquals(0, rig.head.retire(), "a turn with no answer yet is not kept")
            val body = withTimeout(TAKEOVER_BOUND_MS) { turn.await() }
            assertTrue(body.contains("$RESTART_SAID $CLIENT_RESENDS"), "the client is told it sends again: $body")
        } finally {
            rig.mock.startHoldRelease.countDown()
            rig.close()
        }
    }

    /** POST /v1/messages over a bare socket: every byte the head wrote back before it closed the connection. */
    private fun rawRequest(port: Int): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = RAW_READ_TIMEOUT_MS
        val body = """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":8,""" +
            """"messages":[{"role":"user","content":"go"}]}"""
        val request = "POST /v1/messages HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
            "Authorization: Bearer test-inference-token\r\nContent-Type: application/json\r\n" +
            "Content-Length: ${body.toByteArray().size}\r\n\r\n$body"
        socket.getOutputStream().apply {
            write(request.toByteArray())
            flush()
        }
        socket.getInputStream().readAllBytes().toString(Charsets.UTF_8)
    }

    @Test
    fun `an empty shutdown never waits for a cancellation seal`(@TempDir tmp: Path) = runBlocking {
        val rig = Rig(tmp, waiter = Waiter { error("an empty head must not wait") })
        rig.start()
        try {
            val began = System.nanoTime()
            rig.head.stop()
            println("empty_stop_ns=${System.nanoTime() - began}")
            assertEquals(0, rig.gate.snapshot().inflight)
        } finally {
            rig.close()
        }
    }

    // Marcos, Oct 29, 2026: "we already send a message saying the server is restarting and so the user knows about
    // it, then we just have the new daemon take over". A restart cuts a running turn at once with the restart line,
    // under the head's own default policy, so the next daemon can take over in seconds. The old 45s wait cut every
    // turn longer than itself anyway, and bounced every new request while it waited (155 at the 9:48 PM CT install
    // of Oct 28).
    @Test
    fun `a restart hands a running turn the restart line at once instead of waiting for it`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val rig = Rig(tmp)
        rig.start()
        try {
            val reached = CompletableDeferred<Unit>()
            val turn = async(Dispatchers.IO) { rig.heldStream(reached).second }
            assertTrue(rig.awaitInflight(), "precondition: the turn must be holding a slot")
            // The restart line names where the stream stood, and this one is cut after content reached the client:
            // the slot alone says only that the turn was admitted.
            withTimeout(CONTENT_WAIT_MS) { reached.await() }

            val began = System.nanoTime()
            val stopping = async(Dispatchers.IO) { rig.head.stop() }
            val body = turn.await() // a torn socket fails HERE, by throwing
            val receivedMs = (System.nanoTime() - began) / 1_000_000
            stopping.await()
            val stoppedMs = (System.nanoTime() - began) / 1_000_000

            assertTrue(body.contains("$RESTART_SAID $PERSON_RESENDS"), "the client must be told: ${body.take(300)}")
            assertTrue(receivedMs < TAKEOVER_BOUND_MS, "the restart line took $receivedMs ms to reach the client")
            assertTrue(stoppedMs < TAKEOVER_BOUND_MS, "the stop took $stoppedMs ms, so the next daemon waited")
        } finally {
            rig.mock.releaseHold()
            rig.close()
        }
    }

    // Regression for "a stop never cuts a response mid-write". The upstream answers with a burst the head
    // paces out over up to a second, so the gate slot has released while the tail is still being written.
    // The drain used to wait on the gate alone, so the engine stopped here and cut the client's body.
    // Under a machine busy enough to delay this thread past the pacing window the tail is already written when the
    // stop begins; the row then passes without exercising the window, and never fails falsely.
    @Test
    fun `a stop waits for a response still being written after its turn released the gate`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val burst = BurstUpstream()
        val rig = Rig(tmp, upstreamUrl = burst.baseUrl)
        rig.start()
        try {
            val turn = async(Dispatchers.IO) { rig.heldTurn().bodyAsText() }
            assertTrue(rig.awaitUpstreamAsked(burst.asked), "precondition: the turn reached the upstream")
            assertTrue(rig.awaitReleased(), "precondition: the upstream ended and the gate slot released")

            rig.head.stop()

            val body = turn.await()
            assertTrue(body.contains("message_stop"), "the client must get the whole body, tail included")
            assertTrue(!body.contains("event: error"), "no error may replace the tail")
        } finally {
            rig.close()
            burst.close()
        }
    }
}
