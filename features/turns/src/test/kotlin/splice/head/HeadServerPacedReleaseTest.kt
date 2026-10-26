// NEW: fix 2 of Oct 26 (Marcos approved it at about 2:00 AM CT). When the model ends a burst of text and then calls a
// tool or ends its message, Claude Code gets the tool call and the end of the message as soon as splice gets them from
// upstream, so the tool starts at once and the turn ends at once. Before, the pacer (V4-456) held a burst for up to its
// one-second window and every later frame queued behind it, the tool call and message_stop included: on claude-splice,
// Oct 25, 6 to 11 PM CT, over 21,215 turns, the pacer's longest hold per turn was 131 ms at p50 and 944 ms at p99.
// A burst that more text follows is still spread over the window, for the V4-456 reason: a 31 KB thinking summary that
// arrives in one read after a silence.
//
// Driven through the REAL production path: a real HeadServer on the anthropic-passthrough wire, a real HTTP upstream
// that writes a whole burst and what follows it at once, and a client that reads the response as it streams.
package splice.head

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readLine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.WatchdogBudget
import splice.core.util.JsonScalars
import splice.dialect.anthropic.PassthroughProvider
import splice.dialect.anthropic.PassthroughQuirks
import splice.head.wire.PACE_TICK_MS
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.transport.UpstreamClient
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/** The pacing window a burst is spread over: a held delta waits at most this long, a tick aside. */
private const val WINDOW_MS = 1_000L

/** "At once" for a frame that crosses two real sockets on this machine: a few of the pacer's own ticks. */
private const val FEW_TICKS = 5

/** The Oct 1 JFR shape the pacer exists for: 190 deltas in one provider read. */
private const val BURST = 190

// why: a burst the pacer spreads over most of its window, 6,080 characters in one read, carried in five deltas so the
// head reads five events before the tool call, not 190. On Oct 26, with other seats' builds holding this machine at a
// load of 135 on 16 cores, reading 190 deltas took the head up to 230 ms before the frame after them reached the pacer.
private const val BURST_DELTAS = 5
private const val BURST_CHARS = 6_080

/** Of a burst that more text follows, the deltas in its first text block; the rest open a second one. */
private const val FIRST_BLOCK = 150

// why: the bound reads the middle of five turns. One turn can stall behind other processes on this machine: at a load
// of 113 to 135 on 16 cores (Oct 26 runs), one turn in 18 waited 189 ms before splice wrote a byte to the client.
private const val MEASURED_TURNS = 5

// why: six turns through a fresh JVM, each paced over at most the window, plus a client and a server start.
private const val TEST_SECONDS = 60L

// why: how long the upstream waits for the client to read a held burst before it sends the rest anyway.
private const val GATE_SECONDS = 10L

private const val DATA = "data: "
private const val MESSAGE_STOP_LINE = "event: message_stop"
private const val RESPONSE_HEAD = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n"
private const val SESSION = "sess-paced-release"
private const val REQUEST =
    """{"model":"claude-paced--paced-1","stream":true,"max_tokens":64,"messages":[{"role":"user","content":"go"}]}"""

private class PacedAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.ApiKey("held-secret", "x-api-key", "")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

/** One answer of the upstream: [burst] in one write, then, once [gate] opens (at once when there is none), [rest]. */
private data class PacedAnswer(
    val burst: List<String>,
    val rest: List<String> = emptyList(),
    val gate: CountDownLatch? = null,
) {
    /** System.nanoTime() once the burst has left the upstream. */
    val burstSent = CompletableFuture<Long>()

    /** System.nanoTime() once the answer's last byte has left the upstream. */
    val lastByte = CompletableFuture<Long>()
}

/** Reads one HTTP/1.1 request off [input]: its head, then its body by Content-Length or by chunks. */
private class RequestReader(private val input: InputStream) {
    fun skipRequest() {
        val head = generateSequence { line() }.takeWhile { it.isNotEmpty() }.toList()
        val fields = head.drop(1).associate { field ->
            field.substringBefore(":").trim().lowercase() to field.substringAfter(":").trim()
        }
        if (fields["transfer-encoding"].equals("chunked", ignoreCase = true)) {
            skipChunks()
        } else {
            input.readNBytes(fields["content-length"]?.toInt() ?: 0)
        }
    }

    private fun skipChunks() {
        do {
            val size = line().substringBefore(";").trim().toInt(16)
            input.readNBytes(size)
            line()
        } while (size > 0)
    }

    private fun line(): String {
        val bytes = ByteArrayOutputStream()
        var next = input.read()
        while (next >= 0 && next != '\n'.code) {
            if (next != '\r'.code) bytes.write(next)
            next = input.read()
        }
        return bytes.toString(Charsets.US_ASCII)
    }
}

/** An Anthropic Messages upstream that streams whatever [answer] holds for the next turn, on a plain socket with
 *  TCP_NODELAY so each flush leaves at once. The JDK's HttpServer keeps Nagle on: in the Oct 26 runs its socket held
 *  the answer about 40 ms behind the response head's delayed ACK, time in which splice did not have the bytes. */
private class PacedUpstream : AutoCloseable {
    private val pool = Executors.newCachedThreadPool()
    private val server = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))

    @Volatile var answer = PacedAnswer(emptyList())

    val url: String get() = "http://127.0.0.1:${server.localPort}"

    init {
        pool.execute { acceptTurns() }
    }

    private fun acceptTurns() {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: return
            socket.tcpNoDelay = true
            pool.execute { socket.use { answerTurn(it) } }
        }
    }

    // The answer ends when the socket closes: no length and no chunks, so each write reaches splice as written.
    private fun answerTurn(socket: Socket) {
        RequestReader(socket.getInputStream().buffered()).skipRequest()
        val turn = answer
        val out = socket.getOutputStream()
        out.write((RESPONSE_HEAD + turn.burst.joinToString("")).toByteArray())
        out.flush()
        turn.burstSent.complete(System.nanoTime())
        turn.gate?.await(GATE_SECONDS, TimeUnit.SECONDS)
        out.write(turn.rest.joinToString("").toByteArray())
        out.flush()
        turn.lastByte.complete(System.nanoTime())
    }

    override fun close() {
        server.close()
        pool.shutdownNow()
    }
}

/** One line of the response and the moment the client read it. */
private data class PacedLine(val text: String, val atNanos: Long)

/** A measured turn: every line the client read, and when the upstream's last byte left. */
private data class PacedRead(val read: List<PacedLine>, val lastByteNanos: Long)

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeadServerPacedReleaseTest {
    private val upstream = PacedUpstream()
    private val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }
    private lateinit var head: HeadServer

    @BeforeAll
    fun setUp(@TempDir tmp: Path) {
        head = HeadServer(
            provider = PassthroughProvider(
                tuning = ProviderTuning(
                    name = ProviderName(key = "paced", label = "claude-paced"),
                    catalog = ModelCatalog(
                        discoveryPrefix = "claude-paced--",
                        models = listOf(ModelEntry("paced-1", "Paced", contextWindow = 200_000)),
                        defaultContextWindow = 200_000,
                    ),
                    pinnedModel = "paced-1",
                    auth = PacedAuth(),
                    locations = ProviderLocations(baseUrl = upstream.url),
                    watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
                ),
                quirks = PassthroughQuirks(providerTag = "neutral"),
            ),
            listenPort = 0,
            deps = headDeps(tmp, upstream = UpstreamClient(totalTimeoutMs = 30_000, maxRetries = 1)),
        )
        runBlocking { head.start() }
    }

    @AfterAll
    fun tearDown() {
        runBlocking { head.stop() }
        upstream.close()
        client.close()
    }

    private fun frame(event: String, data: String) = "event: $event\n$DATA$data\n\n"

    private fun messageStart() = frame(
        "message_start",
        """{"type":"message_start","message":{"id":"msg_paced","type":"message","role":"assistant",""" +
            """"model":"paced-1","content":[],"stop_reason":null,"usage":{"input_tokens":1,"output_tokens":1}}}""",
    )

    private fun textBlock(index: Int) = frame(
        "content_block_start",
        """{"type":"content_block_start","index":$index,"content_block":{"type":"text","text":""}}""",
    )

    private fun blockStop(index: Int) = frame("content_block_stop", """{"type":"content_block_stop","index":$index}""")

    private fun word(i: Int) = "w$i "

    private fun textDelta(index: Int, text: String) = frame(
        "content_block_delta",
        """{"type":"content_block_delta","index":$index,"delta":{"type":"text_delta","text":"$text"}}""",
    )

    private fun textDeltas(index: Int, range: IntRange): List<String> = range.map { textDelta(index, word(it)) }

    /** The burst a tool call or the end follows: [burstText] in [BURST_DELTAS] deltas. */
    private fun burstDeltas(index: Int): List<String> =
        burstText().chunked(BURST_CHARS / BURST_DELTAS).map { textDelta(index, it) }

    private fun toolCall(index: Int): List<String> = listOf(
        frame(
            "content_block_start",
            """{"type":"content_block_start","index":$index,""" +
                """"content_block":{"type":"tool_use","id":"toolu_paced","name":"Read","input":{}}}""",
        ),
        frame(
            "content_block_delta",
            """{"type":"content_block_delta","index":$index,""" +
                """"delta":{"type":"input_json_delta","partial_json":"{\"file_path\":\"/tmp/paced.txt\"}"}}""",
        ),
        blockStop(index),
    )

    private fun ending(stopReason: String): List<String> = listOf(
        frame(
            "message_delta",
            """{"type":"message_delta","delta":{"stop_reason":"$stopReason","stop_sequence":null},""" +
                """"usage":{"output_tokens":$BURST}}""",
        ),
        frame("message_stop", """{"type":"message_stop"}"""),
    )

    /** One turn through the head: every line of its response, with the moment the client read it. */
    private suspend fun turn(answer: PacedAnswer, onLine: (String) -> Unit = {}): List<PacedLine> {
        upstream.answer = answer
        val lines = ArrayList<PacedLine>()
        client.preparePost("http://127.0.0.1:${head.port}/v1/messages") {
            header("Content-Type", "application/json")
            header("x-claude-code-session-id", SESSION)
            setBody(REQUEST)
        }.execute { response ->
            val body = response.bodyAsChannel()
            var line = body.readLine()
            while (line != null) {
                lines += PacedLine(line, System.nanoTime())
                onLine(line)
                line = body.readLine()
            }
        }
        return lines
    }

    /** The burst and [after] in ONE upstream write, the way a provider batches them, read back by the client in
     *  [MEASURED_TURNS] turns. */
    private suspend fun burstThen(after: List<String>): List<PacedRead> {
        val frames = listOf(messageStart(), textBlock(0)) + burstDeltas(0) + blockStop(0) + after
        // A fresh JVM loads the passthrough, emitter and pacer classes during its first turn; the turns measured below
        // run them warm, as a daemon that serves turns all day does.
        turn(PacedAnswer(frames))
        return List(MEASURED_TURNS) {
            val measured = PacedAnswer(frames)
            val read = turn(measured)
            PacedRead(read, measured.lastByte.get(GATE_SECONDS, TimeUnit.SECONDS))
        }
    }

    private fun data(line: PacedLine): JsonObject? =
        line.text.takeIf { it.startsWith(DATA) }?.let { Json.parseToJsonElement(it.removePrefix(DATA)).jsonObject }

    private fun deltaText(line: PacedLine): String? {
        val delta = data(line)?.get("delta") as? JsonObject ?: return null
        return JsonScalars.str(delta["text"]).takeIf { JsonScalars.str(delta["type"]) == "text_delta" }
    }

    private fun isToolStart(line: PacedLine): Boolean {
        val event = data(line) ?: return false
        val block = event["content_block"] as? JsonObject ?: return false
        return JsonScalars.str(event["type"]) == "content_block_start" && JsonScalars.str(block["type"]) == "tool_use"
    }

    /** The text of the burst a tool call or the end follows: [BURST_CHARS] characters of numbered words. */
    private fun burstText(): String = (0 until BURST_CHARS).joinToString("") { word(it) }.take(BURST_CHARS)

    /** The text of the burst that more text follows: the words of its [BURST] deltas. */
    private fun wordsText(): String = (0 until BURST).joinToString("") { word(it) }

    private fun msBetween(fromNanos: Long, toNanos: Long): Long = TimeUnit.NANOSECONDS.toMillis(toNanos - fromNanos)

    /** One turn: every delta of the burst reached the client ahead of the first line [next] matches, the [name], and
     *  message_stop came last. Answers how long after the upstream's last byte message_stop reached the client. */
    private fun stopMs(turn: PacedRead, name: String, next: (PacedLine) -> Boolean): Long {
        val (read, lastByte) = turn
        val after = read.indexOfFirst(next)
        val stop = read.indexOfFirst { it.text == MESSAGE_STOP_LINE }
        assertTrue(after >= 0 && stop >= after, "the $name and the end reached the client: ${read.map { it.text }}")
        val deltas = read.mapIndexedNotNull { at, line -> deltaText(line)?.let { at to it } }
        assertEquals(burstText(), deltas.joinToString("") { it.second }, "every delta of the burst reached the client")
        assertTrue(deltas.last().first < after, "the held deltas go first, then the $name")
        return msBetween(lastByte, read[stop].atNanos)
    }

    private fun middle(values: List<Long>): Long = values.sorted()[values.size / 2]

    @Test
    @Timeout(TEST_SECONDS)
    fun `a tool call after a burst reaches the client at once, behind every delta of the burst`() = runBlocking {
        val stops = burstThen(toolCall(1) + ending("tool_use")).map { turn ->
            stopMs(turn, "tool call") { isToolStart(it) }
        }

        assertTrue(
            middle(stops) <= FEW_TICKS * PACE_TICK_MS,
            "message_stop, behind the tool call, reached the client $stops ms after the upstream's last byte; " +
                "at once is within $FEW_TICKS ticks of $PACE_TICK_MS ms in the middle turn, and the window is " +
                "$WINDOW_MS ms",
        )
    }

    @Test
    @Timeout(TEST_SECONDS)
    fun `the end of a message after a burst reaches the client at once`() = runBlocking {
        val stops = burstThen(ending("end_turn")).map { turn -> stopMs(turn, "end") { it.text == MESSAGE_STOP_LINE } }

        assertTrue(
            middle(stops) <= FEW_TICKS * PACE_TICK_MS,
            "message_stop reached the client $stops ms after the upstream's last byte; at once is within " +
                "$FEW_TICKS ticks of $PACE_TICK_MS ms in the middle turn, and the window is $WINDOW_MS ms",
        )
    }

    @Test
    @Timeout(TEST_SECONDS)
    fun `a burst that more text follows is still spread over the pacing window`() = runBlocking {
        val lastWord = "\"${word(BURST - 1)}\""
        val readAll = CountDownLatch(1)
        // The burst and the text block that follows it, in one write. The end waits until the client has read the
        // burst's last delta, so only more text follows the burst while it is paced.
        val answer = PacedAnswer(
            burst = listOf(messageStart(), textBlock(0)) + textDeltas(0, 0 until FIRST_BLOCK) + blockStop(0) +
                textBlock(1) + textDeltas(1, FIRST_BLOCK until BURST),
            rest = listOf(blockStop(1)) + ending("end_turn"),
            gate = readAll,
        )
        val read = turn(answer) { line -> if (lastWord in line) readAll.countDown() }

        val burstSent = answer.burstSent.get(GATE_SECONDS, TimeUnit.SECONDS)
        val deltas = read.filter { deltaText(it) != null }
        assertEquals(wordsText(), deltas.joinToString("") { deltaText(it).orEmpty() }, "every delta reached the client")
        val spreadMs = msBetween(burstSent, deltas.last().atNanos)
        assertTrue(
            spreadMs >= WINDOW_MS / 2,
            "the burst is spread, not dumped: its last delta reached the client $spreadMs ms after the upstream " +
                "sent it, and the window is $WINDOW_MS ms",
        )
    }
}
