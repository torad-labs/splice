package splice.app

import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.topology.DaemonConfig
import java.nio.file.Path
import kotlin.io.path.writeText

class McpGlobalReadTest {

    @Test
    fun `absent, malformed and server-less files all read as no servers`(@TempDir home: Path) {
        assertTrue(McpGlobalRead(home)().isEmpty(), "absent")
        home.resolve(".claude.json").writeText("{not json")
        assertTrue(McpGlobalRead(home)().isEmpty(), "malformed")
        home.resolve(".claude.json").writeText("""{"theme":"dark"}""")
        assertTrue(McpGlobalRead(home)().isEmpty(), "no mcpServers key")
    }

    @Test
    fun `the operator's servers are read as they are now`(@TempDir home: Path) {
        val read = McpGlobalRead(home)
        home.resolve(".claude.json").writeText("""{"mcpServers":{"exa":{"command":"npx"}}}""")
        assertEquals(setOf("exa"), read().keys)
        home.resolve(".claude.json").writeText("""{"mcpServers":{"exa":{"command":"npx"},"fs":{"command":"x"}}}""")
        assertEquals(setOf("exa", "fs"), read().keys)
        assertEquals("npx", read()["exa"]!!.jsonObject["command"].toString().trim('"'))
    }

    @Test
    fun `malformed config reports a bounded safe diagnostic and recovers`(@TempDir home: Path) {
        val logs = StringBuilder()
        val reader = McpGlobalRead(home) { logs.append(it) }
        assertTrue(reader().isEmpty())
        assertEquals("", logs.toString(), "genuine absence is quiet")
        val file = home.resolve(".claude.json")
        file.writeText("{synthetic-private-value")
        repeat(3) { assertTrue(reader().isEmpty()) }
        assertEquals(1, logs.lines().count { it.contains("malformed") })
        assertFalse(logs.contains("synthetic-private-value"))
        file.writeText("""{"mcpServers":{"ok":{"command":"srv"}}}""")
        assertEquals(setOf("ok"), reader().keys)
        file.writeText("[]")
        assertTrue(reader().isEmpty())
        assertEquals(2, logs.lines().count { it.contains("malformed") })
    }

    @Test
    fun `unreadable global file is diagnosed rather than treated as first run`(@TempDir home: Path) {
        java.nio.file.Files.createDirectory(home.resolve(".claude.json"))
        val logs = StringBuilder()
        assertTrue(McpGlobalRead(home) { logs.append(it) }().isEmpty())
        assertTrue(logs.contains("unreadable"), logs.toString())
    }

    @Test
    fun `daemon knobs default to hosting on with nothing excluded`() {
        assertEquals(McpHostingSettings(true, emptySet()), McpHostingSettings().with(DaemonConfig()))
        assertEquals(
            McpHostingSettings(false, setOf("fs")),
            McpHostingSettings().with(DaemonConfig(mcpHosting = false, mcpHostingExclude = listOf("fs"))),
        )
    }
}
