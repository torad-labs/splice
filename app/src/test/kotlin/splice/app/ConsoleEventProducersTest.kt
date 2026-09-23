// NEW: V4-134 — every family the daemon produces reaches a REAL /api/events subscriber through the
// bus, as the SSE frame the console reads, with exactly the fields its serializer declares.
//
// THE DENOMINATOR COMES FROM THE SOURCE, not from a list in this file: the families are
// ConsoleEvent's sealed subclasses and each one's fields are its serializer descriptor's element
// names. A new family added to EventBus.kt without a producer here fails by name, and so does a
// family whose payload stops carrying a declared field. Every family is produced: message.edge was
// dispositioned here as deferred until V4-130 landed its wire observation (MessageEdges), and the
// disposition was removed with it rather than left to excuse a family that now has a producer.
//
// Through the route, not through EventBus.subscribe: that method is internal to :daemon-control, which is
// the point — :app reaches the bus only by publishing, and the console only by the stream.
package splice.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.serializer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.app.control.ControlServer
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.util.WallClock
import splice.events.bus.ConsoleEvent
import splice.head.HeadLifecycle
import java.nio.file.Files

private const val TIMEOUT_MS = 10_000L
private const val POLL_MS = 25L

/** The wall clock the publisher stamps message.edge's `at` with, so the frame's value is exact. */
private const val EDGE_AT = 1_789_612_775_123L

/** One SSE frame as the console reads it. */
private data class Frame(val id: Long, val event: String, val data: JsonObject)

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsoleEventProducersTest {

    // The server binds port 0 and reports what it got: no leased port can be taken before the bind.
    private val port: Int get() = control.listeningPort
    private val client = HttpClient(CIO) { expectSuccess = false }
    private val publisher = ConsoleEventPublisher(clock = WallClock { EDGE_AT })
    private lateinit var control: ControlServer
    private lateinit var key: String

    @BeforeAll
    fun setUp() {
        val paths = StatePaths(baseOverride = Files.createTempDirectory("v4134-producers").resolve("state"))
        val mgmt = MgmtKey(paths)
        key = mgmt.get()
        control = ControlServer(
            port = 0,
            heads = emptyMap(),
            config = ConfigService(paths),
            mgmtKey = mgmt,
            dashboardHtml = { "<!doctype html>" },
            log = { },
        )
        control.ports.events = publisher.bus
        runBlocking { control.start() }
    }

    @AfterAll
    fun tearDown() {
        control.stop()
        client.close()
    }

    @OptIn(InternalSerializationApi::class)
    @Test
    fun `every produced family reaches the stream carrying exactly its declared fields`() = runBlocking {
        val frames = collect(6) {
            val head = publisher.forHead("claude")
            head.lifecycle(HeadLifecycle.STARTED)
            head.turnStarted("session-1") // turn.start, and session.change: a session seen for the first time
            head.accountSwitched("primary", "backup")
            head.turnEnded("1789612775000", "ok")
            head.messageSent("session-1", "uds:/run/peer.sock", "toolu_1")
        }
        val declared = ConsoleEvent::class.sealedSubclasses.associate { family ->
            val descriptor = family.serializer().descriptor
            descriptor.serialName to descriptor.elementNames.toSet()
        }
        val produced = frames.associateBy { it.event }
        assertEquals(declared.keys, produced.keys, "every declared family must be produced, and nothing undeclared")
        produced.forEach { (family, frame) ->
            assertEquals(declared.getValue(family), frame.data.keys, "the payload of $family moved off its serializer")
            assertEquals(frame.id, frame.data.getValue("seq").jsonPrimitive.long, "$family: the SSE id must be its seq")
        }
        assertEquals(
            mapOf(
                "head.state" to mapOf("head" to "claude", "state" to "started"),
                "turn.start" to mapOf("head" to "claude", "session" to "session-1"),
                "session.change" to mapOf("session" to "session-1", "head" to "claude"),
                "account.switch" to mapOf("head" to "claude", "from" to "primary", "to" to "backup"),
                "turn.end" to mapOf("head" to "claude", "perfRowId" to "1789612775000", "outcome" to "ok"),
                "message.edge" to mapOf("from" to "session-1", "to" to "uds:/run/peer.sock", "at" to EDGE_AT.toString()),
            ),
            produced.mapValues { (_, frame) ->
                (frame.data - "seq").mapValues { (_, value) -> value.jsonPrimitive.content }
            },
            "each family must carry the values its producer was handed, under the right names",
        )
    }

    @Test
    fun `session change reports a session appearing or moving head, and nothing else`() = runBlocking {
        val frames = collect(9) {
            val a = publisher.forHead("head-a")
            val b = publisher.forHead("head-b")
            a.turnStarted("moving") // first sight: change to head-a
            a.turnStarted("moving") // same head again: no change
            b.turnStarted("moving") // moved: change to head-b
            a.turnStarted(null) // no session header: a turn, never a session
            b.turnStarted("moving") // still head-b: no change
            a.turnStarted("moving") // moved back: change to head-a
        }
        assertEquals(
            listOf(
                "turn.start head-a moving",
                "session.change head-a moving",
                "turn.start head-a moving",
                "turn.start head-b moving",
                "session.change head-b moving",
                "turn.start head-a null",
                "turn.start head-b moving",
                "turn.start head-a moving",
                "session.change head-a moving",
            ),
            frames.map { frame ->
                val session = frame.data["session"]?.jsonPrimitive?.content
                "${frame.event} ${frame.data.getValue("head").jsonPrimitive.content} $session"
            },
        )
    }

    /** Opens the stream, runs [produce] once the stream is live, and returns the next [count] frames.
     *  The stream subscribes before it writes its open comment, so producing after that line cannot
     *  race the subscription. */
    private suspend fun collect(count: Int, produce: () -> Unit): List<Frame> = withTimeout(TIMEOUT_MS) {
        awaitPort()
        val open = CompletableDeferred<Unit>()
        val reading = async {
            client.prepareGet("http://127.0.0.1:$port/api/events") {
                header("Authorization", "Bearer $key")
            }.execute { response ->
                val channel = response.bodyAsChannel()
                check(channel.readUTF8Line() == ": open") { "the stream must open with its comment" }
                open.complete(Unit)
                List(count) { readFrame(channel) }
            }
        }
        open.await()
        produce()
        reading.await()
    }

    /** The next frame: blank lines and comments before it are skipped, and the blank line after it
     *  ends it. */
    private suspend fun readFrame(channel: ByteReadChannel): Frame {
        val fields = mutableMapOf<String, String>()
        var line = channel.readUTF8Line()
        while (line != null && stillReading(fields.isEmpty(), line)) {
            if (line.isNotEmpty() && !line.startsWith(":")) {
                fields[line.substringBefore(": ")] = line.substringAfter(": ")
            }
            line = channel.readUTF8Line()
        }
        check(fields.isNotEmpty()) { "the stream ended before a frame" }
        return Frame(
            id = fields.getValue("id").toLong(),
            event = fields.getValue("event"),
            data = Json.parseToJsonElement(fields.getValue("data")).jsonObject,
        )
    }

    private suspend fun awaitPort() {
        while (runCatching { java.net.Socket("127.0.0.1", port).close() }.isFailure) delay(POLL_MS)
    }

    /** Before the frame starts every line is read (the blank after `: open`, heartbeats); once it
     *  has started, the first blank line ends it. */
    private fun stillReading(nothingYet: Boolean, line: String): Boolean = nothingYet || line.isNotEmpty()
}
