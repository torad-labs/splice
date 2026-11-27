// `splice feed <name>` as a script meets it: the value piped in becomes the visual mode's feed, a read by the mode's
// own tool then gives it back, and the script never holds or sees the management key. The daemon is the real control
// server with the real visual mode on it, behind its guard, reached with the key the command reads from the state
// folder. A stand-in answers only the two things a running daemon cannot be made to do: lose its answer after the
// value went up, and be a daemon from before feeds.
package splice.app.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.control.ControlAuth
import splice.app.control.ControlServer
import splice.app.control.controlServerFor
import splice.app.modes.DaemonModes
import splice.app.modes.ModeMcp
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.mode.ModeSetting
import splice.core.terminal.TerminalOutput
import splice.modes.api.ModeCatalogReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

class FeedCommandTest {
    @TempDir
    lateinit var tmp: Path

    private val said = CopyOnWriteArrayList<String>()
    private val failed = CopyOnWriteArrayList<String>()
    private lateinit var modes: DaemonModes
    private lateinit var control: ControlServer
    private var standIn: HttpServer? = null

    private fun statePaths(): StatePaths = StatePaths(baseOverride = tmp.resolve("state"))

    private fun key(): String = MgmtKey(statePaths()).get()

    @BeforeEach
    fun start() {
        val catalog = ModeCatalogReader(Thread.currentThread().contextClassLoader).read()
        modes = DaemonModes(catalog, mapOf("visual" to ModeSetting(on = true)), listOf("acme"), tmp.resolve("modes"))
        val paths = statePaths()
        control = controlServerFor(
            0,
            emptyMap(),
            ConfigService(paths, envReader = { null }),
            ControlAuth(MgmtKey(paths), {}),
        )
        control.ports.modes = modes
        runBlocking { control.start() }
    }

    @AfterEach
    fun stop() {
        control.stop()
        standIn?.stop(0)
    }

    /** The command a script runs, its standard input [stdin], against the daemon (or whatever is on [port]). */
    private fun feed(
        name: String,
        stdin: String,
        port: Int = control.listeningPort,
        follow: Boolean = false,
    ): Boolean = run(name, stdin.byteInputStream(), port, follow)

    private fun run(name: String, stdin: InputStream, port: Int = control.listeningPort, follow: Boolean): Boolean {
        val env = mapOf(
            "SPLICE_STATE_DIR" to tmp.resolve("state").toString(),
            "SPLICE_CONTROL_PORT" to port.toString(),
            "SPLICE_CONFIG" to tmp.resolve("splice.toml").toString(),
            "HOME" to tmp.toString(),
        )
        return FeedCommand(
            TerminalOutput { said.add(it) },
            TerminalOutput { failed.add(it) },
            stdin,
        ).run(name, follow, { variable -> env[variable] })
    }

    /** What a card's host reads through the mode's MCP server: the `feed` tool's answer, parsed as the card does. */
    private fun read(name: String): JsonObject = runBlocking {
        val server = checkNotNull(ModeMcp { modes }.server("visual", "acme", "head"))
        val answer = server.call("feed", JsonObject(mapOf("name" to JsonPrimitive(name))), "card-session")
        assertEquals("false", answer.getValue("isError").toString(), "$answer")
        val text = answer.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
        Json.parseToJsonElement(text).jsonObject
    }

    private fun data(name: String): String = read(name).getValue("data").toString()

    /** The line numbers of what was said, in the order it was said; a sentence with no line is left out. */
    private fun lineNumbers(): List<Int> =
        failed.mapNotNull { Regex("line (\\d+)").find(it)?.groupValues?.get(1)?.toInt() }

    /** A port nothing listens on: bound, read and closed, so a connection to it is refused. (An HttpServer made and
     *  stopped without being started keeps its socket bound, and the kernel takes the connection.) */
    private fun closedPort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    @Test
    fun `a value piped in is the feed, and a read by the tool then gives it back`() {
        val ok = feed("training", """{"step":40,"loss":0.31}""" + "\n")

        assertTrue(ok, "$failed")
        assertTrue(said.single().startsWith("Feed training written at "), "it says what it kept: $said")
        val read = read("training")
        assertEquals("true", read.getValue("found").jsonPrimitive.content)
        assertEquals("""{"step":40,"loss":0.31}""", read.getValue("data").toString())
        assertTrue(read.getValue("updated").jsonPrimitive.content.endsWith("Z"), "it is stamped: $read")
    }

    @Test
    fun `the script never sees the management key, whatever the daemon says`() {
        feed("training", """{"loss":1}""")
        feed("training", "not json at all")
        feed("training", "")
        feed("training", "1\nhello\n", follow = true)
        val out = (said + failed).joinToString("\n")
        assertFalse(key() in out, "the key is not printed: $out")
    }

    @Test
    fun `a single value that is not strict JSON is refused in words, exits 1, and the feed keeps what it had`() {
        assertTrue(feed("training", "[1,2]"))

        for (value in listOf("hello", "01", "+1", "NaN", "{\"x\":hello}", "loss is 0.5", "\u000B1", "\"raw\ttab\"")) {
            failed.clear()
            assertFalse(feed("training", value), value)
            assertTrue("JSON" in failed.single(), "$value: $failed")
        }
        assertEquals("[1,2]", data("training"))
    }

    @Test
    fun `invalid UTF-8 is refused by both command forms without replacing the value`() {
        val latin1 = "\"café\"".toByteArray(Charsets.ISO_8859_1)
        assertTrue(feed("training", "7"))

        for (follow in listOf(false, true)) {
            failed.clear()
            assertFalse(run("training", latin1.inputStream(), follow = follow))
            assertTrue("UTF-8" in failed.single(), "$failed")
            assertEquals("7", data("training"))
        }
        failed.clear()
        assertFalse(run("training", (latin1 + "\r\n8".encodeToByteArray()).inputStream(), follow = true))
        assertEquals(listOf(1), lineNumbers())
        assertEquals("8", data("training"))
    }

    @Test
    fun `follow reads CR LF and CRLF, including a CRLF across separate reads`() {
        val source = object : ByteArrayInputStream("1\r2\r\nhello\n3\r\n4".encodeToByteArray()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 1))
        }

        assertFalse(run("training", source, follow = true))
        assertEquals(listOf(3), lineNumbers(), "$failed")
        assertEquals("4", data("training"))
    }

    @Test
    fun `null or no value is refused in words, and the feed keeps what it had`() {
        assertTrue(feed("training", """{"loss":1}"""))

        assertFalse(feed("training", "null"))
        assertTrue("null" in failed.last(), "$failed")
        assertFalse(feed("training", "  \n"))
        assertTrue("standard input" in failed.last(), "$failed")
        assertEquals("""{"loss":1}""", data("training"))
    }

    @Test
    fun `a name or a size the mode refuses is told to the script as the mode words it`() {
        assertFalse(feed("has space", "1"))
        assertTrue("letters, digits" in failed.last(), "$failed")
        assertFalse(feed("_leading", "1"))
        assertTrue("starting with a letter or a digit" in failed.last(), "$failed")
        assertFalse(feed("big", "\"" + "a".repeat(64 * 1024) + "\""))
        assertTrue("65538 bytes" in failed.last() && "64 KiB" in failed.last(), "$failed")
        assertEquals("false", read("big").getValue("found").jsonPrimitive.content)
    }

    @Test
    fun `with follow, each line is one value written in the order it came, and the last is what a read gives`() {
        val lines = (1..5).joinToString("\n") { """{"step":$it}""" } + "\n"

        assertTrue(feed("training", lines, follow = true), "$failed")

        assertEquals("""{"step":5}""", data("training"))
        assertTrue(said.isEmpty() && failed.isEmpty(), "a stream that is kept says nothing: $said $failed")
    }

    @Test
    fun `with follow, a value that is not strict JSON is said with its line, the next is written, and it exits 1`() {
        val every = feed("training", "1\nhello\n01\n\n+1\nNaN\n{\"x\":hello}\nnull\n{\"n\":9}\n", follow = true)

        assertFalse(every, "a line was not kept, so the run says so")
        assertEquals(listOf(2, 3, 5, 6, 7, 8), lineNumbers(), "$failed")
        assertTrue(failed.take(5).all { "JSON" in it }, "$failed")
        assertTrue("null" in failed.last(), "$failed")
        assertEquals("""{"n":9}""", data("training"), "the lines after a bad one were written")
    }

    @Test
    fun `with follow, only bad lines leave the old value, and the feed never holds text a card cannot parse`() {
        assertTrue(feed("training", "7"))

        assertFalse(feed("training", "hello\n+1\n", follow = true))

        assertEquals("7", data("training"))
    }

    @Test
    fun `with follow, standard input ending, even empty or with no last newline, ends cleanly`() {
        assertTrue(feed("training", "", follow = true), "$failed")
        assertTrue(feed("training", "\n\n", follow = true), "$failed")
        assertTrue(feed("training", "{\"last\":true}", follow = true), "$failed")

        assertEquals("""{"last":true}""", data("training"))
        assertTrue(said.isEmpty() && failed.isEmpty(), "$said $failed")
    }

    @Test
    fun `a value read past what a feed holds is refused where it is read, and not read on`() {
        val source = Endless(50L * 1024 * 1024)

        assertFalse(run("training", source, follow = false))

        assertTrue("past 65 KiB" in failed.single() && "nothing was written" in failed.single(), "$failed")
        assertTrue(source.read <= 64 * 1024 + 1024 + 1, "it read ${source.read} bytes of 50 MiB")
        assertEquals("false", read("training").getValue("found").jsonPrimitive.content)
    }

    @Test
    fun `with follow, a line past what a feed holds is refused with its number once it is past, then it goes on`() {
        val past = 64 * 1024 + 1024
        val toldBy = ArrayList<Long>()
        val source = Endless(4L * 1024 * 1024, tail = "\n{\"ok\":1}\n") { at ->
            if (failed.isNotEmpty() && toldBy.isEmpty()) toldBy.add(at)
        }

        assertFalse(run("training", source, follow = true))

        assertEquals(1, failed.size, "$failed")
        assertTrue("line 1 " in failed.single() && "past 65 KiB" in failed.single(), "$failed")
        assertTrue(toldBy.single() <= past + 8 * 1024L, "said after ${toldBy.single()} bytes of a 4 MiB line")
        assertEquals("""{"ok":1}""", data("training"), "the next line was written")
    }

    @Test
    fun `with follow, a daemon that is not there is said once, whatever the lines after it`() {
        assertFalse(feed("training", "1\n2\n3\n", port = closedPort(), follow = true))

        assertEquals(1, failed.size, "$failed")
        assertTrue("line 1" in failed.single() && "not running" in failed.single(), "$failed")
        assertTrue("nothing was written" in failed.single(), "$failed")
    }

    @Test
    fun `with no daemon listening it says so, that nothing was written, and fails`() {
        assertFalse(feed("training", "1", port = closedPort()))

        assertTrue("not running" in failed.single() && "nothing was written" in failed.single(), "$failed")
    }

    @Test
    fun `a reply lost after the value was sent is said as not known, never as nothing written`() {
        val lost = standIn { exchange ->
            // the value is taken and the connection closed with no answer, as a daemon that went down would
            check(exchange.requestBody.readBytes().isNotEmpty())
            exchange.close()
        }

        assertFalse(feed("lost", "1", port = lost))
        assertTrue("not known" in failed.single() && "nothing was written" !in failed.single(), "$failed")
        failed.clear()
        assertFalse(feed("lost", "1\n2\n", port = lost, follow = true))
        assertEquals(listOf(1), lineNumbers(), "a daemon that does not answer is said once: $failed")
        assertTrue("not known" in failed.single(), "$failed")
    }

    @Test
    fun `a partial success reply does not confirm that a feed was kept`() {
        val lost = standIn { exchange ->
            exchange.requestBody.use { it.readBytes() }
            exchange.sendResponseHeaders(200, 100L)
            exchange.responseBody.use { it.write("{\"sa".encodeToByteArray()) }
        }

        assertFalse(feed("lost", "1", port = lost))
        assertTrue("not known" in failed.single() && "kept nothing" !in failed.single(), "$failed")
    }

    @Test
    fun `a daemon from before feeds is said to be older, and nothing was written`() {
        val older = standIn { exchange ->
            check(exchange.requestBody.readBytes().isNotEmpty())
            exchange.sendResponseHeaders(404, -1L)
            exchange.close()
        }

        assertFalse(feed("training", "1", port = older))

        assertTrue("older than this splice" in failed.single() && "nothing was written" in failed.single(), "$failed")
    }

    private fun standIn(answer: (HttpExchange) -> Unit): Int {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { answer(it) }
        server.start()
        standIn = server
        return server.address.port
    }
}

/** A standard input of [length] bytes of `a`, then [tail], made as it is read, that counts what was read and tells
 *  [onRead] where it is; it is the stream a command must not hold whole. */
private class Endless(
    private val length: Long,
    private val tail: String = "",
    private val onRead: (Long) -> Unit = {},
) : InputStream() {
    var read = 0L
        private set
    private val after = tail.toByteArray()

    override fun read(): Int {
        onRead(read)
        val next = when {
            read < length -> 'a'.code
            read - length < after.size -> after[(read - length).toInt()].toInt()
            else -> -1
        }
        if (next >= 0) read++
        return next
    }

    override fun read(into: ByteArray, offset: Int, count: Int): Int {
        if (count == 0) return 0
        var filled = 0
        while (filled < count) {
            val next = read()
            if (next < 0) break
            into[offset + filled++] = next.toByte()
        }
        return if (filled == 0) -1 else filled
    }
}
