// `splice feed <name>` as a script meets it: the value piped in becomes the visual mode's feed, a read by the mode's
// own tool then gives it back, and the script never holds or sees the management key. The daemon is a stand-in that
// checks the key the command read from the state folder and hands each request to the real visual mode's routes.
package splice.app.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.terminal.TerminalOutput
import splice.modes.api.FoundMode
import splice.modes.api.ModeAnswer
import splice.modes.api.ModeCall
import splice.modes.api.ModeCaller
import splice.modes.api.ModeCatalogReader
import splice.modes.api.ModeRequest
import splice.modes.api.ModeRoute
import splice.modes.api.ModeSession
import splice.modes.api.ModeStore
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

class FeedCommandTest {
    @TempDir
    lateinit var tmp: Path

    private val said = CopyOnWriteArrayList<String>()
    private val failed = CopyOnWriteArrayList<String>()
    private lateinit var daemon: StandInDaemon

    private val catalog = ModeCatalogReader(Thread.currentThread().contextClassLoader).read()
    private val visual = checkNotNull(catalog.mode("visual"))
    private val store = object : ModeStore {
        override fun read(session: String): String? = null
        override fun write(session: String, text: String) = Unit
    }

    @BeforeEach
    fun start() {
        daemon = StandInDaemon(key(), visual, store)
    }

    @AfterEach
    fun stop() {
        daemon.stop()
    }

    private fun statePaths(): StatePaths = StatePaths(baseOverride = tmp.resolve("state"))

    private fun key(): String = MgmtKey(statePaths()).get()

    /** The command a script runs, its standard input [stdin], against the stand-in daemon (or none on `port`). */
    private fun feed(name: String, stdin: String, port: Int = daemon.port, follow: Boolean = false): Boolean {
        val env = mapOf(
            "SPLICE_STATE_DIR" to tmp.resolve("state").toString(),
            "SPLICE_CONTROL_PORT" to port.toString(),
            "SPLICE_CONFIG" to tmp.resolve("splice.toml").toString(),
            "HOME" to tmp.toString(),
        )
        return FeedCommand(
            TerminalOutput { said.add(it) },
            TerminalOutput { failed.add(it) },
            stdin.byteInputStream(),
        ).run(name, follow, { variable -> env[variable] })
    }

    /** What the mode's `feed` tool answers for [name]: the read a card makes through its app. */
    private fun read(name: String): JsonObject {
        val session = ModeSession("card", null, "", "", ModeCaller.DESKTOP)
        val input = JsonObject(mapOf("name" to JsonPrimitive(name)))
        val answer = visual.handler.answer(ModeCall("feed", input, session, store))
        assertTrue(answer is ModeAnswer.Text, "$answer")
        return Json.parseToJsonElement(answer.text).jsonObject
    }

    @Test
    fun `a value piped in is the feed, and a read by the tool then gives it back`() {
        val ok = feed("training", """{"step":40,"loss":0.31}""" + "\n")

        assertTrue(ok, "$failed")
        assertTrue(said.any { "training" in it }, "it says what it kept: $said")
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
        val out = (said + failed).joinToString("\n")
        assertFalse(key() in out, "the key is not printed: $out")
    }

    @Test
    fun `a value that is not JSON, or no value, is refused in words and the feed keeps what it had`() {
        assertTrue(feed("training", """{"loss":1}"""))

        assertFalse(feed("training", "loss is 0.5"))
        assertTrue(failed.any { "JSON" in it }, "$failed")
        assertFalse(feed("training", "  \n"))
        assertTrue(failed.any { "standard input" in it }, "$failed")
        assertEquals("""{"loss":1}""", read("training").getValue("data").toString())
    }

    @Test
    fun `a name or a size the mode refuses is told to the script as the mode words it`() {
        assertFalse(feed("has space", "1"))
        assertTrue(failed.any { "letters, digits" in it }, "$failed")
        assertFalse(feed("big", "\"" + "a".repeat(64 * 1024) + "\""))
        assertTrue(failed.any { "64 KiB" in it }, "$failed")
        assertEquals("false", read("big").getValue("found").jsonPrimitive.content)
    }

    @Test
    fun `with follow, each line is one value written in the order it came, and the last is what a read gives`() {
        val lines = (1..5).joinToString("\n") { """{"step":$it,"loss":${1.0 / it}}""" } + "\n"

        assertTrue(feed("training", lines, follow = true), "$failed")

        assertEquals((1..5).map { """{"step":$it,"loss":${1.0 / it}}""" }, daemon.written)
        assertEquals(5, read("training").getValue("data").jsonObject.getValue("step").jsonPrimitive.content.toInt())
        assertTrue(said.isEmpty() && failed.isEmpty(), "a stream that is kept says nothing: $said $failed")
    }

    @Test
    fun `with follow, a bad line is said with its number and does not end the stream`() {
        val lines = "{\"n\":1}\nnot json\n\n{\"n\":3}\n"

        val every = feed("training", lines, follow = true)

        assertFalse(every, "a line was not kept, so the run says so")
        assertEquals(listOf("{\"n\":1}", "{\"n\":3}"), daemon.written)
        assertEquals(1, failed.size, "$failed")
        assertTrue("line 2" in failed.single() && "JSON" in failed.single(), "$failed")
        assertEquals(3, read("training").getValue("data").jsonObject.getValue("n").jsonPrimitive.content.toInt())
    }

    @Test
    fun `with follow, a line the mode refuses is said and the next is written`() {
        val every = feed("has space", "1\n2\n", follow = true)

        assertFalse(every)
        assertEquals(2, failed.size, "each refused line is said: $failed")
        assertTrue(failed.all { "letters, digits" in it }, "$failed")
        assertTrue(feed("training", "7\n", follow = true))
        assertEquals("7", read("training").getValue("data").toString())
    }

    @Test
    fun `with follow, standard input ending, even empty or with no last newline, ends cleanly`() {
        assertTrue(feed("training", "", follow = true), "$failed")
        assertTrue(feed("training", "\n\n", follow = true), "$failed")
        assertTrue(feed("training", "{\"last\":true}", follow = true), "$failed")

        assertEquals(listOf("{\"last\":true}"), daemon.written)
        assertTrue(said.isEmpty() && failed.isEmpty(), "$said $failed")
    }

    @Test
    fun `with follow, a daemon that is not there is said once, whatever the lines after it`() {
        val closed = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { it.stop(0) }.address.port

        assertFalse(feed("training", "1\n2\n3\n", port = closed, follow = true))

        assertEquals(1, failed.size, "$failed")
        assertTrue("line 1" in failed.single() && "not running" in failed.single(), "$failed")
    }

    @Test
    fun `with no daemon listening it says so and fails`() {
        val closed = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { it.stop(0) }.address.port
        assertFalse(feed("training", "1", port = closed))
        assertTrue(failed.any { "not running" in it }, "$failed")
    }
}

/** A daemon's control port as far as feeds go: it checks the bearer key, then hands the request to the mode's route. */
private class StandInDaemon(private val key: String, private val mode: FoundMode, private val store: ModeStore) {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val port: Int get() = server.address.port

    /** The bodies of the writes it was handed, in the order they came. */
    val written = CopyOnWriteArrayList<String>()

    init {
        server.createContext("/") { exchange -> answer(exchange) }
        server.start()
    }

    private fun answer(exchange: HttpExchange) {
        val (status, reply) = decide(exchange)
        val bytes = reply.toByteArray()
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1L else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private fun decide(exchange: HttpExchange): Pair<Int, String> {
        val body = exchange.requestBody.readBytes().decodeToString().takeIf(String::isNotBlank)
        val parsed = body?.let(::json)
        val route = routeOf(exchange)
        return when {
            exchange.requestHeaders.getFirst("Authorization") != "Bearer $key" -> 401 to error("unauthorized")
            route == null -> 404 to ""
            body != null && parsed == null -> 400 to error("the body is not JSON")
            else -> handled(route, exchange, parsed)
        }
    }

    private fun handled(route: ModeRoute, exchange: HttpExchange, body: JsonElement?): Pair<Int, String> {
        if (exchange.requestMethod == "PUT" && body != null) written.add(body.toString())
        val request = ModeRequest(exchange.requestMethod, exchange.requestURI.path, emptyMap(), body, store)
        return route.handler.handle(request).let { it.status to it.body.toString() }
    }

    private fun routeOf(exchange: HttpExchange): ModeRoute? = mode.handler.routes.firstOrNull {
        it.method == exchange.requestMethod && exchange.requestURI.path.startsWith(it.path.substringBefore("{"))
    }

    private fun json(text: String): JsonElement? = try {
        Json.parseToJsonElement(text)
    } catch (_: SerializationException) {
        null
    }

    private fun error(text: String): String = buildJsonObject { put("error", text) }.toString()

    fun stop() = server.stop(0)
}
