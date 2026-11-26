package splice.modes.visual

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
            listOf("mcp__visual__read_me", "mcp__visual__show_widget", "mcp__visual__html_render"),
            found.tools.map { it.offeredAs },
        )
        val named = found.tools.filter { it.name != "read_me" }.map {
            checkNotNull(it.meta).getValue("ui").jsonObject.getValue("resourceUri").jsonPrimitive.content
        }
        assertEquals(listOf("ui://visual/reply.html", "ui://visual/reply.html"), named)
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
}
