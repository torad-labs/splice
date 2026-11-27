// The visual mode as the daemon serves it, with the catalog its jar carries: its entry in `GET /api/modes` as a host
// reads it, its view through the MCP server's `resources/read`, and a page a html_render call kept read back through
// its management route from the daemon's store on disk, after a restart too. Off, no MCP server answers for it. Its
// feed routes are reached over HTTP on the real control server, behind its guard, as a script or an app reaches them.
package splice.app.modes

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.control.ControlAuth
import splice.app.control.controlServerFor
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.mode.ModeSetting
import splice.modes.api.ModeCatalogReader
import splice.modes.api.ModeRequest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path

/** The view's `_meta`: a content security policy that lets it load from and connect to no origin. */
private const val NO_ORIGIN = """{"ui":{"csp":{"connectDomains":[],"resourceDomains":[]}}}"""

class VisualModeServedTest {
    @TempDir
    lateinit var state: Path

    private val catalog = ModeCatalogReader(Thread.currentThread().contextClassLoader).read()

    private fun daemon(on: Boolean?): DaemonModes =
        DaemonModes(catalog, mapOf("visual" to ModeSetting(on = on)), listOf("acme"), state)

    private fun route(modes: DaemonModes, path: String, query: Map<String, String> = emptyMap()) =
        checkNotNull(modes.mode("visual")).handler.routes
            .single { it.method == "GET" && path.startsWith(it.path.substringBefore("{")) }
            .handler.handle(ModeRequest("GET", path, query, null, modes.store("visual")))

    @Test
    fun `GET api-modes lists it as an mcp mode whose drawing tools name the view it serves`() {
        val row = ModeRows(daemon(on = null)).all().getValue("modes").jsonArray.map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == "visual" }
        assertEquals("mcp", row.getValue("kind").jsonPrimitive.content)
        assertEquals("visual", row.getValue("server").jsonPrimitive.content)
        val tools = row.getValue("tools").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("mcp__visual__read_me", "mcp__visual__show_widget", "mcp__visual__html_render", "mcp__visual__feed"),
            tools.map { it.getValue("offered_as").jsonPrimitive.content },
        )
        val views = tools.mapNotNull { it["_meta"]?.jsonObject?.get("ui")?.jsonObject?.get("resourceUri") }
        assertEquals(listOf("ui://visual/reply.html", "ui://visual/reply.html"), views.map { it.jsonPrimitive.content })
        val resource = row.getValue("resources").jsonArray.single().jsonObject
        assertEquals("ui://visual/reply.html", resource.getValue("uri").jsonPrimitive.content)
        assertEquals(NO_ORIGIN, resource.getValue("_meta").toString())
    }

    @Test
    fun `a host is shown that the drawing tools are the model's to call and not the view's`() {
        val listed = checkNotNull(ModeMcp { daemon(on = true) }.server("visual", "acme", "head")).tools()
            .map { it.jsonObject }
            .associate { it.getValue("name").jsonPrimitive.content to it["_meta"] }
        val row = ModeRows(daemon(on = true)).all().getValue("modes").jsonArray.map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == "visual" }
        val rowed = row.getValue("tools").jsonArray.map { it.jsonObject }
            .associate { it.getValue("name").jsonPrimitive.content to it["_meta"] }
        for (shown in listOf(listed, rowed)) {
            for (tool in listOf("show_widget", "html_render")) {
                val ui = checkNotNull(shown[tool]).jsonObject.getValue("ui").jsonObject
                assertEquals("""["model"]""", ui.getValue("visibility").toString(), "$tool in $shown")
            }
        }
    }

    @Test
    fun `its MCP server answers only while it is on, and resources-read gives the view the route gives`() {
        assertNull(ModeMcp { daemon(on = null) }.server("visual", "acme", "head"), "not a head's by default")
        val modes = daemon(on = true)
        val server = checkNotNull(ModeMcp { modes }.server("visual", "acme", "head"))
        val read = checkNotNull(server.read("ui://visual/reply.html")).single().jsonObject
        assertEquals("text/html;profile=mcp-app", read.getValue("mimeType").jsonPrimitive.content)
        assertEquals(NO_ORIGIN, read.getValue("_meta").toString())
        val view = route(modes, "/api/visual/view", mapOf("uri" to "ui://visual/reply.html"))
        assertEquals(200, view.status)
        assertEquals(read.getValue("text"), view.body.jsonObject.getValue("html"))
        assertEquals(read.getValue("_meta"), view.body.jsonObject.getValue("meta"))
        assertNull(server.read("ui://visual/other.html"))
        assertEquals(404, route(modes, "/api/visual/view", mapOf("uri" to "ui://visual/other.html")).status)
    }

    @Test
    fun `a page html_render kept is read back by its id from disk, after a restart too`() = runBlocking {
        val modes = daemon(on = true)
        val server = checkNotNull(ModeMcp { modes }.server("visual", "acme", "head"))
        val args = JsonObject(mapOf("title" to JsonPrimitive("Report"), "html" to JsonPrimitive("<p>report</p>")))
        val result = server.call("html_render", args, "mcp-session-1")
        assertEquals("false", result.getValue("isError").toString())
        val text = result.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
        val id = checkNotNull(Regex("\\bvp_[0-9a-f]{12}\\b").find(text)) { text }.value

        val restarted = DaemonModes(
            ModeCatalogReader(Thread.currentThread().contextClassLoader).read(),
            mapOf("visual" to ModeSetting(on = true)),
            listOf("acme"),
            state,
        )
        val page = route(restarted, "/api/visual/pages/$id")
        assertEquals(200, page.status)
        assertEquals("<p>report</p>", page.body.jsonObject.getValue("html").jsonPrimitive.content)
        assertEquals("Report", page.body.jsonObject.getValue("title").jsonPrimitive.content)
        assertEquals(404, route(restarted, "/api/visual/pages/vp_0123456789ab").status)
    }

    @Test
    fun `a feed a tool call wrote is read through its route, from another session, and the route says the same`() =
        runBlocking {
            val modes = daemon(on = true)
            val server = checkNotNull(ModeMcp { modes }.server("visual", "acme", "head"))
            val data = JsonObject(mapOf("step" to JsonPrimitive(40), "loss" to JsonPrimitive(0.31)))
            val written = server.call(
                "feed",
                JsonObject(mapOf("name" to JsonPrimitive("training"), "data" to data)),
                "mcp-session-1",
            )
            assertEquals("false", written.getValue("isError").toString())

            val read = route(modes, "/api/visual/feeds/training")
            assertEquals(200, read.status)
            assertEquals(data, Json.parseToJsonElement(read.body.toString()).jsonObject.getValue("data"))

            val card = server.call("feed", JsonObject(mapOf("name" to JsonPrimitive("training"))), "mcp-session-2")
            val text = card.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
            assertEquals(read.body.toString(), text, "the tool and the route say the same")
            val missing = route(modes, "/api/visual/feeds/not_written")
            assertEquals(200, missing.status)
            assertEquals("""{"name":"not_written","found":false}""", missing.body.toString())
        }

    @Test
    fun `over HTTP, a body that is not strict JSON is refused in words and the feed keeps what it had`() = served {
        assertEquals(200, put("training", "1").statusCode())

        val lenient = listOf(
            "hello", "01", "+1", "NaN", "Infinity", "{\"x\":hello}", "[1,01]", "true false", "\uFEFF42",
            "\"raw\ttab\"", "\"raw\u0000nul\"", "{\"raw\nkey\":1}", "\u000B1",
        )
        for (body in lenient) {
            val refused = put("training", body)
            assertEquals(400, refused.statusCode(), body)
            assertTrue("JSON" in error(refused), "$body: ${refused.body()}")
        }

        val read = get("training")
        assertEquals(200, read.statusCode())
        assertEquals("1", data(read), "only the strict value was kept: ${read.body()}")
        assertEquals(read.body(), toolRead("training"), "the route and the tool answer the same bytes")
    }

    @Test
    fun `PUT refuses invalid UTF-8 before changing the value`() = served {
        assertEquals(200, put("training", "7").statusCode())
        val refused = sendBytes("PUT", "training", "\"café\"".toByteArray(Charsets.ISO_8859_1))
        assertEquals(400, refused.statusCode())
        assertTrue("UTF-8" in error(refused), refused.body())
        assertEquals("7", data(get("training")))
    }

    @Test
    fun `over HTTP, a strict value is read back as the same JSON by the route and the tool alike`() = served {
        val value = """{"step":40,"loss":0.31,"tags":["a","é"],"ok":true,"none":null,"e":-1.5E+3}"""

        assertEquals(200, put("training", value).statusCode())

        val read = get("training")
        assertEquals(Json.parseToJsonElement(value), Json.parseToJsonElement(data(read)))
        assertEquals(read.body(), toolRead("training"))
    }

    @Test
    fun `over HTTP, null and an empty body are refused and the old value stays`() = served {
        put("training", "[1]")

        val nothing = put("training", "null")
        assertEquals(400, nothing.statusCode())
        assertTrue("null" in error(nothing), nothing.body())
        assertEquals(400, put("training", "").statusCode())

        assertEquals("[1]", data(get("training")))
    }

    @Test
    fun `over HTTP, a feed nobody wrote answers 200 found false, as the tool does`() = served {
        val missing = get("nobody_wrote_this")

        assertEquals(200, missing.statusCode())
        assertEquals("""{"name":"nobody_wrote_this","found":false}""", missing.body())
        assertEquals(missing.body(), toolRead("nobody_wrote_this"))
        assertEquals(400, get("_leading").statusCode(), "a name starts with a letter or a digit")
    }

    @Test
    fun `over HTTP, a value past 64 KiB is refused with its size, and 64 KiB itself is kept`() = served {
        val most = "\"" + "a".repeat(64 * 1024 - 2) + "\""
        assertEquals(200, put("big", most).statusCode())

        val past = put("big", "\"" + "a".repeat(64 * 1024 - 1) + "\"")

        assertEquals(413, past.statusCode())
        assertTrue("65537 bytes" in error(past) && "64 KiB" in error(past), past.body())
        assertEquals(most, data(get("big")))
    }

    @Test
    fun `over HTTP, no key or a key that is not the daemon's reaches no feed`() = served {
        put("training", "1")

        assertEquals(401, send("GET", "training", bearer = null).statusCode())
        assertEquals(401, send("PUT", "training", "2", bearer = "not-the-key").statusCode())
        assertEquals("1", data(get("training")))
    }

    /** Runs [checks] against the real control server with the visual mode on, on a port the OS picks. */
    private fun served(checks: Served.() -> Unit) {
        val paths = StatePaths(baseOverride = state.resolve("served"))
        val mgmt = MgmtKey(paths)
        val modes = daemon(on = true)
        val control = controlServerFor(0, emptyMap(), ConfigService(paths, envReader = { null }), ControlAuth(mgmt, {}))
        control.ports.modes = modes
        runBlocking { control.start() }
        try {
            Served(control.listeningPort, mgmt.get(), modes).checks()
        } finally {
            control.stop()
        }
    }
}

/** The control port as a script or an app reaches it: [port], with the management key [key]. */
private class Served(private val port: Int, private val key: String, private val modes: DaemonModes) {
    private val http = HttpClient.newHttpClient()

    fun send(method: String, name: String, body: String? = null, bearer: String? = key): HttpResponse<String> {
        return sendBytes(method, name, body?.encodeToByteArray(), bearer)
    }

    fun sendBytes(method: String, name: String, body: ByteArray?, bearer: String? = key): HttpResponse<String> {
        val publisher = body?.let(HttpRequest.BodyPublishers::ofByteArray) ?: HttpRequest.BodyPublishers.noBody()
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/visual/feeds/$name"))
            .header("Content-Type", "application/json")
            .method(method, publisher)
        bearer?.let { request.header("Authorization", "Bearer $it") }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    fun put(name: String, body: String): HttpResponse<String> = send("PUT", name, body)

    fun get(name: String): HttpResponse<String> = send("GET", name)

    fun error(response: HttpResponse<String>): String =
        Json.parseToJsonElement(response.body()).jsonObject.getValue("error").jsonPrimitive.content

    /** The value a read answered, as the JSON text a card parses. */
    fun data(response: HttpResponse<String>): String =
        Json.parseToJsonElement(response.body()).jsonObject.getValue("data").toString()

    /** What a card's host reads through the mode's MCP server: the text of the `feed` tool's answer. */
    fun toolRead(name: String): String = runBlocking {
        val server = checkNotNull(ModeMcp { modes }.server("visual", "acme", "head"))
        val answer = server.call("feed", JsonObject(mapOf("name" to JsonPrimitive(name))), "card-session")
        answer.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
    }
}
