package splice.app.desktop.setup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

class DesktopProfileSyncTest {
    @Test
    fun `syncInto merges 1P Desktop settings and Claude Code stdio MCPs without overwriting 3P entries`(
        @TempDir tmp: Path,
    ) {
        val configRoot = tmp.resolve("config")
        val sourceDir = Files.createDirectories(configRoot.resolve("Claude"))
        Files.writeString(sourceDir.resolve("claude_desktop_config.json"), SAMPLE_1P_CONFIG)
        Files.writeString(tmp.resolve(".claude.json"), SAMPLE_CLAUDE_JSON)
        val targetAppData = Files.createDirectories(configRoot.resolve("claude-splice/Claude-3p"))
        Files.writeString(targetAppData.resolve("claude_desktop_config.json"), SAMPLE_3P_CONFIG)

        val sync = DesktopProfileSync(env = testEnv(tmp, configRoot), osName = "Linux")
        assertTrue(sync.syncInto(targetAppData))

        val written = Json.parseToJsonElement(
            Files.readString(targetAppData.resolve("claude_desktop_config.json")),
        ).jsonObject
        val servers = written.getValue("mcpServers").jsonObject
        assertEquals("custom-3p-cmd", servers.getValue("shared").jsonObject.getValue("command").jsonPrimitive.content)
        assertEquals("npx", servers.getValue("filesystem").jsonObject.getValue("command").jsonPrimitive.content)
        val cliStdio = servers.getValue("ast-grep").jsonObject
        assertEquals("uvx", cliStdio.getValue("command").jsonPrimitive.content)
        assertFalse("type" in cliStdio, "Claude Code transport tag is stripped for Desktop LocalMcpServerManager")
        assertFalse("remote-http" in servers, "HTTP/SSE servers from ~/.claude.json are skipped")
        val prefs = written.getValue("preferences").jsonObject
        assertEquals("light", prefs.getValue("theme").jsonPrimitive.content)
        assertEquals("true", prefs.getValue("quickEntry").jsonPrimitive.content)
        assertEquals("/home/user/cowork", written.getValue("coworkUserFilesPath").jsonPrimitive.content)
        assertEquals("true", written.getValue("isHardwareAccelerationDisabled").jsonPrimitive.content)
        assertFalse("secretToken" in written)
        assertFalse(sync.syncInto(targetAppData))
    }

    @Test
    fun `syncInto copies missing 1P Desktop extensions without symlinks or overwriting 3P installs`(
        @TempDir tmp: Path,
    ) {
        val configRoot = tmp.resolve("config")
        val sourceDir = Files.createDirectories(configRoot.resolve("Claude"))
        seedSampleExtensions(sourceDir)
        val targetAppData = Files.createDirectories(configRoot.resolve("claude-splice/Claude-3p"))
        val existingExt = Files.createDirectories(targetAppData.resolve("Claude Extensions/already.installed"))
        Files.writeString(existingExt.resolve("manifest.json"), """{"name":"keep-3p"}""")

        val sync = DesktopProfileSync(env = testEnv(tmp, configRoot), osName = "Linux")
        assertTrue(sync.syncInto(targetAppData))

        assertTrue(Files.isRegularFile(targetAppData.resolve("Claude Extensions/ext.desktop.commander/manifest.json")))
        assertFalse(
            Files.exists(targetAppData.resolve("Claude Extensions/ext.desktop.commander/escape.link")),
            "symlinks inside 1P extension dirs must never be followed or copied",
        )
        assertEquals(
            """{"name":"keep-3p"}""",
            Files.readString(targetAppData.resolve("Claude Extensions/already.installed/manifest.json")),
        )
        assertEquals(
            """{"isEnabled":true}""",
            Files.readString(targetAppData.resolve("Claude Extensions Settings/ext.desktop.commander.json")),
        )
        val reg = Json.parseToJsonElement(
            Files.readString(targetAppData.resolve("extensions-installations.json")),
        ).jsonObject.getValue("extensions").jsonObject
        assertTrue("ext.desktop.commander" in reg)
        assertFalse("missing.dir.ext" in reg)
        assertFalse(sync.syncInto(targetAppData))
    }

    private fun testEnv(home: Path, configRoot: Path): EnvReader = EnvReader { key ->
        when (key) {
            "XDG_CONFIG_HOME" -> configRoot.toString()
            "HOME" -> home.toString()
            else -> null
        }
    }

    private fun seedSampleExtensions(sourceDir: Path) {
        val extDir = Files.createDirectories(sourceDir.resolve("Claude Extensions/ext.desktop.commander"))
        Files.writeString(extDir.resolve("manifest.json"), """{"name":"desktop-commander"}""")
        Files.writeString(sourceDir.resolve("claude_desktop_config.json"), "{}")
        Files.createSymbolicLink(extDir.resolve("escape.link"), sourceDir.resolve("claude_desktop_config.json"))
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

private val SAMPLE_CLAUDE_JSON = """
    {
      "mcpServers": {
        "ast-grep": {"type": "stdio", "command": "uvx", "args": ["ast-grep-mcp"], "env": {"FOO": "1"}},
        "filesystem": {"type": "stdio", "command": "cli-loses-to-1p-desktop"},
        "remote-http": {"type": "http", "url": "https://example.invalid/mcp"}
      }
    }
""".trimIndent()

private val SAMPLE_3P_CONFIG = """
    {
      "mcpServers": {"shared": {"command": "custom-3p-cmd"}},
      "preferences": {"theme": "light"}
    }
""".trimIndent()
