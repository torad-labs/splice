// Terminal launch profiles (issue #791, comment §4): what a profile's folder holds after `splice launch claude
// --profile <name>` rebuilds it, and that the person's `~/.claude` and `~/.claude.json` are read and never written.
package splice.client.profile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.topology.LaunchProfileConfig
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.readText
import kotlin.io.path.writeText

class LaunchProfileBuildTest {
    @TempDir lateinit var home: Path

    private val claude: Path get() = home.resolve(".claude")
    private val folder: Path get() = home.resolve("profile")
    private val registry = JsonObject(
        mapOf(
            "registered" to JsonObject(mapOf("command" to JsonPrimitive("reg-server"))),
            "own" to JsonObject(mapOf("command" to JsonPrimitive("registry-copy"))),
        ),
    )

    private fun write(path: Path, text: String) {
        Files.createDirectories(path.parent)
        path.writeText(text)
    }

    /** A Claude folder with hooks, plugins, env, a CLAUDE.md with marked blocks, skills, agents, commands, history. */
    private fun person() {
        write(
            claude.resolve("settings.json"),
            """
            {"theme":"dark",
             "hooks":{
               "SessionStart":[{"hooks":[{"type":"command","command":"start.sh"}]}],
               "PreToolUse":[{"matcher":"Bash","hooks":[
                 {"type":"command","command":"/x/notify.sh --loud"},{"type":"command","command":"guard.sh"}]}]},
             "enabledPlugins":{"kept@market":true,"dropped@market":true,"off@market":false},
             "env":{"ANTHROPIC_BASE_URL":"https://elsewhere","MY_VAR":"1"}}
            """.trimIndent(),
        )
        write(
            claude.resolve("CLAUDE.md"),
            "# Mine\nkeep me\n<!-- team-notes -->\nteam text\n<!-- /team-notes -->\n" +
                "# BEGIN other-tool\nother text\n# END other-tool\nend\n",
        )
        write(claude.resolve("skills/mine/SKILL.md"), "mine")
        write(claude.resolve("skills/team-skill/SKILL.md"), "team")
        write(claude.resolve("agents/helper.md"), "agent")
        write(claude.resolve("commands/team-cmd.md"), "team")
        write(claude.resolve("commands/go.md"), "go")
        write(claude.resolve("projects/-work/session.jsonl"), "{}")
        write(claude.resolve("sessions/1.json"), "{}")
        listOf("kept", "dropped").forEach { name ->
            val at = install(name)
            write(at.resolve("hooks/hooks.json"), """{"hooks":{"Stop":[]}}""")
            write(at.resolve(".claude-plugin/plugin.json"), """{"name":"$name","hooks":"./more-hooks.json"}""")
            write(at.resolve("skills/s/SKILL.md"), "plugin skill")
        }
        write(
            claude.resolve("plugins/installed_plugins.json"),
            """{"version":2,"plugins":{
              "kept@market":[{"scope":"user","installPath":"${install("kept")}","version":"1.0.0"}],
              "dropped@market":[{"scope":"user","installPath":"${install("dropped")}","version":"1.0.0"}]}}""",
        )
        write(claude.resolve("plugins/known_marketplaces.json"), """{"market":{"installLocation":"/m"}}""")
        write(
            home.resolve(".claude.json"),
            """{"hasCompletedOnboarding":true,"oauthAccount":{"emailAddress":"x"},
               "projects":{"/work":{"hasTrustDialogAccepted":true}},
               "mcpServers":{"own":{"command":"mine","env":{"TOKEN":"secret"}}}}""",
        )
    }

    private fun build(profile: LaunchProfileConfig, owned: Set<String> = setOf("ANTHROPIC_BASE_URL")): ProfileBuilt =
        LaunchProfileBuild(home, home.resolve("unused-root"), registry)
            .build("work", profile.copy(dir = profile.dir ?: folder.toString()), owned)

    private fun json(path: Path): JsonObject = Json.parseToJsonElement(path.readText()).jsonObject

    /** Every path under [root] (links as links), with a file's bytes and a link's target. */
    private fun snapshot(root: Path): Map<String, String> = Files.walk(root).use { paths ->
        paths.toList().associate { path ->
            val what = when {
                Files.isSymbolicLink(path) -> "link:" + Files.readSymbolicLink(path)
                Files.isRegularFile(path) -> "file:" + path.readText() + Files.getLastModifiedTime(path)
                else -> "dir"
            }
            root.relativize(path).toString() to what
        }
    }

    @Test
    fun `own hooks stay less every dropped word and event, and dropped plugins are written disabled`() {
        person()
        val profile = LaunchProfileConfig(dropHooks = listOf("notify.sh", "SessionStart"), keepPlugins = listOf("kept"))
        val built = build(profile)
        assertInstanceOf(ProfileBuilt.Ready::class.java, built)
        val settings = json(folder.resolve("settings.json"))
        val hooks = settings.getValue("hooks").jsonObject
        assertEquals(setOf("PreToolUse"), hooks.keys)
        val commands = hooks.getValue("PreToolUse").jsonArray.single().jsonObject.getValue("hooks").jsonArray
            .map { it.jsonObject.getValue("command").jsonPrimitive.content }
        assertEquals(listOf("guard.sh"), commands)
        val enabled = settings.getValue("enabledPlugins").jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertEquals(mapOf("kept@market" to "true", "dropped@market" to "false", "off@market" to "false"), enabled)
        assertEquals("dark", settings.getValue("theme").jsonPrimitive.content)
    }

    @Test
    fun `keep_hooks none carries no hooks, and the door's variables leave the settings env`() {
        person()
        build(LaunchProfileConfig(keepHooks = "none"))
        val settings = json(folder.resolve("settings.json"))
        assertNull(settings["hooks"])
        assertEquals(setOf("MY_VAR"), settings.getValue("env").jsonObject.keys)
    }

    @Test
    fun `a kept plugin loads from the profile without its hooks, and only it is installed there`() {
        person()
        build(LaunchProfileConfig(keepPlugins = listOf("kept@market")))
        val installed = json(folder.resolve("plugins/installed_plugins.json")).getValue("plugins").jsonObject
        assertEquals(setOf("kept@market"), installed.keys)
        val at = installedAt(installed, "kept@market")
        assertTrue(at.startsWith(folder.resolve("plugins/cache")), at.toString())
        assertFalse(Files.exists(at.resolve("hooks"), LinkOption.NOFOLLOW_LINKS), "the plugin's hooks stay out")
        assertNull(json(at.resolve(".claude-plugin/plugin.json"))["hooks"], "the manifest's hooks stay out")
        assertEquals("plugin skill", at.resolve("skills/s/SKILL.md").readText())
        val known = json(folder.resolve("plugins/known_marketplaces.json"))
        assertEquals("""{"market":{"installLocation":"/m"}}""", known.toString())
    }

    @Test
    fun `with keep_hooks all a kept plugin carries its hooks`() {
        person()
        build(LaunchProfileConfig(keepHooks = "all", keepPlugins = listOf("kept")))
        val installed = json(folder.resolve("plugins/installed_plugins.json")).getValue("plugins").jsonObject
        val at = installedAt(installed, "kept@market")
        assertTrue(Files.isRegularFile(at.resolve("hooks/hooks.json")))
        val manifest = json(at.resolve(".claude-plugin/plugin.json"))
        assertEquals("./more-hooks.json", manifest.getValue("hooks").jsonPrimitive.content)
    }

    @Test
    fun `no kept plugin installs none`() {
        person()
        build(LaunchProfileConfig())
        assertEquals(JsonObject(emptyMap()), json(folder.resolve("plugins/installed_plugins.json")).getValue("plugins"))
    }

    @Test
    fun `CLAUDE-md loses the dropped blocks and keeps the rest`() {
        person()
        build(LaunchProfileConfig(dropBlocks = listOf("team-notes", "other-tool")))
        assertEquals("# Mine\nkeep me\nend\n", folder.resolve("CLAUDE.md").readText())
    }

    @Test
    fun `an unclosed block is kept whole and said`() {
        person()
        write(claude.resolve("CLAUDE.md"), "a\n<!-- open -->\nb\n")
        val profile = LaunchProfileConfig(dropBlocks = listOf("open"))
        val built = assertInstanceOf(ProfileBuilt.Ready::class.java, build(profile))
        assertEquals("a\n<!-- open -->\nb\n", folder.resolve("CLAUDE.md").readText())
        assertTrue(built.notes.any { "open" in it }, built.notes.toString())
    }

    @Test
    fun `a CLAUDE-md the person removed is removed from the profile`() {
        person()
        build(LaunchProfileConfig())
        Files.delete(claude.resolve("CLAUDE.md"))
        build(LaunchProfileConfig())
        assertFalse(Files.exists(folder.resolve("CLAUDE.md")))
    }

    @Test
    fun `skills, agents and commands are linked one by one, less the dropped names, and stale links go`() {
        person()
        build(LaunchProfileConfig(dropNames = listOf("team-")))
        assertEquals(listOf("mine"), names(folder.resolve("skills")))
        assertEquals(listOf("helper.md"), names(folder.resolve("agents")))
        assertEquals(listOf("go.md"), names(folder.resolve("commands")))
        assertEquals(claude.resolve("skills/mine"), Files.readSymbolicLink(folder.resolve("skills/mine")))

        Files.delete(claude.resolve("commands/go.md"))
        build(LaunchProfileConfig(dropNames = listOf("mine")))
        assertEquals(listOf("team-skill"), names(folder.resolve("skills")))
        assertEquals(listOf("team-cmd.md"), names(folder.resolve("commands")))
    }

    @Test
    fun `projects and sessions link to the person's, so resume sees every session`() {
        person()
        build(LaunchProfileConfig())
        assertEquals(claude.resolve("projects"), Files.readSymbolicLink(folder.resolve("projects")))
        assertEquals(claude.resolve("sessions"), Files.readSymbolicLink(folder.resolve("sessions")))
        assertEquals("{}", folder.resolve("projects/-work/session.jsonl").readText())
    }

    @Test
    fun `claude-json carries the person's and the registry's MCP servers owner-only, and never the Claude login`() {
        person()
        build(LaunchProfileConfig())
        val file = folder.resolve(".claude.json")
        val config = json(file)
        val servers = config.getValue("mcpServers").jsonObject
        assertEquals(setOf("own", "registered"), servers.keys)
        assertEquals("mine", servers.getValue("own").jsonObject.getValue("command").jsonPrimitive.content)
        assertNull(config["oauthAccount"])
        assertEquals(JsonPrimitive(true), config.getValue("hasCompletedOnboarding"))
        assertTrue(config.getValue("projects").jsonObject.containsKey("/work"))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
    }

    @Test
    fun `what Claude Code wrote in the profile's claude-json is kept, and mcpServers is rebuilt`() {
        person()
        build(LaunchProfileConfig())
        val file = folder.resolve(".claude.json")
        val claudeWrote = mapOf("numStartups" to JsonPrimitive(7), "mcpServers" to JsonArray(emptyList()))
        val written = JsonObject(json(file) + claudeWrote)
        file.writeText(written.toString())
        build(LaunchProfileConfig())
        assertEquals(JsonPrimitive(7), json(file).getValue("numStartups"))
        assertEquals(setOf("own", "registered"), json(file).getValue("mcpServers").jsonObject.keys)
    }

    @Test
    fun `the person's Claude folder and claude-json are never written`() {
        person()
        val before = snapshot(claude)
        val config = home.resolve(".claude.json").readText()
        val first = LaunchProfileConfig(dropBlocks = listOf("team-notes"), dropNames = listOf("team-"))
        build(first.copy(keepPlugins = listOf("kept")))
        build(LaunchProfileConfig(keepHooks = "all", keepPlugins = listOf("kept", "dropped")))
        assertEquals(before, snapshot(claude))
        assertEquals(config, home.resolve(".claude.json").readText())
    }

    @Test
    fun `a dir that leads to ~-claude through a link is refused and writes nothing there`() {
        person()
        Files.createSymbolicLink(home.resolve("sneaky"), claude)
        val before = snapshot(claude)
        val built = build(LaunchProfileConfig(dir = home.resolve("sneaky").toString()))
        assertInstanceOf(ProfileBuilt.Refused::class.java, built)
        assertEquals(before, snapshot(claude))
    }

    @Test
    fun `a folder holding files splice did not put there is refused and left as it is`() {
        person()
        write(folder.resolve("settings.json"), "mine")
        assertInstanceOf(ProfileBuilt.Refused::class.java, build(LaunchProfileConfig()))
        assertEquals("mine", folder.resolve("settings.json").readText())
    }

    @Test
    fun `settings that can't be read stop the launch rather than run unfiltered`() {
        person()
        write(claude.resolve("settings.json"), "{not json")
        assertInstanceOf(ProfileBuilt.Refused::class.java, build(LaunchProfileConfig()))
    }

    private fun names(dir: Path): List<String> =
        Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

    private fun install(name: String): Path = home.resolve("installs/$name/1.0.0")

    private fun installedAt(installed: JsonObject, id: String): Path =
        Path.of(installed.getValue(id).jsonArray.single().jsonObject.getValue("installPath").jsonPrimitive.content)
}
