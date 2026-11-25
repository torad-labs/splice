// The visual mode as the daemon serves it, with the catalog its jar carries: its entry in `GET /api/modes` as a host
// reads it, its view through the MCP server's `resources/read`, and a page a html_render call kept read back through
// its management route from the daemon's store on disk, after a restart too. Off, no MCP server answers for it.
package splice.app.modes

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.mode.ModeSetting
import splice.modes.api.ModeCatalogReader
import splice.modes.api.ModeRequest
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
            .single { path.startsWith(it.path.substringBefore("{")) }
            .handler.handle(ModeRequest("GET", path, query, null, modes.store("visual")))

    @Test
    fun `GET api-modes lists it as an mcp mode whose drawing tools name the view it serves`() {
        val row = ModeRows(daemon(on = null)).all().getValue("modes").jsonArray.map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == "visual" }
        assertEquals("mcp", row.getValue("kind").jsonPrimitive.content)
        assertEquals("visual", row.getValue("server").jsonPrimitive.content)
        val tools = row.getValue("tools").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("mcp__visual__read_me", "mcp__visual__show_widget", "mcp__visual__html_render"),
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
        val listed = checkNotNull(ModeMcp { daemon(on = true) }.server("visual", "acme")).tools().map { it.jsonObject }
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
        assertNull(ModeMcp { daemon(on = null) }.server("visual", "acme"), "the mode ships off")
        val modes = daemon(on = true)
        val server = checkNotNull(ModeMcp { modes }.server("visual", "acme"))
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
        val server = checkNotNull(ModeMcp { modes }.server("visual", "acme"))
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
}
