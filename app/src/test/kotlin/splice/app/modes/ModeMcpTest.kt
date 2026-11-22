// NEW: modes-mcp (Nov 21, 2026) — an mcp mode as the server a client meets: its tools and resources as its folder
// writes them, its handler's answers as tool results, and no server at all where the mode is off or was never offered.
package splice.app.modes

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.mode.ModeSetting
import splice.modes.api.FoundMode
import splice.modes.api.Mode
import splice.modes.api.ModeAnswer
import splice.modes.api.ModeCall
import splice.modes.api.ModeCaller
import splice.modes.api.ModeCatalog
import splice.modes.api.ModeKind
import splice.modes.api.ModeManifest
import splice.modes.api.ModeOffer
import splice.modes.api.ModeResource
import splice.modes.api.ModeTool
import java.nio.file.Path

class ModeMcpTest {
    @TempDir
    lateinit var state: Path

    /** Answers `show_tide` with who asked, fails `sink_ship` in words, throws on anything else, and gives the page
     *  of the one resource its manifest declares with no file. */
    private class HarborMode : Mode {
        override fun answer(call: ModeCall): ModeAnswer = when (call.tool) {
            "show_tide" -> ModeAnswer.Text("High at 04:10 on ${call.session.head} for ${call.session.id}.")
            "sink_ship" -> ModeAnswer.Error("No ship to sink.")
            else -> error("a secret the model must not read")
        }

        override fun resource(uri: String): String? = "<p>live</p>".takeIf { uri == "ui://harbor/live" }
    }

    private fun tool(name: String, meta: JsonObject? = null) =
        ModeTool(name, "Does $name.", buildJsonObject { put("type", "object") }, meta, "mcp__harbor__$name")

    private fun mode(name: String, kind: ModeKind, offer: ModeOffer) = FoundMode(
        ModeManifest(
            name,
            "A harbor.",
            name,
            "splice.app.modes.HarborMode",
            listOf("show_tide", "sink_ship", "leak"),
            kind,
            offer,
        ),
        listOf(
            tool(
                "show_tide",
                buildJsonObject { put("ui", buildJsonObject { put("resourceUri", "ui://harbor/tide") }) },
            ),
            tool("sink_ship"),
            tool("leak"),
        ),
        listOf(
            ModeResource("ui://harbor/tide", "tide", "text/html;profile=mcp-app", null, "<h1>Tide</h1>"),
            ModeResource(
                "ui://harbor/live",
                "live",
                "text/html;profile=mcp-app",
                buildJsonObject { put("ui", "x") },
                null,
            ),
        ),
        "Show the tide when asked.".takeIf { kind == ModeKind.MCP },
        HarborMode(),
    )

    /** `harbor` is an mcp mode on by its manifest, never on the head `globex` and never offered to Desktop; `dock` is
     *  a door mode of the same shape; `quay` is an mcp mode splice.toml turns on for `acme` alone. */
    private fun modes(): ModeMcp {
        val everywhere =
            ModeOffer(on = true, only = emptyList(), off = listOf("globex"), callersOff = listOf(ModeCaller.DESKTOP))
        val nowhere = ModeOffer(on = false, only = emptyList(), off = emptyList(), callersOff = emptyList())
        val found =
            listOf(
                mode("dock", ModeKind.DOOR, everywhere),
                mode("harbor", ModeKind.MCP, everywhere),
                mode("quay", ModeKind.MCP, nowhere),
            )
        val held = DaemonModes(
            ModeCatalog(found, emptyList(), found.map { it.name }),
            mapOf("quay" to ModeSetting(heads = mapOf("acme" to true))),
            listOf("acme", "globex"),
            state,
        )
        return ModeMcp { held }
    }

    private fun text(result: JsonObject) = result.getValue(
        "content",
    ).jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content

    @Test
    fun `a client lists the mode's tools by their own names, with the _meta its folder writes`() {
        val tools = checkNotNull(modes().server("harbor", "acme")).tools().map { it.jsonObject }
        assertEquals(listOf("show_tide", "sink_ship", "leak"), tools.map { it.getValue("name").jsonPrimitive.content })
        assertEquals("Does show_tide.", tools.first().getValue("description").jsonPrimitive.content)
        assertEquals("""{"ui":{"resourceUri":"ui://harbor/tide"}}""", tools.first().getValue("_meta").toString())
        assertEquals(setOf("name", "description", "inputSchema"), tools[1].keys)
        assertEquals("Show the tide when asked.", checkNotNull(modes().server("harbor", "acme")).instructions())
    }

    @Test
    fun `a call is the handler's answer, its error an error result, and what it throws is named, never quoted`() {
        val server = checkNotNull(modes().server("harbor", "acme"))
        val shown = server.call("show_tide", JsonObject(emptyMap()), "mcp-session-7")
        assertEquals("High at 04:10 on acme for mcp-session-7.", text(shown))
        assertEquals("false", shown.getValue("isError").toString())
        val refused = server.call("sink_ship", JsonObject(emptyMap()), null)
        assertEquals("No ship to sink." to "true", text(refused) to refused.getValue("isError").toString())
        val thrown = server.call("leak", JsonObject(emptyMap()), null)
        assertEquals(
            "mode harbor failed: IllegalStateException" to "true",
            text(thrown) to thrown.getValue("isError").toString(),
        )
    }

    @Test
    fun `its resources are listed, a file's content is read from the folder and the other's from the handler`() {
        val server = checkNotNull(modes().server("harbor", "acme"))
        val listed = server.resources().map { it.jsonObject }
        assertEquals(
            listOf("ui://harbor/tide", "ui://harbor/live"),
            listed.map { it.getValue("uri").jsonPrimitive.content },
        )
        assertEquals("""{"ui":"x"}""", listed[1].getValue("_meta").toString())
        assertEquals(
            "<h1>Tide</h1>",
            checkNotNull(server.read("ui://harbor/tide")).single().jsonObject.getValue("text").jsonPrimitive.content,
        )
        val live = checkNotNull(server.read("ui://harbor/live")).single().jsonObject
        assertEquals(
            "<p>live</p>" to "text/html;profile=mcp-app",
            live.getValue("text").jsonPrimitive.content to live.getValue("mimeType").jsonPrimitive.content,
        )
        assertNull(server.read("ui://harbor/chart"))
    }

    @Test
    fun `a mode is a server only where it is on, and a door mode is never one`() {
        val served = modes()
        assertNull(served.server("harbor", "globex"), "its manifest keeps it off globex")
        assertNull(served.server("dock", "acme"), "a door mode's calls never leave splice")
        assertNull(served.server("quay", "globex"))
        assertNull(served.server("quay", null), "splice.toml turned it on for acme alone")
        assertEquals("quay", checkNotNull(served.server("quay", "acme")).let { "quay" })
        assertNull(served.server("nobody", "acme"))
    }

    @Test
    fun `a launch lists the mcp modes on for its head and offered to its caller`() {
        val served = modes()
        assertEquals(listOf("harbor", "quay"), served.on("acme", "door").map { it.manifest.server })
        assertEquals(listOf("harbor", "quay"), served.on("acme", "head").map { it.manifest.server })
        assertEquals(
            listOf("quay"),
            served.on("acme", "desktop").map {
                it.manifest.server
            },
            "harbor is never offered to Desktop",
        )
        assertEquals(emptyList<String>(), served.on("globex", "door").map { it.manifest.server })
        assertEquals(emptyList<String>(), served.on("acme", "somebody").map { it.manifest.server })
    }
}
