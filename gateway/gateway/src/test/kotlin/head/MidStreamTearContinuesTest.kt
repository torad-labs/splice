// NEW: the MID-STREAM TEAR wall (V4-67). A real HeadServer on the Anthropic-passthrough dialect, a
// real upstream socket that DIES mid-response, and every assertion made on the BYTES THE CLIENT
// RECEIVES — never on splice's internal call counts, because the campaign's root-cause lesson is
// that policy-mirroring tests ratified every regression they were written to prevent.
//
// The upstream here is a raw ServerSocket rather than com.sun.net.httpserver, for one reason: only
// raw sockets can express the failure under test. A chunked response cut off before its terminating
// zero-chunk is a TEAR (the reader throws); a clean close is an EOF (the reader returns -1), and
// the EOF half is already walled by the re-anchor tests. An RST (SO_LINGER 0) is a third shape
// again — the SocketException class G5 is allowed to re-issue on.
//
// THE FOUR ARMS, and what each one is for:
//   1. tear AFTER content, head WITH reanchor_prefill -> one coherent message, ONE terminal, the
//      remainder APPENDED. This is the operator's measured failure (2026-09-16, claude-deepseek).
//   2. the SAME tear, head WITHOUT the quirk -> exactly today's ending, an honest error event and
//      no continuation request. NEVER BELOW STATUS QUO is a test, not a promise: muse rejects
//      assistant prefill, so its honest error must survive every change made for deepseek/kimi.
//   3. a tear BEFORE any content frame -> recovers through the G5 stream reissue inside
//      UpstreamClient, which sits BELOW this seam and must keep its case. A conversion at the
//      round boundary cannot divert G5 (G5 decides first, and only what it declines is ever
//      thrown), so what this arm pins is that the pre-content case still ends where it ends today.
//   4. a client that hangs up mid-stream is not a tear: the turn must not manufacture a
//      continuation round for a reader that has gone, and the upstream must see no second POST.
//   5. a tear before content that the G5 interlock REFUSES (its allowlist has six transport names
//      in it and a torn chunk stream is none of them) — the case that had no retry at all, and the
//      one the operator measured. See SseRoundDriver.tearOutcome.
//   6. the same refused tear on the head without the quirk: it recovers by a VERBATIM restart, so
//      a vendor that rejects assistant prefill can never be handed one by this path.
//
// ARMS 1-4 PASSED BEFORE SseRoundDriver.tearOutcome EXISTED, and that is a finding, not an
// accident: the row that opened this work said a post-content tear escapes the re-anchor loop,
// and the run says it does not — the translator's own IOException catch already reports it as a
// truncation carrying its partial (V4-41). Arms 5 and 6 are the half that was genuinely blind.
package head

import campaign.v4105.headDeps
import campaign.v4105.headStores
import kotlinx.coroutines.runBlocking
import mock.freshPort
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.CONN_RESET_OUTCOME
import splice.core.turn.WatchdogBudget
import splice.dialect.passthrough.PassthroughProvider
import splice.dialect.passthrough.PassthroughQuirks
import splice.gateway.head.HeadServer
import splice.spi.InflightGate
import splice.spi.ProviderTuning
import splice.spi.UpstreamClient
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** What one upstream connection does with its turn. */
private enum class Act {
    /** 200, two frames the client can SEE, then the socket dies mid-chunk-stream. */
    TEAR_AFTER_CONTENT,

    /** 200 + message_start (a structural frame, never content), then an RST. */
    RESET_BEFORE_CONTENT,

    /** 200 + message_start, then a torn chunk stream — a tear before content whose exception class
     *  is NOT one the G5 interlock re-issues on. */
    TRUNCATE_BEFORE_CONTENT,

    /** A complete, well-formed answer ending in message_stop. */
    FULL,

    /** Content, then hold the connection open — the arm where the CLIENT is the one that leaves. */
    HOLD_AFTER_CONTENT,

    /** V4-116 (3): real content, then an RST. The tear KIND the census listed as never pinned:
     *  every other post-content shape here dies by a FIN or by a torn chunk, while this one kills
     *  the socket outright, and the reader sees a SocketException rather than an EOF. */
    RESET_AFTER_CONTENT,
}

private const val TEAR_DELIVERY_PAUSE_MS = 250L

// One more tear than PassthroughReanchorController's continuation budget, so the turn runs the
// whole re-anchor loop out and finishes with the honest failure instead of recovering.
private const val TEARS_PAST_BUDGET = 7
private const val PERF_POLLS = 40
private const val PERF_POLL_MS = 50L

private const val HOLD_MS = 6_000L

/** V4-116: the armed mid-output stall-re-anchor tier the stall arms run under. A reply inside a
 *  few multiples of this can only have come from the tier — the heads' streamIdle is 120_000ms. */
private const val STALL_TIER_MS = 1_000L
private const val MS_PER_NANO = 1_000_000L

// The perf row is JSON, so the tag is asserted as its own FIELD — and the tag itself comes from
// the one definition in core, never a second spelling here. A test that re-spells the string it
// is pinning cannot catch the string changing.
private const val PERF_OUTCOME_FIELD = "\"outcome\":\""
private const val QUOTE = "\""

// V4-116 (5): the two evidence fields, asserted by their rendered name so a renamed key cannot
// pass this arm while leaving the operator grepping for a key that no longer exists.
private const val REANCHORS_FIELD = "\"reanchors\":"
private val STALL_MS_RE = Regex("\"stall_ms\":(\\d+)")

/** An Anthropic-shaped upstream that can die mid-response. [acts] is consumed by request index, the
 *  last entry repeating, so a test states the whole conversation up front. Request BODIES are
 *  recorded because what the upstream RECEIVED is an observation about the wire, not about splice's
 *  internals — it is how "the continuation APPENDED rather than replayed" is checked. */
private class TearingAnthropicUpstream {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val baseUrl: String = "http://127.0.0.1:${server.localPort}"
    val requestBodies = CopyOnWriteArrayList<String>()

    @Volatile
    var acts: List<Act> = listOf(Act.FULL)

    fun start() {
        Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: return@Thread
                Thread { serve(socket) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() {
        runCatching { server.close() }
    }

    private fun serve(socket: Socket) {
        socket.tcpNoDelay = true
        val body = runCatching { readRequest(socket.getInputStream()) }.getOrNull() ?: return
        val index = requestBodies.size
        requestBodies.add(body)
        val act = acts.getOrElse(index) { acts.last() }
        val out = socket.getOutputStream()
        runCatching { respond(socket, out, act) }
        runCatching { socket.close() }
    }

    private fun respond(socket: Socket, out: OutputStream, act: Act) {
        out.write(
            (
                "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
                    "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                ).toByteArray(),
        )
        out.flush()
        when (act) {
            Act.TEAR_AFTER_CONTENT -> {
                out.chunk(openingFrames() + delta(FIRST_HALF))
                Thread.sleep(TEAR_DELIVERY_PAUSE_MS)
                // Cut INSIDE a chunk whose header promised more bytes than were sent. A missing
                // zero-chunk alone can be read leniently as an end of body; a half-delivered chunk
                // cannot be mistaken for anything but a torn stream, which is the whole point of
                // this arm — an EOF is the case the re-anchor tests already cover.
                out.write("ff\r\n".toByteArray())
                out.write("event: content_bl".toByteArray())
                out.flush()
                Thread.sleep(TEAR_DELIVERY_PAUSE_MS)
            }
            Act.TRUNCATE_BEFORE_CONTENT -> {
                out.chunk(frame("message_start", MESSAGE_START))
                Thread.sleep(TEAR_DELIVERY_PAUSE_MS)
                out.write("ff\r\n".toByteArray())
                out.write("event: content_bl".toByteArray())
                out.flush()
                Thread.sleep(TEAR_DELIVERY_PAUSE_MS)
            }
            Act.RESET_BEFORE_CONTENT -> {
                out.chunk(frame("message_start", MESSAGE_START))
                Thread.sleep(TEAR_DELIVERY_PAUSE_MS)
                socket.setSoLinger(true, 0) // close() now sends RST, not FIN
            }
            Act.FULL -> {
                out.chunk(openingFrames() + delta(SECOND_HALF) + closingFrames())
                out.write("0\r\n\r\n".toByteArray())
                out.flush()
            }
            Act.HOLD_AFTER_CONTENT -> {
                out.chunk(openingFrames() + delta(FIRST_HALF))
                Thread.sleep(HOLD_MS)
            }
            Act.RESET_AFTER_CONTENT -> {
                out.chunk(openingFrames() + delta(FIRST_HALF))
                Thread.sleep(TEAR_DELIVERY_PAUSE_MS)
                socket.setSoLinger(true, 0) // close() now sends RST, not FIN
            }
        }
    }

    private fun readRequest(input: InputStream): String {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val c = input.read()
            if (c < 0) return ""
            head.append(c.toChar())
        }
        val length = CONTENT_LENGTH.find(head)?.groupValues?.get(1)?.toInt() ?: 0
        val bytes = ByteArray(length)
        var off = 0
        while (off < length) {
            val n = input.read(bytes, off, length - off)
            if (n < 0) break
            off += n
        }
        return String(bytes, Charsets.UTF_8)
    }
}

private val CONTENT_LENGTH = Regex("(?i)content-length:\\s*(\\d+)")

private const val FIRST_HALF = "The answer is "
private const val SECOND_HALF = "forty-two."

private const val MESSAGE_START = """{"type":"message_start","message":{"usage":{"input_tokens":7}}}"""

private fun frame(event: String, data: String): String = "event: $event\ndata: $data\n\n"

private fun openingFrames(): String =
    frame("message_start", MESSAGE_START) +
        frame(
            "content_block_start",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        )

private fun delta(text: String): String =
    frame(
        "content_block_delta",
        """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$text"}}""",
    )

private fun closingFrames(): String =
    frame("content_block_stop", """{"type":"content_block_stop","index":0}""") +
        frame(
            "message_delta",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}""",
        ) +
        frame("message_stop", """{"type":"message_stop"}""")

private fun OutputStream.chunk(payload: String) {
    val bytes = payload.toByteArray()
    write("${bytes.size.toString(16)}\r\n".toByteArray())
    write(bytes)
    write("\r\n".toByteArray())
    flush()
}

private class TearTestAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-tear", "acct-tear")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

private const val INFERENCE_TOKEN = "test-inference-token"

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MidStreamTearContinuesTest {

    private val upstream = TearingAnthropicUpstream()
    private lateinit var tmp: java.nio.file.Path
    private val heads = mutableListOf<HeadServer>()
    private val journal = CopyOnWriteArrayList<String>()

    private var prefillPort = 0
    private var honestPort = 0

    /** V4-116: the two heads with the mid-output stall tier ARMED. Everything else about them is
     *  the same head, so an arm that passes here and fails on [prefillPort] is measuring the tier
     *  and nothing else. 1000ms is far below the 120s streamIdle these heads run under — a reply
     *  inside a couple of seconds can only have come from the stall tier. */
    private var stallPrefillPort = 0
    private var stallHonestPort = 0

    @BeforeAll
    fun setUp() = runBlocking {
        tmp = Files.createTempDirectory("head-mid-stream-tear")
        upstream.start()
        prefillPort = startHead(prefill = true)
        honestPort = startHead(prefill = false)
        stallPrefillPort = startHead(prefill = true, stallMs = STALL_TIER_MS)
        stallHonestPort = startHead(prefill = false, stallMs = STALL_TIER_MS)
        // no warm-up: each head binds before start returns (Ktor Netty bind(...).sync(); V4-139)
    }

    @AfterAll
    fun tearDown() = runBlocking {
        heads.forEach { it.stop() }
        upstream.stop()
    }

    private suspend fun startHead(prefill: Boolean, stallMs: Long? = null): Int {
        val port = freshPort()
        val provider = PassthroughProvider(
            tuning = ProviderTuning(
                key = if (prefill) "deepseek-like" else "muse-like",
                label = "claude-splice",
                catalog = ModelCatalog(
                    discoveryPrefix = "claude-splice--",
                    models = listOf(ModelEntry("claude-fable-5", "Claude Fable 5", contextWindow = 200_000)),
                    defaultContextWindow = 200_000,
                ),
                pinnedModel = "claude-fable-5",
                auth = TearTestAuth(),
                baseUrl = upstream.baseUrl,
                // Deliberately large: no watchdog may fire inside these windows, or the arms
                // measure a stall instead of a tear. [stallMs] arms the V4-116 mid-output
                // stall-re-anchor tier — the ONLY tier small enough to fire here, which is what
                // makes the stall arms below measure the re-anchor rather than the tear.
                watchdog = WatchdogBudget(
                    120.seconds,
                    120.seconds,
                    300.seconds,
                    stallReanchor = stallMs?.milliseconds ?: Duration.INFINITE,
                ),
            ),
            quirks = PassthroughQuirks(
                providerTag = if (prefill) "deepseek-like" else "muse-like",
                reanchorPrefill = prefill,
            ),
            staticHeaders = mapOf("anthropic-version" to "2023-06-01"),
        )
        val head = HeadServer(
            provider = provider,
            listenPort = port,
            deps = headDeps(
                tmp = tmp,
                upstream = UpstreamClient(firstByteTimeoutMs = 10_000, totalTimeoutMs = 60_000, maxRetries = 4),
                gate = InflightGate({ 4 }),
                log = { line -> journal.add(line) },
            ).copy(
                // This rig carries its OWN bearer and keys its store files by port, so both come from
                // the site rather than the fixture's defaults.
                inferenceToken = INFERENCE_TOKEN,
                stores = headStores(tmp, suffix = "-$port"),
            ),
        )
        head.start()
        heads.add(head)
        return port
    }

    /** A raw client socket, exactly the shape HeadServerCollectDisconnectTest uses: a real FIN on
     *  close() with no HTTP-client pool or cancellation semantics in between. */
    private fun openTurn(port: Int): Socket {
        val body = """{"model":"claude-splice--claude-fable-5","stream":true,"max_tokens":64,""" +
            """"messages":[{"role":"user","content":"what is the answer"}]}"""
        val socket = Socket("127.0.0.1", port)
        socket.soTimeout = 60_000
        val request = "POST /v1/messages HTTP/1.1\r\n" +
            "Host: 127.0.0.1:$port\r\n" +
            "Authorization: Bearer $INFERENCE_TOKEN\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${body.toByteArray().size}\r\n" +
            "Connection: close\r\n\r\n" + body
        socket.getOutputStream().write(request.toByteArray())
        socket.getOutputStream().flush()
        return socket
    }

    /** Everything the client receives, to EOF. */
    private fun drainTurn(port: Int): String = openTurn(port).use { socket ->
        val buffer = ByteArray(8 * 1024)
        val seen = StringBuilder()
        while (true) {
            val n = runCatching { socket.getInputStream().read(buffer) }.getOrDefault(-1)
            if (n < 0) break
            seen.append(String(buffer, 0, n, Charsets.UTF_8))
        }
        seen.toString()
    }

    /** The perf row this head wrote for the turn just finished. PerfStats appends through
     *  AsyncFileIo, so it is polled rather than read once — the HeadServerCapacityTest idiom.
     *
     *  Polled for a row that was NOT THERE before the turn, never for "any row": the file is per
     *  head and every arm on the same port appends to it, so "the last row" is the previous arm's
     *  row until this turn's append lands — and it landed late enough once (V4-141 gate of record,
     *  2026-09-20: ARM 7 read ARM 5's `outcome: ok` row while its own `error:conn-reset` row was
     *  still in the writer) for the arm to fail on a row it never wrote. [before] is the count
     *  taken by [perfRowsBefore] ahead of the turn. */
    private fun perfRow(port: Int, before: Int): String {
        repeat(PERF_POLLS) {
            val rows = perfRows(port)
            if (rows.size > before) return rows.last()
            Thread.sleep(PERF_POLL_MS)
        }
        return ""
    }

    private fun perfRowsBefore(port: Int): Int = perfRows(port).size

    private fun perfRows(port: Int): List<String> {
        val file = tmp.resolve("perf-$port.jsonl")
        if (!Files.exists(file)) return emptyList()
        return Files.readString(file).lines().filter { it.isNotBlank() }
    }

    private fun reset(vararg acts: Act) {
        upstream.requestBodies.clear()
        journal.clear()
        upstream.acts = acts.toList()
    }

    private fun occurrences(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1

    private fun diagnostics(received: String): String =
        "\nCLIENT RECEIVED:\n$received\nUPSTREAM REQUESTS: ${upstream.requestBodies.size}\nJOURNAL:\n" +
            journal.joinToString("")

    // ARM 1 — the operator's measured failure. Two content frames reach the client, the upstream
    // socket dies, and the turn must come back as ONE message whose remainder was APPENDED.
    @Test
    fun `a tear after content continues and the client reads one coherent message`() {
        reset(Act.TEAR_AFTER_CONTENT, Act.FULL)
        val received = drainTurn(prefillPort)

        assertTrue(
            received.contains(FIRST_HALF),
            "the frames the client already saw must stand" + diagnostics(received),
        )
        assertTrue(received.contains(SECOND_HALF), "the remainder must be appended" + diagnostics(received))
        assertEquals(
            1,
            occurrences(received, "event: message_stop"),
            "a spliced turn ends in exactly ONE terminal" + diagnostics(received),
        )
        assertFalse(
            received.contains("event: error"),
            "a recovered turn shows the client no error" + diagnostics(received),
        )
        assertEquals(
            1,
            occurrences(received, FIRST_HALF),
            "a proxy cannot un-send bytes: the salvaged text must never be replayed to the client" +
                diagnostics(received),
        )
        assertEquals(2, upstream.requestBodies.size, "the continuation must have been POSTed" + diagnostics(received))
        assertTrue(
            upstream.requestBodies[1].contains(FIRST_HALF.trimEnd()),
            "the continuation carries the salvaged text as an assistant prefill" + diagnostics(received),
        )
    }

    // ARM 2 — NEVER BELOW STATUS QUO. The same tear on a head that has NOT been measured to accept
    // an assistant prefill keeps the honest error: an upstream that refuses the prefill shape would
    // answer 400 invalid_request_error, which Claude Code does not retry at all.
    @Test
    fun `the same tear on a head without the prefill quirk keeps the honest error`() {
        reset(Act.TEAR_AFTER_CONTENT, Act.FULL)
        val received = drainTurn(honestPort)

        assertTrue(received.contains("event: error"), "the honest ending is an error event" + diagnostics(received))
        assertTrue(
            received.contains("overloaded_error"),
            "and it stays the retryable type the client understands" + diagnostics(received),
        )
        assertEquals(
            0,
            occurrences(received, "event: message_stop"),
            "an errored turn never claims a clean terminal" + diagnostics(received),
        )
        assertEquals(
            1,
            upstream.requestBodies.size,
            "no continuation may be sent to an upstream that has not been measured to accept one" +
                diagnostics(received),
        )
    }

    // ARM 3 — a tear BEFORE any content frame belongs to G5, which lives one layer below this seam.
    // message_start is structural, never content, so the client has seen nothing it could be shown
    // twice and the request is safely re-issued whole.
    @Test
    fun `a tear before any content frame still recovers through the stream reissue`() {
        reset(Act.RESET_BEFORE_CONTENT, Act.FULL)
        val received = drainTurn(prefillPort)

        assertTrue(received.contains(SECOND_HALF), "the re-issued round answers the turn" + diagnostics(received))
        assertEquals(
            1,
            occurrences(received, "event: message_stop"),
            "one terminal across the reissue" + diagnostics(received),
        )
        assertFalse(received.contains("event: error"), "no error reaches the client" + diagnostics(received))
        assertEquals(2, upstream.requestBodies.size, "the request was re-issued once" + diagnostics(received))
        assertEquals(
            upstream.requestBodies[0],
            upstream.requestBodies[1],
            "a pre-content reissue re-POSTs the ORIGINAL body — it is not a continuation" + diagnostics(received),
        )
    }

    // ARM 5 — THE OPERATOR'S TURN. A tear before the first content frame whose exception class the
    // G5 interlock refuses to re-issue on: measured against unmodified code, this ended the turn at
    // attempts=1 with `turn ERROR conn-reset ... : stream torn before first client frame`, one
    // upstream request, and every retry budget unspent — the same shape as claude-deepseek session
    // 4b09e038 on 2026-09-16. Nothing was ever shown to the client, so the restart duplicates
    // nothing.
    @Test
    fun `a tear the reissue interlock refuses is continued instead of ending the turn`() {
        reset(Act.TRUNCATE_BEFORE_CONTENT, Act.FULL)
        val received = drainTurn(prefillPort)

        assertTrue(received.contains(SECOND_HALF), "the turn must come back with an answer" + diagnostics(received))
        assertEquals(
            1,
            occurrences(received, "event: message_stop"),
            "one terminal for the whole turn" + diagnostics(received),
        )
        assertFalse(
            received.contains("event: error"),
            "a turn that recovered shows the client no error" + diagnostics(received),
        )
        assertEquals(2, upstream.requestBodies.size, "the round was re-POSTed once" + diagnostics(received))
    }

    // ARM 6 — the same tear on the head that REJECTS assistant prefill. It recovers too, and it
    // must recover by a VERBATIM restart: the request the upstream receives the second time has to
    // be byte-identical to the first, or muse answers the recovery with a 400 invalid_request_error
    // that Claude Code will not retry — strictly worse than the honest error this row may not go
    // below.
    @Test
    fun `the refused tear restarts verbatim on a head that rejects prefill`() {
        reset(Act.TRUNCATE_BEFORE_CONTENT, Act.FULL)
        val received = drainTurn(honestPort)

        assertTrue(received.contains(SECOND_HALF), "the turn must come back with an answer" + diagnostics(received))
        assertFalse(received.contains("event: error"), "no error reaches the client" + diagnostics(received))
        assertEquals(2, upstream.requestBodies.size, "the round was re-POSTed once" + diagnostics(received))
        assertEquals(
            upstream.requestBodies[0],
            upstream.requestBodies[1],
            "an unmeasured vendor gets its OWN request back, never a prefill" + diagnostics(received),
        )
    }

    // ARM 7 — THE TAG. A converted tear that never recovers (every continuation tears too, until
    // the controller budget is spent) must still be RECORDED as conn-reset. That tag is the only
    // string in the live journal that names this failure class: it is what the operator grepped to
    // find this bug at all, so a fix that renamed it would have hidden its own successor. The
    // client bytes and the health attribution are the ones this row already pins elsewhere; this
    // arm pins the perf row alone.
    @Test
    fun `an unrecovered converted tear is still recorded as conn-reset`() {
        val tears = Array(TEARS_PAST_BUDGET) { Act.TRUNCATE_BEFORE_CONTENT }
        reset(*tears)
        val before = perfRowsBefore(prefillPort)
        val received = drainTurn(prefillPort)

        assertTrue(
            received.contains("overloaded_error"),
            "the client still reads the honest retryable error" + diagnostics(received),
        )
        val row = perfRow(prefillPort, before)
        assertTrue(
            row.contains(PERF_OUTCOME_FIELD + CONN_RESET_OUTCOME + QUOTE),
            "an unrecovered tear must stay greppable as conn-reset, got: " + row + diagnostics(received),
        )
    }

    // ARM 4 — cancellation is not a tear. The client hangs up mid-stream; nothing may turn that
    // into a continuation round, which would be upstream spend with no reader.
    @Test
    fun `a client that hangs up mid-stream never produces a continuation round`() {
        reset(Act.HOLD_AFTER_CONTENT, Act.FULL)
        val socket = openTurn(prefillPort)
        val buffer = ByteArray(4 * 1024)
        val seen = StringBuilder()
        while (!seen.contains(FIRST_HALF)) {
            val n = socket.getInputStream().read(buffer)
            if (n < 0) break
            seen.append(String(buffer, 0, n, Charsets.UTF_8))
        }
        assertTrue(seen.contains(FIRST_HALF), "precondition: the client must be mid-stream$seen")
        socket.close()

        // Long enough for a continuation to have been POSTed if one were ever going to be: the
        // upstream is still holding the first connection open for HOLD_MS.
        Thread.sleep(HOLD_MS + 1_500)
        assertEquals(
            1,
            upstream.requestBodies.size,
            "a hang-up must not buy the upstream another round" + diagnostics(seen.toString()),
        )
    }

    // ARM 8 — THE SCAR (V4-116). claude-deepseek session b10459ba streamed 3810 content frames,
    // went silent, and sat the WHOLE 300s mid-output tier before ending a turn that was continuable
    // the entire time — because the watchdog's Failure carried no `partial`, so the controller's
    // first line (`round.failure.partial ?: return null`) answered before any eligibility rule was
    // read. A stall and a truncation are the same fact; this arm is the proof that they now end the
    // same way. The harness is the tear one deliberately: ONLY the failure's origin changed, so a
    // difference in the client's bytes would be a difference the salvage caused.
    @Test
    fun `a mid-output stall resumes from the salvage and the client reads one coherent message`() {
        reset(Act.HOLD_AFTER_CONTENT, Act.FULL)
        val started = System.nanoTime()
        val received = drainTurn(stallPrefillPort)
        val elapsedMs = (System.nanoTime() - started) / MS_PER_NANO

        assertTrue(
            received.contains(FIRST_HALF),
            "the frames the client already saw must stand" + diagnostics(received),
        )
        assertTrue(received.contains(SECOND_HALF), "the stall must be RESUMED, not ended" + diagnostics(received))
        assertEquals(
            1,
            occurrences(received, "event: message_stop"),
            "a spliced turn ends in exactly ONE terminal" + diagnostics(received),
        )
        assertFalse(
            received.contains("event: error"),
            "a recovered stall shows the client no error" + diagnostics(received),
        )
        assertEquals(
            1,
            occurrences(received, FIRST_HALF),
            "a proxy cannot un-send bytes: the salvaged text must never be replayed" + diagnostics(received),
        )
        assertEquals(2, upstream.requestBodies.size, "the continuation must have been POSTed" + diagnostics(received))
        assertTrue(
            upstream.requestBodies[1].contains(FIRST_HALF.trimEnd()),
            "the continuation carries the salvage as an assistant prefill" + diagnostics(received),
        )
        assertTrue(
            elapsedMs < STALL_TIER_MS * 4,
            "the turn ended on the ${STALL_TIER_MS}ms STALL tier, not the 120s streamIdle — " +
                "otherwise this arm would pass on unmodified code: took ${elapsedMs}ms" + diagnostics(received),
        )
    }

    // ARM 9 — NEVER BELOW STATUS QUO, the stall half. The same silence on a head that has NOT been
    // measured to accept an assistant prefill keeps the honest error: there is no continuation to
    // reap INTO, so reaping early could only ever cost a slow-but-alive generation. This is the
    // "streamIdle stays the hard floor for prefill-off providers" clause as a test — the tier FIRES
    // (the reply is seconds, not 120s) and the controller declines, which is the whole difference
    // between the two heads.
    @Test
    fun `the same stall on a head without the prefill quirk keeps the honest error`() {
        reset(Act.HOLD_AFTER_CONTENT, Act.FULL)
        val started = System.nanoTime()
        val received = drainTurn(stallHonestPort)
        val elapsedMs = (System.nanoTime() - started) / MS_PER_NANO

        assertTrue(received.contains("event: error"), "the honest ending is an error event" + diagnostics(received))
        assertTrue(
            received.contains("overloaded_error"),
            "and it stays the retryable type the client understands" + diagnostics(received),
        )
        assertEquals(
            0,
            occurrences(received, "event: message_stop"),
            "an errored turn never claims a clean terminal" + diagnostics(received),
        )
        assertEquals(
            1,
            upstream.requestBodies.size,
            "no continuation may be sent to an upstream that has not been measured to accept one" +
                diagnostics(received),
        )
        assertTrue(
            elapsedMs < STALL_TIER_MS * 4,
            "the tier must still have FIRED — took ${elapsedMs}ms, so this arm is not proving the " +
                "20s-vs-300s distinction" + diagnostics(received),
        )
    }

    // ARM 10 — V4-116 (3), the tear KIND the census could not find. Every post-content shape above
    // dies by a FIN or by a torn chunk; an RST kills the socket outright and the reader sees a
    // SocketException instead of an EOF. The claim under test is that this kind is not special:
    // the translator's IOException catch already turns it into a truncation WITH its partial, so a
    // post-content reset reaches the same re-anchor. If it does not, this is the arm that says so.
    @Test
    fun `a reset after content continues like the torn chunk it is`() {
        reset(Act.RESET_AFTER_CONTENT, Act.FULL)
        val received = drainTurn(prefillPort)

        assertTrue(received.contains(FIRST_HALF), "the delivered prefix must stand" + diagnostics(received))
        assertTrue(
            received.contains(SECOND_HALF),
            "an RST after content must reach the SAME re-anchor a torn chunk does" + diagnostics(received),
        )
        assertEquals(
            1,
            occurrences(received, "event: message_stop"),
            "one terminal across the re-anchor" + diagnostics(received),
        )
        assertFalse(received.contains("event: error"), "no error reaches the client" + diagnostics(received))
        assertEquals(2, upstream.requestBodies.size, "the continuation must have been POSTed" + diagnostics(received))
    }

    // ARM 11 — V4-116 (5), THE EVIDENCE ROW. The next incident must be answerable from the perf row
    // ALONE, which is what the operator could not do for session b10459ba: the row said `attempts=1`
    // and nothing distinguished "never retried" from "retried and gave up", nor said how long the
    // upstream had actually been silent before the proxy gave up waiting.
    //
    // Asserted on the ROW rather than on a counter object deliberately: the JSONL is the artifact
    // the operator greps, and a counter that is set but never rendered would pass a unit test and
    // still leave the incident unanswerable.
    @Test
    fun `a spent re-anchor stamps reanchors and the stall it was reaped on`() {
        reset(Act.HOLD_AFTER_CONTENT, Act.FULL)
        val before = perfRowsBefore(stallPrefillPort)
        val received = drainTurn(stallPrefillPort)
        assertTrue(
            received.contains(SECOND_HALF),
            "precondition: this arm only means anything if the stall WAS re-anchored" + diagnostics(received),
        )

        val row = perfRow(stallPrefillPort, before)
        assertTrue(
            row.contains(REANCHORS_FIELD + "1"),
            "the spent continuation must be countable from the row, got: " + row,
        )
        val stallMs = STALL_MS_RE.find(row)?.groupValues?.get(1)?.toLong()
        assertNotNull(
            stallMs,
            "and the row must carry HOW LONG the upstream was silent, got: " + row,
        )
        assertTrue(
            stallMs != null && stallMs >= STALL_TIER_MS,
            "STALL_MS is the watchdog's own idleMs, so it can never be under the tier that fired " +
                "(${STALL_TIER_MS}ms), got: $stallMs in " + row,
        )
    }
}
