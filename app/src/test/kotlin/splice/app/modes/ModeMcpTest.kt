// NEW: modes-mcp (Nov 21, 2026) — an mcp mode as the server a client meets: its tools and resources as its folder
// writes them, its handler's answers as tool results, and no server at all where the mode is off or was never offered.
package splice.app.modes

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.StatePaths
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
import java.nio.file.Files
import java.nio.file.Path

class ModeMcpTest {
    @TempDir
    lateinit var state: Path

    /** Answers `show_tide` with who asked, fails `sink_ship` in words, throws on anything else, and gives the page
     *  of the one resource its manifest declares with no file. */
    private class HarborMode : Mode {
        override fun answer(call: ModeCall): ModeAnswer = when (call.tool) {
            "show_tide" -> {
                val asked = call.session.caller?.wire ?: "a caller not known"
                ModeAnswer.Text("High at 04:10 on ${call.session.head} for ${call.session.id}, asked by $asked.")
            }
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
    private fun modes(theirs: Set<String> = emptySet()): ModeMcp = ModeMcp { held(theirs) }

    private fun held(theirs: Set<String> = emptySet()): DaemonModes {
        val everywhere =
            ModeOffer(on = true, only = emptyList(), off = listOf("globex"), callersOff = listOf(ModeCaller.DESKTOP))
        val nowhere = ModeOffer(on = false, only = emptyList(), off = emptyList(), callersOff = emptyList())
        val found =
            listOf(
                mode("dock", ModeKind.DOOR, everywhere),
                mode("harbor", ModeKind.MCP, everywhere),
                mode("quay", ModeKind.MCP, nowhere),
            )
        return DaemonModes(
            ModeCatalog(found, emptyList(), found.map { it.name }),
            mapOf("quay" to ModeSetting(heads = mapOf("acme" to true))),
            listOf("acme", "globex"),
            state,
            theirs.takeIf { it.isNotEmpty() }?.let(::personsOwn),
        )
    }

    /** The person's own MCP servers called [names], in a `~/.claude.json` of a home of their own. */
    private fun personsOwn(names: Set<String>): PersonMcpNames {
        val home = Files.createDirectories(state.resolve("home"))
        val servers = names.joinToString(",") { """"$it":{"command":"their-$it"}""" }
        Files.writeString(home.resolve(".claude.json"), """{"mcpServers":{$servers}}""")
        return PersonMcpNames(home, StatePaths(baseOverride = state.resolve("splice"), homeDir = home))
    }

    private fun text(result: JsonObject) = result.getValue(
        "content",
    ).jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content

    @Test
    fun `a client lists the mode's tools by their own names, with the _meta its folder writes`() {
        val tools = checkNotNull(modes().server("harbor", "acme", "head")).tools().map { it.jsonObject }
        assertEquals(listOf("show_tide", "sink_ship", "leak"), tools.map { it.getValue("name").jsonPrimitive.content })
        assertEquals("Does show_tide.", tools.first().getValue("description").jsonPrimitive.content)
        assertEquals("""{"ui":{"resourceUri":"ui://harbor/tide"}}""", tools.first().getValue("_meta").toString())
        assertEquals(setOf("name", "description", "inputSchema"), tools[1].keys)
        val server = checkNotNull(modes().server("harbor", "acme", "head"))
        assertEquals("Show the tide when asked.", server.instructions())
    }

    @Test
    fun `call results say who asked, include errors and never quote thrown exceptions`() = runBlocking {
        val server = checkNotNull(modes().server("harbor", "acme", "head"))
        val shown = server.call("show_tide", JsonObject(emptyMap()), "mcp-session-7")
        assertEquals("High at 04:10 on acme for mcp-session-7, asked by head.", text(shown))
        val bare = checkNotNull(modes().server("harbor", "acme", null)) { "a request that names no caller is answered" }
            .call("show_tide", JsonObject(emptyMap()), "mcp-session-8")
        assertEquals("High at 04:10 on acme for mcp-session-8, asked by a caller not known.", text(bare))
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
        val server = checkNotNull(modes().server("harbor", "acme", "head"))
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
    fun `a mode is a server only where it is on and for a caller it is offered to, and a door mode is never one`() {
        val served = modes()
        assertNull(served.server("harbor", "globex", "head"), "its manifest keeps it off globex")
        assertNull(served.server("dock", "acme", "head"), "a door mode's calls never leave splice")
        assertNull(served.server("quay", "globex", "head"))
        assertNull(served.server("quay", null, "head"), "splice.toml turned it on for acme alone")
        assertEquals("quay", checkNotNull(served.server("quay", "acme", "head")).let { "quay" })
        assertNull(served.server("nobody", "acme", "head"))
        assertEquals("quay", checkNotNull(served.server("quay", "acme", null)).let { "quay" }, "no caller named")
        assertNull(served.server("harbor", "acme", "desktop"), "a request naming Desktop is held to `[callers] off`")
    }

    @Test
    fun `a server name one of the person's own MCP servers has stays theirs, and the mode's row says so`() {
        val served = modes(theirs = setOf("quay"))
        assertNull(served.server("quay", "acme", null), "their server answers at the address")
        assertNull(served.server("quay", "acme", "head"))
        assertEquals(listOf("harbor"), served.on("acme", "door").map { it.manifest.server }, "no launch lists it")
        val rows = ModeRows(held(theirs = setOf("quay"))).all().getValue("modes").jsonArray.map { it.jsonObject }
            .associateBy { it.getValue("name").jsonPrimitive.content }
        assertEquals("true", rows.getValue("quay").getValue("server_taken").toString())
        assertEquals("false", rows.getValue("harbor").getValue("server_taken").toString())
        assertFalse("server_taken" in rows.getValue("dock"), "a door mode has no server")
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
