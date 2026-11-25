// What Claude Desktop's configuration lists for the mcp modes the daemon gives it, as a profile sync writes it: a mode
// on for every head and offered to Desktop is an entry naming splice's bridge and the mode's server, and nothing else
// is; a mode turned off is taken out; an install with no such mode gets exactly the configuration it had; and a server
// of the person's by a mode's name keeps the name.
package splice.app.desktop.setup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.mcp.McpFrontCheck
import splice.app.mcp.McpHarnessSyncSeams
import splice.app.mcp.stdio.McpStdioResolution
import splice.app.mcp.stdio.McpStdioTargets
import splice.app.modes.DaemonModes
import splice.app.modes.ModeMcp
import splice.app.modes.ModeRows
import splice.app.modes.PersonMcpNames
import splice.core.config.StatePaths
import splice.core.mode.ModeSetting
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.modes.api.FoundMode
import splice.modes.api.Mode
import splice.modes.api.ModeAnswer
import splice.modes.api.ModeCall
import splice.modes.api.ModeCaller
import splice.modes.api.ModeCatalog
import splice.modes.api.ModeKind
import splice.modes.api.ModeManifest
import splice.modes.api.ModeOffer
import splice.modes.api.ModeTool
import java.nio.file.Files
import java.nio.file.Path

class DesktopModeServersTest {
    @TempDir
    lateinit var tmp: Path

    private val home: Path get() = tmp.resolve("home")
    private val state: Path get() = tmp.resolve("state")
    private val profile: Path get() = tmp.resolve("config/Claude-3p")
    private val paths: StatePaths get() = StatePaths(baseOverride = state, homeDir = home)
    private val env = EnvReader { key ->
        when (key) {
            "SPLICE_STATE_DIR" -> state.toString()
            "SPLICE_CONFIG" -> tmp.resolve("splice.toml").toString()
            "XDG_CONFIG_HOME" -> tmp.resolve("config").toString()
            else -> null
        }
    }
    private val bridge: String get() = state.resolve("desktop-mcp").toAbsolutePath().toString()

    private class Answers : Mode {
        override fun answer(call: ModeCall): ModeAnswer = ModeAnswer.Text("ok")
    }

    private fun mode(name: String, kind: ModeKind, on: Boolean, callersOff: List<ModeCaller> = emptyList()) =
        FoundMode(
            ModeManifest(
                name,
                "A $name.",
                name,
                "splice.app.desktop.setup.Answers",
                listOf("look"),
                kind,
                ModeOffer(on = on, only = emptyList(), off = emptyList(), callersOff = callersOff),
            ),
            listOf(ModeTool("look", "Looks.", buildJsonObject { put("type", "object") }, null, "mcp__${name}__look")),
            emptyList(),
            null,
            Answers(),
        )

    /** `harbor` is an mcp mode on for every head; `quay` one splice.toml turns on for `acme` alone; `wharf` one on
     *  everywhere that its manifest leaves out for Desktop; `dock` a door mode on everywhere; `pier` an mcp mode
     *  off. */
    private fun modes(theirs: PersonMcpNames? = null): DaemonModes {
        val found = listOf(
            mode("dock", ModeKind.DOOR, on = true),
            mode("harbor", ModeKind.MCP, on = true),
            mode("pier", ModeKind.MCP, on = false),
            mode("quay", ModeKind.MCP, on = false),
            mode("wharf", ModeKind.MCP, on = true, callersOff = listOf(ModeCaller.DESKTOP)),
        )
        return DaemonModes(
            ModeCatalog(found, emptyList(), found.map { it.name }),
            mapOf("quay" to ModeSetting(heads = mapOf("acme" to true))),
            listOf("acme"),
            state,
            theirs,
        )
    }

    /** The profile's configuration brought up to date as the daemon does, with [held]'s modes; whether it changed. */
    private fun daemonSync(held: DaemonModes?): Boolean {
        val own = DesktopOwnMcp(state, ModeMcp { held })
        val mcp = SpliceDesktopMcp(env, home, paths, own)
        return DesktopProfileSync(env, "Linux", home, mcp = mcp).syncConfigInto(profile)
    }

    /** The same, as a command that runs without the daemon (`splice mcp sync`) does. */
    private fun commandSync(): Boolean =
        DesktopProfileSync(env, "Linux", home, mcp = SpliceDesktopMcp(env, home, paths)).syncConfigInto(profile)

    private fun servers(): JsonObject =
        Json.parseToJsonElement(Files.readString(profile.resolve("claude_desktop_config.json"))).jsonObject
            .getValue("mcpServers").jsonObject

    private fun bridged(): Map<String, List<String>> = servers().filter { (_, entry) ->
        entry.jsonObject["command"]?.jsonPrimitive?.content == bridge
    }.mapValues { (_, entry) -> entry.jsonObject.getValue("args").jsonArray.map { it.jsonPrimitive.content } }

    @Test
    fun `Desktop lists the mcp modes on for every head and offered to it, each through the bridge by name`() {
        Files.createDirectories(home)
        assertTrue(daemonSync(modes()))
        assertEquals(mapOf("harbor" to listOf("harbor")), bridged())
        val none = "no door mode, no off mode, no head-only mode, and no mode whose manifest leaves Desktop out"
        assertEquals(setOf("harbor"), servers().keys, none)
        assertTrue(Files.isExecutable(state.resolve("desktop-mcp")))
        assertFalse(commandSync(), "a command that runs without the daemon lists the same servers")
        assertEquals(mapOf("harbor" to listOf("harbor")), bridged())
    }

    @Test
    fun `a mode turned off is taken out of Desktop's configuration, by the daemon and by a command after it`() {
        Files.createDirectories(home)
        val held = modes()
        daemonSync(held)
        held.follow(held.settings + ("harbor" to ModeSetting(on = false)))
        assertTrue(daemonSync(held))
        assertEquals(emptyMap<String, List<String>>(), bridged())
        assertFalse(commandSync())
        assertEquals(emptyMap<String, List<String>>(), bridged())
        assertFalse(Files.exists(state.resolve("desktop-own-mcp.json")), "no record is kept while no mode is given")
    }

    @Test
    fun `with no mcp mode on, Desktop's configuration is left exactly as it was`() {
        Files.createDirectories(home)
        val config = Files.createDirectories(profile).resolve("claude_desktop_config.json")
        val theirs = "{\n  \"mcpServers\": {\"notes\": {\"command\": \"notes-mcp\"}},\n  \"preferences\": {}\n}\n"
        Files.writeString(config, theirs)
        val held = modes().also { it.follow(it.settings + ("harbor" to ModeSetting(on = false))) }
        assertFalse(daemonSync(held))
        assertFalse(daemonSync(null), "a daemon that carries no modes")
        assertEquals(theirs, Files.readString(config))
        assertFalse(Files.exists(state.resolve("desktop-own-mcp.json")))
        assertFalse(Files.exists(state.resolve("desktop-mcp")), "nothing is bridged, so no bridge is laid")
    }

    @Test
    fun `a server of the person's by a mode's name keeps the name in Desktop and at the bridge`() {
        Files.createDirectories(home)
        // A server bound to a folder of theirs, which Desktop starts itself, so its entry shows whose it is.
        val project = Files.createDirectories(tmp.resolve("project"))
        val theirs = """{"command":"uvx","args":["their-harbor","$project"]}"""
        Files.writeString(home.resolve(".claude.json"), """{"mcpServers":{"harbor":$theirs}}""")
        val held = modes(PersonMcpNames(home, paths, env, "Linux"))
        daemonSync(held)
        assertEquals(theirs, servers().getValue("harbor").toString())
        assertEquals("true", ModeRows(held).row(checkNotNull(held.mode("harbor"))).getValue("server_taken").toString())
        assertNull(ModeMcp { held }.server("harbor", null, null), "the daemon's address for the name is theirs too")
        val seams = McpHarnessSyncSeams(osName = "Linux", frontLive = McpFrontCheck { _, _ -> false })
        val reached = McpStdioTargets(home, paths, env, LogSink {}, seams).resolve("harbor")
        val why = (reached as McpStdioResolution.Missing).why
        assertTrue("Claude Desktop starts it itself" in why, "the bridge reaches theirs, never the mode: $why")
    }

    @Test
    fun `a server of the person's in Desktop's own file under a mode's name stays, and the mode's row says so`() {
        Files.createDirectories(home)
        val config = Files.createDirectories(profile).resolve("claude_desktop_config.json")
        Files.writeString(config, """{"mcpServers":{"harbor":{"command":"/opt/harbor/bin/serve"}}}""")
        val held = modes(PersonMcpNames(home, paths, env, "Linux"))
        daemonSync(held)
        assertEquals("""{"command":"/opt/harbor/bin/serve"}""", servers().getValue("harbor").toString())
        assertEquals("true", ModeRows(held).row(checkNotNull(held.mode("harbor"))).getValue("server_taken").toString())
        assertNull(ModeMcp { held }.server("harbor", null, null), "the daemon's address for the name is theirs too")
        assertFalse("harbor" in ModeMcp { held }.names("", "door"), "no launch is given the mode")
    }

    @Test
    fun `after splice's state folder moves, the mode is still served and listed, under one current entry`() {
        Files.createDirectories(home)
        val config = Files.createDirectories(profile).resolve("claude_desktop_config.json")
        // the entry an earlier splice laid while its state folder was elsewhere, as at a move of the state root
        val earlier = tmp.resolve("home/.claude-codex/state/desktop-mcp").toAbsolutePath()
        Files.writeString(config, """{"mcpServers":{"harbor":{"command":"$earlier","args":["harbor"]}}}""")
        val held = modes(PersonMcpNames(home, paths, env, "Linux"))
        assertEquals("false", ModeRows(held).row(checkNotNull(held.mode("harbor"))).getValue("server_taken").toString())
        assertTrue("harbor" in ModeMcp { held }.names("", "door"), "a launch is given the mode")
        assertTrue(ModeMcp { held }.server("harbor", null, null) != null, "its address answers the mode")
        assertTrue(daemonSync(held))
        assertEquals(mapOf("harbor" to listOf("harbor")), bridged(), "the current bridge entry")
        assertEquals(setOf("harbor"), servers().keys, "and no stale one beside it")
    }

    @Test
    fun `a Desktop file kept as a link is read again after its target changes`() {
        Files.createDirectories(home)
        val target = Files.createDirectories(tmp.resolve("dotfiles")).resolve("claude_desktop_config.json")
        Files.writeString(target, """{"mcpServers":{}}""")
        Files.createSymbolicLink(Files.createDirectories(profile).resolve("claude_desktop_config.json"), target)
        val held = modes(PersonMcpNames(home, paths, env, "Linux"))
        val harbor = checkNotNull(held.mode("harbor"))
        assertEquals("false", ModeRows(held).row(harbor).getValue("server_taken").toString())
        Files.writeString(target, """{"mcpServers":{"harbor":{"command":"/opt/harbor/bin/serve"}}}""")
        assertEquals("true", ModeRows(held).row(harbor).getValue("server_taken").toString(), "their new server is seen")
    }
}
