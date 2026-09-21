// NEW: v0.4.0 FEATURES.md §8 — a hosted MCP child runs in a stated working directory (the home
// directory by default), never in whatever directory happened to start the daemon.
package splice.control.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.mcp.McpServerSpec
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class McpProcessLauncherTest {
    @Test
    fun `a hosted child runs in the launcher's working directory`(@TempDir dir: Path) {
        val child = StdioProcessLauncher(dir).invoke(McpServerSpec("pwd", "pwd", emptyList(), emptyMap()))
        val printed = child.inputStream.bufferedReader().readText().trim()
        child.waitFor(10, TimeUnit.SECONDS)
        assertEquals(dir.toRealPath().toString(), printed)
    }
}
