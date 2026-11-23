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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

class DesktopProfileSyncTest {
    // Changed Nov 23, 2026 (desktop-3p-parity): Splice's MCP servers now reach the gateway profile as every harness
    // gets them. A server Splice hosts and a remote HTTP server, both skipped before, are entries naming the stdio
    // bridge; a server Desktop starts itself is still a plain command.
    @Test
    fun `syncInto gives 3P Splice's MCP servers through the bridge and 1P settings without overwriting 3P entries`(
        @TempDir tmp: Path,
    ) {
        val configRoot = tmp.resolve("config")
        val sourceDir = Files.createDirectories(configRoot.resolve("Claude"))
        Files.writeString(sourceDir.resolve("claude_desktop_config.json"), SAMPLE_1P_CONFIG)
        Files.createDirectories(tmp.resolve("project"))
        Files.writeString(tmp.resolve(".claude.json"), sampleClaudeJson(tmp.resolve("project")))
        val targetAppData = Files.createDirectories(configRoot.resolve("claude-splice/Claude-3p"))
        Files.writeString(targetAppData.resolve("claude_desktop_config.json"), SAMPLE_3P_CONFIG)

        val sync = DesktopProfileSync(env = testEnv(tmp, configRoot), osName = "Linux", home = tmp)
        assertTrue(sync.syncInto(targetAppData))

        val text = Files.readString(targetAppData.resolve("claude_desktop_config.json"))
        val written = Json.parseToJsonElement(text).jsonObject
        val servers = written.getValue("mcpServers").jsonObject
        val bridge = tmp.resolve("state/desktop-mcp").toAbsolutePath().toString()
        assertEquals("custom-3p-cmd", command(servers, "shared"))
        assertEquals("npx", command(servers, "filesystem"))
        assertEquals(bridge, command(servers, "ast-grep"), "a server Splice hosts goes through the bridge")
        assertEquals(listOf("ast-grep"), args(servers, "ast-grep"))
        assertEquals(bridge, command(servers, "remote-http"), "a server at a URL goes through the bridge")
        assertEquals("uvx", command(servers, "scoped"), "a server Splice does not host is started by Desktop")
        assertFalse("type" in servers.getValue("scoped").jsonObject)
        assertFalse("example.invalid" in text || "Authorization" in text || "SECRET" in text, "no address or header")
        assertTrue(Files.isExecutable(tmp.resolve("state/desktop-mcp")), "the bridge program is laid")
        val prefs = written.getValue("preferences").jsonObject
        assertEquals("light", prefs.getValue("theme").jsonPrimitive.content)
        assertEquals("true", prefs.getValue("quickEntry").jsonPrimitive.content)
        assertEquals("/home/user/cowork", written.getValue("coworkUserFilesPath").jsonPrimitive.content)
        assertEquals("true", written.getValue("isHardwareAccelerationDisabled").jsonPrimitive.content)
        assertFalse("secretToken" in written)
        assertFalse(sync.syncInto(targetAppData))
    }

    @Test
    fun `bridge entries are kept current and taken out when their server is gone, the person's entries never`(
        @TempDir tmp: Path,
    ) {
        val target = Files.createDirectories(tmp.resolve("Claude-3p"))
        val program = DesktopPrograms(tmp.resolve("state"))
        val bridge = program.mcp.toString()
        Files.writeString(
            target.resolve("claude_desktop_config.json"),
            """{"mcpServers":{"mine":{"command":"my-own","args":["x"]},""" +
                """"stale":{"command":"$bridge","args":["stale"]}}}""",
        )
        var wanted = DesktopMcpWanted(stdio(), listOf("hosted", "mine", "remote"), program)
        val sync = DesktopProfileSync(testEnv(tmp, tmp), "Linux", tmp, mcp = { wanted })

        assertTrue(sync.syncConfigInto(target))
        val first = servers(target)
        assertEquals(setOf("mine", "hosted", "remote", "started"), first.keys)
        assertEquals("my-own", command(first, "mine"), "a server the person has under the same name keeps theirs")
        assertEquals(bridge, command(first, "hosted"))
        assertEquals("stdio-cmd", command(first, "started"))

        wanted = DesktopMcpWanted(stdio(), listOf("hosted"), program)
        assertTrue(sync.syncConfigInto(target))
        assertEquals(setOf("mine", "hosted", "started"), servers(target).keys, "a server that is gone is taken out")
        assertFalse(sync.syncConfigInto(target), "a sync with nothing new writes nothing")
    }

    @Test
    fun `syncInto copies missing 1P Desktop extensions with their programs runnable and links kept inside`(
        @TempDir tmp: Path,
    ) {
        val configRoot = tmp.resolve("config")
        val sourceDir = Files.createDirectories(configRoot.resolve("Claude"))
        seedSampleExtensions(sourceDir)
        val targetAppData = Files.createDirectories(configRoot.resolve("claude-splice/Claude-3p"))
        val existingExt = Files.createDirectories(targetAppData.resolve("Claude Extensions/already.installed"))
        Files.writeString(existingExt.resolve("manifest.json"), """{"name":"keep-3p"}""")

        val sync = DesktopProfileSync(env = testEnv(tmp, configRoot), osName = "Linux", home = tmp, mcp = { none() })
        assertTrue(sync.syncInto(targetAppData))

        val copied = targetAppData.resolve("Claude Extensions/ext.desktop.commander")
        assertTrue(Files.isRegularFile(copied.resolve("manifest.json")))
        assertTrue(Files.isExecutable(copied.resolve("server/bin/run.sh")), "a bundled program keeps its +x")
        assertTrue(Files.isSymbolicLink(copied.resolve("server/current")), "a link inside the extension is kept")
        assertEquals("bin", Files.readSymbolicLink(copied.resolve("server/current")).toString())
        assertFalse(Files.exists(copied.resolve("escape.link")), "an absolute link out of the extension is dropped")
        assertFalse(Files.exists(copied.resolve("climb.link")), "a relative link that climbs out is dropped")
        assertFalse(Files.exists(targetAppData.resolve("Claude Extensions/.ext.desktop.commander.splice-copy")))
        assertEquals(
            """{"name":"keep-3p"}""",
            Files.readString(targetAppData.resolve("Claude Extensions/already.installed/manifest.json")),
        )
        val settings = Json.parseToJsonElement(
            Files.readString(targetAppData.resolve("Claude Extensions Settings/ext.desktop.commander.json")),
        ).jsonObject
        assertEquals("true", settings.getValue("isEnabled").jsonPrimitive.content)
        val reg = Json.parseToJsonElement(
            Files.readString(targetAppData.resolve("extensions-installations.json")),
        ).jsonObject.getValue("extensions").jsonObject
        assertTrue("ext.desktop.commander" in reg)
        assertFalse("missing.dir.ext" in reg)
        assertFalse(sync.syncInto(targetAppData))
    }

    @Test
    fun `syncInto gives the 3P skills plugin the person's Claude Code and 1P Desktop skills, not Anthropic's`(
        @TempDir tmp: Path,
    ) {
        val configRoot = tmp.resolve("config")
        val sourceDir = Files.createDirectories(configRoot.resolve("Claude"))
        seedCodeSkills(tmp)
        val onePlugin = Files.createDirectories(sourceDir.resolve("local-agent-mode-sessions/skills-plugin/org/acct"))
        skill(onePlugin.resolve("skills"), "desktop-made", "made in Desktop")
        skill(onePlugin.resolve("skills"), "docs", "anthropic's")
        Files.writeString(
            onePlugin.resolve("manifest.json"),
            """{"skills":[{"name":"desktop-made","creatorType":"user","description":"made in Desktop"},""" +
                """{"name":"docs","creatorType":"anthropic"}]}""",
        )
        val targetAppData = Files.createDirectories(configRoot.resolve("claude-splice/Claude-3p"))
        val plugin = Files.createDirectories(targetAppData.resolve("local-agent-mode-sessions/skills-plugin/o/a"))
        skill(plugin.resolve("skills"), "docx", "bundled")
        Files.writeString(plugin.resolve("manifest.json"), """{"skills":[{"name":"docx","creatorType":"anthropic"}]}""")
        skill(plugin.resolve("skills"), "code-skill-unlisted", "already a folder here")

        val sync = DesktopProfileSync(env = testEnv(tmp, configRoot), osName = "Linux", home = tmp, mcp = { none() })
        assertTrue(sync.syncInto(targetAppData))

        val listed = Json.parseToJsonElement(Files.readString(plugin.resolve("manifest.json"))).jsonObject
            .getValue("skills").jsonArray.map { it.jsonObject }
        val byName = listed.associateBy { it.getValue("name").jsonPrimitive.content }
        assertEquals(setOf("docx", "code-skill", "linked-skill", "desktop-made"), byName.keys)
        assertEquals("user", byName.getValue("code-skill").getValue("creatorType").jsonPrimitive.content)
        assertEquals("false", byName.getValue("code-skill").getValue("syncManaged").jsonPrimitive.content)
        assertEquals(
            "Folded description across lines",
            byName.getValue("code-skill").getValue("description").jsonPrimitive.content,
        )
        assertTrue(Files.isRegularFile(plugin.resolve("skills/linked-skill/SKILL.md")), "a linked skill is copied")
        assertFalse(Files.isSymbolicLink(plugin.resolve("skills/linked-skill")))
        val docx = Files.readString(plugin.resolve("skills/docx/SKILL.md"))
        assertTrue("description: bundled" in docx, "a skill already in the plugin is left as it is")
        assertFalse(Files.exists(plugin.resolve("skills/docs")), "Anthropic's own 1P skills are Desktop's to give")
        assertFalse(sync.syncInto(targetAppData))
    }

    private fun servers(target: Path): JsonObject = Json.parseToJsonElement(
        Files.readString(target.resolve("claude_desktop_config.json")),
    ).jsonObject.getValue("mcpServers").jsonObject

    private fun command(servers: JsonObject, name: String): String =
        servers.getValue(name).jsonObject.getValue("command").jsonPrimitive.content

    private fun args(servers: JsonObject, name: String): List<String> =
        servers.getValue(name).jsonObject.getValue("args").jsonArray.map { it.jsonPrimitive.content }

    private fun stdio(): JsonObject = buildJsonObject {
        put("started", buildJsonObject { put("command", "stdio-cmd") })
        put("mine", buildJsonObject { put("command", "would-not-replace") })
    }

    private fun none(): DesktopMcpWanted = DesktopMcpWanted()

    private fun testEnv(home: Path, configRoot: Path): EnvReader = EnvReader { key ->
        when (key) {
            "XDG_CONFIG_HOME" -> configRoot.toString()
            "HOME" -> home.toString()
            "SPLICE_STATE_DIR" -> home.resolve("state").toString()
            else -> null
        }
    }

    private fun seedSampleExtensions(sourceDir: Path) {
        val extDir = Files.createDirectories(sourceDir.resolve("Claude Extensions/ext.desktop.commander"))
        Files.writeString(extDir.resolve("manifest.json"), """{"name":"desktop-commander"}""")
        val bin = Files.createDirectories(extDir.resolve("server/bin"))
        Files.writeString(bin.resolve("run.sh"), "#!/bin/sh\necho ok\n")
        bin.resolve("run.sh").toFile().setExecutable(true, true)
        Files.createSymbolicLink(extDir.resolve("server/current"), Path.of("bin"))
        Files.writeString(sourceDir.resolve("claude_desktop_config.json"), "{}")
        Files.createSymbolicLink(extDir.resolve("escape.link"), sourceDir.resolve("claude_desktop_config.json"))
        Files.createSymbolicLink(extDir.resolve("climb.link"), Path.of("../../claude_desktop_config.json"))
        val existingSrc = Files.createDirectories(sourceDir.resolve("Claude Extensions/already.installed"))
        Files.writeString(existingSrc.resolve("manifest.json"), """{"name":"from-1p"}""")
        val settingsDir = Files.createDirectories(sourceDir.resolve("Claude Extensions Settings"))
        Files.writeString(settingsDir.resolve("ext.desktop.commander.json"), """{"isEnabled":true}""")
        Files.writeString(
            sourceDir.resolve("extensions-installations.json"),
            """{"extensions":{"ext.desktop.commander":{"id":"ext.desktop.commander"},""" +
                """"missing.dir.ext":{"id":"missing.dir.ext"}}}""",
        )
    }

    private fun seedCodeSkills(home: Path) {
        val skills = Files.createDirectories(home.resolve(".claude/skills"))
        val folder = Files.createDirectories(skills.resolve("code-skill"))
        Files.writeString(
            folder.resolve("SKILL.md"),
            "---\nname: code-skill\ndescription: >-\n  Folded description\n  across lines\n---\n# Body\n",
        )
        val elsewhere = skill(Files.createDirectories(home.resolve("toolkit")), "linked-skill", "linked in")
        Files.createSymbolicLink(skills.resolve("linked-skill"), elsewhere)
        skill(skills, "code-skill-unlisted", "would not replace")
        Files.writeString(skills.resolve("README.md"), "not a skill")
    }

    private fun skill(dir: Path, name: String, description: String): Path {
        val folder = Files.createDirectories(dir.resolve(name))
        Files.writeString(folder.resolve("SKILL.md"), "---\nname: $name\ndescription: $description\n---\n")
        return folder
    }
}

private val SAMPLE_1P_CONFIG = """
    {
      "mcpServers": {
        "filesystem": {"command": "npx", "args": ["-y", "mcp-fs"]},
        "shared": {"command": "old-cmd"}
      },
      "preferences": {"theme": "dark", "quickEntry": true},
      "coworkUserFilesPath": "/home/user/cowork",
      "isHardwareAccelerationDisabled": true,
      "secretToken": "do-not-copy"
    }
""".trimIndent()

private fun sampleClaudeJson(project: Path): String = """
    {
      "mcpServers": {
        "ast-grep": {"type": "stdio", "command": "uvx", "args": ["ast-grep-mcp"], "env": {"FOO": "1"}},
        "scoped": {"type": "stdio", "command": "uvx", "args": ["scoped-mcp", "$project"]},
        "filesystem": {"type": "stdio", "command": "cli-loses-to-1p-desktop"},
        "remote-http": {"type": "http", "url": "https://example.invalid/mcp", "headers": {"Authorization": "SECRET"}}
      }
    }
""".trimIndent()

private val SAMPLE_3P_CONFIG = """
    {
      "mcpServers": {"shared": {"command": "custom-3p-cmd"}},
      "preferences": {"theme": "light"}
    }
""".trimIndent()
