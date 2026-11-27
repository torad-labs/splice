package splice.modes.visual

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.modes.api.ModeAnswer
import splice.modes.api.ModeCall
import splice.modes.api.ModeCaller
import splice.modes.api.ModeCatalogReader
import splice.modes.api.ModeKind
import splice.modes.api.ModeRequest
import splice.modes.api.ModeResponse
import splice.modes.api.ModeSession
import splice.modes.api.ModeStore
import java.time.Instant

/** The visual mode as a caller meets it: the catalog's reading of its folder, each tool's answer, its two routes. */
class VisualModeTest {
    /** The daemon's store as the mode sees it: text by key, kept across a new mode (a restarted daemon). */
    private class MemoryStore : ModeStore {
        val kept = LinkedHashMap<String, String>()
        override fun read(session: String): String? = kept[session]
        override fun write(session: String, text: String) {
            kept[session] = text
        }
    }

    private val store = MemoryStore()
    private val mode = VisualMode()
    private val session = ModeSession("mcp-1", null, "codex", "", ModeCaller.HEAD)
    private val catalog = ModeCatalogReader(VisualModeTest::class.java.classLoader).read()
    private val pageId = Regex("\\bvp_[0-9a-f]{12}\\b")

    private fun call(tool: String, vararg input: Pair<String, JsonElement>, on: VisualMode = mode): ModeAnswer =
        on.answer(ModeCall(tool, JsonObject(mapOf(*input)), session, store))

    private fun get(path: String, query: Map<String, String> = emptyMap(), on: VisualMode = mode): ModeResponse {
        val route = on.routes.single { it.method == "GET" && path.startsWith(it.path.substringBefore("{")) }
        return route.handler.handle(ModeRequest("GET", path, query, null, store))
    }

    private fun field(response: ModeResponse, key: String): String =
        response.body.jsonObject.getValue(key).jsonPrimitive.content

    private fun page(title: String, html: String, vararg more: Pair<String, JsonElement>): ModeAnswer =
        call("html_render", "title" to JsonPrimitive(title), "html" to JsonPrimitive(html), *more)

    private fun widget(title: String, code: String): ModeAnswer =
        call("show_widget", "title" to JsonPrimitive(title), "widget_code" to JsonPrimitive(code))

    private fun idOf(answer: ModeAnswer): String {
        assertTrue(answer is ModeAnswer.Text, "$answer")
        return checkNotNull(pageId.find(answer.text)) { "no page id in: ${answer.text}" }.value
    }

    @Test
    fun `the catalog reads it whole, on for Desktop alone, as the server visual whose drawing tools name its view`() {
        assertEquals(emptyList<String>(), catalog.findings, catalog.findings.joinToString("\n"))
        val found = checkNotNull(catalog.mode("visual"))
        assertEquals(ModeKind.MCP to false, found.manifest.kind to found.manifest.offer.on)
        assertEquals(listOf(ModeCaller.DESKTOP), found.manifest.offer.onFor, "on for Claude Desktop's own sessions")
        assertEquals(
            listOf("mcp__visual__read_me", "mcp__visual__show_widget", "mcp__visual__html_render", "mcp__visual__feed"),
            found.tools.map { it.offeredAs },
        )
        val named = found.tools.filter { it.name in setOf("show_widget", "html_render") }.map {
            checkNotNull(it.meta).getValue("ui").jsonObject.getValue("resourceUri").jsonPrimitive.content
        }
        assertEquals(listOf("ui://visual/reply.html", "ui://visual/reply.html"), named)
        // A card calls feed through its host, so it carries no mark that keeps it to the model.
        assertEquals(null, found.tools.single { it.name == "feed" }.meta)
        val view = found.resources.single()
        assertEquals("ui://visual/reply.html" to "text/html;profile=mcp-app", view.uri to view.mimeType)
        assertEquals("""{"ui":{"csp":{"connectDomains":[],"resourceDomains":[]}}}""", view.meta.toString())
        assertTrue(checkNotNull(found.prompt).lines().size <= 2, "prompt.md is paid on every request: ${found.prompt}")
    }

    @Test
    fun `the view draws both tools' calls and loads nothing from anywhere`() {
        val html = checkNotNull(checkNotNull(catalog.mode("visual")).resources.single().content)
        assertTrue(html.trimStart().startsWith("<!doctype html>"), html.take(40))
        assertTrue(listOf("ui/initialize", "ui/notifications/tool-input", "widget_code").all { it in html })
        val code = html.replace("https://modelcontextprotocol.io/extensions/apps/overview", "")
            .replace("https://invalid.example/", "")
        assertTrue(listOf("<script src", "<link ", "http://", "https://", "fetch(", "import(").none { it in code })
    }

    @Test
    fun `read_me gives the guidance and the modules asked for`() {
        val every = call("read_me")
        assertTrue(every is ModeAnswer.Text && "When a visual beats text" in every.text, "$every")
        val modules = listOf("chart", "comparison", "diagram", "diff", "explainer", "tool", "page")
        assertTrue(modules.all { "## Module: $it" in every.text }, every.text)
        assertFalse("## Module: live" in every.text, "a bare read_me leaves out the live module")
        val live = call("read_me", "modules" to JsonArray(listOf(JsonPrimitive("live"))))
        assertTrue(live is ModeAnswer.Text && "## Module: live" in live.text && "splice feed" in live.text, "$live")
        assertTrue("--color-text-primary" in every.text, "it tells the model to use the host's theme")

        val chart = call("read_me", "modules" to JsonArray(listOf(JsonPrimitive("chart"))))
        assertTrue(chart is ModeAnswer.Text && "## Module: chart" in chart.text, "$chart")
        assertFalse("## Module: diagram" in chart.text)
        assertTrue("When it does not" in chart.text)

        val unknown = call("read_me", "modules" to JsonArray(listOf(JsonPrimitive("poster"))))
        assertTrue(unknown is ModeAnswer.Error && "poster" in unknown.text && "chart" in unknown.text, "$unknown")
    }

    @Test
    fun `show_widget answers in words for a client that draws nothing, and refuses what it cannot draw`() {
        val drawn = widget("monthly_costs", "<svg/>")
        assertTrue(drawn is ModeAnswer.Text, "$drawn")
        assertTrue("monthly_costs" in drawn.text && "MCP Apps" in drawn.text && "in words" in drawn.text, drawn.text)

        val untitled = call("show_widget", "widget_code" to JsonPrimitive("<svg/>"))
        assertTrue(untitled is ModeAnswer.Error && "title" in untitled.text, "$untitled")
        val empty = widget("x", "  ")
        assertTrue(empty is ModeAnswer.Error && "widget_code" in empty.text, "$empty")
        val big = widget("x", "a".repeat(256 * 1024 + 1))
        assertTrue(big is ModeAnswer.Error && "256 KiB" in big.text, "$big")
        assertTrue(widget("x", "a".repeat(256 * 1024)) is ModeAnswer.Text, "256 KiB itself is drawn")
        assertTrue(call("draw_poster") is ModeAnswer.Error)
    }

    @Test
    fun `html_render keeps the page, and its route gives it back by the id its answer names`() {
        val html = "<!doctype html><html><body><h1>Night's work</h1></body></html>"
        val answer = page("The night's work", html, "height" to JsonPrimitive(900))
        val id = idOf(answer)
        assertTrue("MCP Apps" in (answer as ModeAnswer.Text).text && "in words" in answer.text, answer.text)

        val read = get("/api/visual/pages/$id")
        assertEquals(200, read.status)
        assertEquals(html to "The night's work", field(read, "html") to field(read, "title"))
        assertEquals("900", field(read, "height"))

        val plain = get("/api/visual/pages/${idOf(page("Plain", "<p>plain</p>"))}")
        assertEquals(setOf("html", "title"), plain.body.jsonObject.keys, "no height when the call gave none")
    }

    @Test
    fun `the first page id in html_render's answer is its own page, whatever id its title names`() {
        val old = idOf(page("Report", "<p>old</p>"))
        val revised = page("Report (replaces $old)", "<p>new</p>")
        val first = idOf(revised)
        assertTrue(first != old, (revised as ModeAnswer.Text).text)
        assertEquals("<p>new</p>", field(get("/api/visual/pages/$first"), "html"))
    }

    @Test
    fun `a kept page is still there for a restarted daemon`() {
        val id = idOf(page("Kept", "<p>kept</p>"))
        val restarted = VisualMode()
        assertEquals("<p>kept</p>", field(get("/api/visual/pages/$id", on = restarted), "html"))
    }

    @Test
    fun `the pages route answers 404 for a page the mode does not keep`() {
        page("Some page", "<p>one</p>")
        assertEquals(404, get("/api/visual/pages/vp_0123456789ab").status)
        assertEquals(404, get("/api/visual/pages/..%2F..%2Fetc%2Fpasswd").status)
        assertEquals(404, get("/api/visual/pages/pages").status)
        val nothingKept = ModeRequest("GET", "/api/visual/pages/vp_0123456789ab", emptyMap(), null, MemoryStore())
        assertEquals(404, VisualMode().routes.single { "pages" in it.path }.handler.handle(nothingKept).status)
    }

    @Test
    fun `once 200 pages are kept the oldest goes first, and the store holds no more than them`() {
        val ids = (1..201).map { idOf(page("Page $it", "<p>$it</p>")) }
        assertEquals(404, get("/api/visual/pages/${ids.first()}").status)
        assertEquals("<p>2</p>", field(get("/api/visual/pages/${ids[1]}"), "html"))
        assertEquals("<p>201</p>", field(get("/api/visual/pages/${ids.last()}"), "html"))
        assertTrue(store.kept.size <= 201, "the store holds ${store.kept.size} entries")
    }

    @Test
    fun `html_render reads no file, whatever the path, and refuses what it cannot keep`() {
        for (path in listOf("/etc/hostname", "report.html", "../../etc/passwd", "~/.ssh/id_ed25519")) {
            val refused = call("html_render", "title" to JsonPrimitive("t"), "path" to JsonPrimitive(path))
            assertTrue(refused is ModeAnswer.Error && "html" in refused.text && "reads no file" in refused.text)
        }
        val both = page("t", "<p>x</p>", "path" to JsonPrimitive("/etc/hostname"))
        assertTrue(both is ModeAnswer.Error && "reads no file" in both.text, "$both")
        assertTrue(store.kept.isEmpty(), "nothing was kept for a refused call")

        val big = page("t", "a".repeat(256 * 1024 + 1))
        assertTrue(big is ModeAnswer.Error && "256 KiB" in big.text, "$big")
        val tall = page("t", "<p>x</p>", "height" to JsonPrimitive(10_001))
        assertTrue(tall is ModeAnswer.Error && "height" in tall.text, "$tall")
        val worded = page("t", "<p>x</p>", "height" to JsonPrimitive("tall"))
        assertTrue(worded is ModeAnswer.Error && "height" in worded.text, "$worded")
        assertTrue(store.kept.isEmpty())
    }

    @Test
    fun `the view route gives what resources-read gives, and 404 for a view the mode does not have`() {
        val declared = checkNotNull(catalog.mode("visual")).resources.single()
        val view = get("/api/visual/view", mapOf("uri" to "ui://visual/reply.html"))
        assertEquals(200, view.status)
        assertEquals(declared.content, field(view, "html"))
        assertEquals(declared.meta, view.body.jsonObject.getValue("meta"))

        assertEquals(404, get("/api/visual/view", mapOf("uri" to "ui://visual/other.html")).status)
        assertEquals(404, get("/api/visual/view", mapOf("uri" to "ui://tableapp/table")).status)
        assertEquals(404, get("/api/visual/view").status)
    }

    private fun feed(name: String, data: JsonElement? = null, on: VisualMode = mode): ModeAnswer =
        call("feed", "name" to JsonPrimitive(name), *listOfNotNull(data?.let { "data" to it }).toTypedArray(), on = on)

    private fun put(path: String, body: JsonElement?, on: VisualMode = mode): ModeResponse =
        on.routes.single { it.method == "PUT" && path.startsWith(it.path.substringBefore("{")) }
            .handler.handle(ModeRequest("PUT", path, emptyMap(), body, store))

    private fun readOf(answer: ModeAnswer): JsonObject {
        assertTrue(answer is ModeAnswer.Text, "$answer")
        return Json.parseToJsonElement(answer.text).jsonObject
    }

    @Test
    fun `a feed written is read back with its value and when it was written, and a rewrite replaces it`() {
        val before = Instant.now().minusSeconds(1)
        val loss = JsonObject(mapOf("step" to JsonPrimitive(40), "loss" to JsonPrimitive(0.31)))
        val written = feed("training", loss)
        assertTrue(written is ModeAnswer.Text && "training" in written.text, "$written")

        val read = readOf(feed("training"))
        assertEquals("training", read.getValue("name").jsonPrimitive.content)
        assertEquals("true", read.getValue("found").jsonPrimitive.content)
        assertEquals(loss, read.getValue("data"))
        val at = Instant.parse(read.getValue("updated").jsonPrimitive.content)
        assertTrue(at >= before && at <= Instant.now().plusSeconds(1), "written $at, between $before and now")

        feed("training", JsonObject(mapOf("step" to JsonPrimitive(41))))
        assertEquals(JsonPrimitive(41), readOf(feed("training")).getValue("data").jsonObject.getValue("step"))
    }

    @Test
    fun `a feed no one wrote is answered plainly as not found, never as an error`() {
        val read = feed("nobody_wrote_this")
        assertEquals("false", readOf(read).getValue("found").jsonPrimitive.content)
        assertFalse("data" in readOf(read) || "updated" in readOf(read))
        assertTrue(read is ModeAnswer.Text)
    }

    @Test
    fun `a feed holds any JSON value but null, and null is refused by the tool and the route alike`() {
        val one = JsonArray(listOf(JsonPrimitive(1)))
        for (value in listOf(JsonPrimitive("ready"), JsonPrimitive(7), JsonPrimitive(true), one)) {
            feed("shape", value)
            assertEquals(value, readOf(feed("shape")).getValue("data"))
        }
        val tool = feed("shape", JsonNull)
        assertTrue(tool is ModeAnswer.Error && "null" in tool.text && "leave data out" in tool.text, "$tool")
        val route = put("/api/visual/feeds/shape", JsonNull)
        assertEquals(400, route.status)
        assertTrue("null" in field(route, "error"), field(route, "error"))
        assertEquals(one, readOf(feed("shape")).getValue("data"), "the old value stays")
    }

    // The daemon's own JSON parser reads a bare word, a leading zero, a plus sign and NaN as literals. A feed keeps
    // only what a card's JSON.parse reads, so each is refused by the tool and the route, and what was there stays.
    @Test
    fun `a value that is not strict JSON is refused by the tool and the route, and what was there is kept`() {
        feed("strict", JsonPrimitive(1))
        val notStrict = listOf("hello", "01", "+1", "NaN", "Infinity", ".5", "1.", "0x10")
        val nested = listOf("{\"x\":hello}", "[1,01]", "{\"a\":[{\"b\":+2}]}")
        for (text in notStrict + nested) {
            val lenient = Json.parseToJsonElement(text)
            val tool = feed("strict", lenient)
            assertTrue(tool is ModeAnswer.Error && "strict JSON" in tool.text, "$text: $tool")
            val route = put("/api/visual/feeds/strict", lenient)
            assertEquals(400, route.status, text)
            assertTrue("strict JSON" in field(route, "error"), "$text: ${field(route, "error")}")
        }
        assertEquals(JsonPrimitive(1), readOf(feed("strict")).getValue("data"), "nothing changed what was there")
        for (text in listOf("0", "-0.5e3", "1E+2", "true", "false", "\"a\\u00e9\"", "[]", "{\"a\":[1,{\"b\":null}]}")) {
            assertTrue(feed("strict", Json.parseToJsonElement(text)) is ModeAnswer.Text, text)
            val read = readOf(feed("strict"))
            assertEquals(Json.parseToJsonElement(text), read.getValue("data"), text)
        }
    }

    @Test
    fun `a name that is not a short safe token is refused in words the model can act on`() {
        for (name in listOf("", "has space", "../etc", "a/b", "-leading", "x".repeat(41), "ünï", "a.b")) {
            val refused = feed(name, JsonPrimitive(1))
            val said = (refused as? ModeAnswer.Error)?.text.orEmpty()
            assertTrue("1 to 40" in said && "letters, digits" in said, "$name: $refused")
            assertTrue(feed(name) is ModeAnswer.Error, "reading $name")
        }
        assertTrue(feed("x".repeat(40), JsonPrimitive(1)) is ModeAnswer.Text, "40 characters is the most")
        assertTrue(call("feed", "data" to JsonPrimitive(1)) is ModeAnswer.Error, "no name")
        assertEquals(400, get("/api/visual/feeds/has%20space").status)
        assertEquals(400, put("/api/visual/feeds/has%20space", JsonPrimitive(1)).status)
    }

    @Test
    fun `a value past 64 KiB is refused with its size, and what it replaced is kept`() {
        feed("big", JsonPrimitive("kept"))
        val atMost = "a".repeat(64 * 1024 - 2)
        assertTrue(feed("big", JsonPrimitive(atMost)) is ModeAnswer.Text, "64 KiB itself is kept")
        val tooBig = feed("big", JsonPrimitive(atMost + "a"))
        assertTrue(tooBig is ModeAnswer.Error && "65537 bytes" in tooBig.text && "64 KiB" in tooBig.text, "$tooBig")
        assertEquals(JsonPrimitive(atMost), readOf(feed("big")).getValue("data"), "a refused write changes nothing")
        assertEquals(413, put("/api/visual/feeds/big", JsonPrimitive(atMost + "a")).status)
    }

    @Test
    fun `a 65th feed drops the one written longest ago, says so, and a rewrite keeps a feed young`() {
        (1..64).forEach { feed("f$it", JsonPrimitive(it)) }
        feed("f1", JsonPrimitive("again"))
        val answer = feed("f65", JsonPrimitive(65))
        assertTrue(answer is ModeAnswer.Text && "f2" in answer.text && "dropped" in answer.text, "$answer")
        assertEquals("false", readOf(feed("f2")).getValue("found").jsonPrimitive.content)
        assertEquals("true", readOf(feed("f1")).getValue("found").jsonPrimitive.content)
        assertEquals("true", readOf(feed("f65")).getValue("found").jsonPrimitive.content)
        assertEquals("true", readOf(feed("f3")).getValue("found").jsonPrimitive.content)
        assertTrue(store.kept.isEmpty(), "feeds are in memory: nothing is written to the store")
    }

    @Test
    fun `the feeds route gives the bytes the tool reads, and found false the way the tool does for a feed not there`() {
        feed("build", JsonObject(mapOf("done" to JsonPrimitive(12), "of" to JsonArray(listOf(JsonPrimitive("a"))))))
        val route = get("/api/visual/feeds/build")
        assertEquals(200, route.status)
        assertEquals((feed("build") as ModeAnswer.Text).text, route.body.toString(), "the same text, byte for byte")
        val data = Json.parseToJsonElement(route.body.toString()).jsonObject.getValue("data")
        assertEquals("""{"done":12,"of":["a"]}""", data.toString())
        val missing = get("/api/visual/feeds/nobody_wrote_this")
        assertEquals(200, missing.status)
        assertEquals((feed("nobody_wrote_this") as ModeAnswer.Text).text, missing.body.toString())
        assertEquals("""{"name":"nobody_wrote_this","found":false}""", missing.body.toString())
    }

    @Test
    fun `a feed written through its route is read by the tool, and a bad write says why`() {
        val kept = put("/api/visual/feeds/queue", JsonObject(mapOf("depth" to JsonPrimitive(3))))
        assertEquals(200, kept.status)
        assertTrue("queue" in field(kept, "said"), field(kept, "said"))
        assertEquals(JsonPrimitive(3), readOf(feed("queue")).getValue("data").jsonObject.getValue("depth"))

        val none = put("/api/visual/feeds/queue", null)
        assertEquals(400, none.status)
        assertTrue("JSON body" in field(none, "error"), field(none, "error"))
        assertEquals(JsonPrimitive(3), readOf(feed("queue")).getValue("data").jsonObject.getValue("depth"))
    }
}
