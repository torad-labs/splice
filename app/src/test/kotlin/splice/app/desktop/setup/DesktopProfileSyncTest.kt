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
    fun `syncInto copies safe non-auth settings from 1P Desktop and preserves existing 3P entries`(
        @TempDir tmp: Path,
    ) {
        val configRoot = tmp.resolve("config")
        val sourceDir = Files.createDirectories(configRoot.resolve("Claude"))
        Files.writeString(sourceDir.resolve("claude_desktop_config.json"), SAMPLE_1P_CONFIG)
        val targetAppData = Files.createDirectories(configRoot.resolve("claude-splice/Claude-3p"))
        Files.writeString(targetAppData.resolve("claude_desktop_config.json"), SAMPLE_3P_CONFIG)
        val env = EnvReader { key ->
            when (key) {
                "XDG_CONFIG_HOME" -> configRoot.toString()
                "HOME" -> tmp.toString()
                else -> null
            }
        }
        val sync = DesktopProfileSync(env = env, osName = "Linux")
        assertTrue(sync.syncInto(targetAppData))

        val written = Json.parseToJsonElement(
            Files.readString(targetAppData.resolve("claude_desktop_config.json")),
        ).jsonObject
        val servers = written.getValue("mcpServers").jsonObject
        assertEquals("custom-3p-cmd", servers.getValue("shared").jsonObject.getValue("command").jsonPrimitive.content)
        assertEquals("npx", servers.getValue("filesystem").jsonObject.getValue("command").jsonPrimitive.content)
        val prefs = written.getValue("preferences").jsonObject
        assertEquals("light", prefs.getValue("theme").jsonPrimitive.content)
        assertEquals("true", prefs.getValue("quickEntry").jsonPrimitive.content)
        assertEquals("/home/user/cowork", written.getValue("coworkUserFilesPath").jsonPrimitive.content)
        assertEquals("true", written.getValue("isHardwareAccelerationDisabled").jsonPrimitive.content)
        assertFalse("secretToken" in written)
        assertFalse(sync.syncInto(targetAppData))
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

private val SAMPLE_3P_CONFIG = """
    {
      "mcpServers": {"shared": {"command": "custom-3p-cmd"}},
      "preferences": {"theme": "light"}
    }
""".trimIndent()
