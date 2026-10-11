// NEW: Oct 11, 2026 — the user decides how much splice writes: with the journal switch off a code-mode save leaves
// nothing new on the disk, and switched back on the next save writes it.
package splice.provider.codex

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.Path

class CodeModeJournalSwitchTest {
    private fun filesIn(dir: Path): Int = if (Files.isDirectory(dir)) Files.list(dir).use { it.count().toInt() } else 0

    @Test
    fun `with the switch off a save writes no file, and back on the next save writes one`(@TempDir tmp: Path) {
        var on = false
        val dir = tmp.resolve("code-mode")
        val store = CodexCodeModeStore(
            CodeModeStateLocation(dir, tmp.resolve("legacy.json"), CodeModeJournaling { on }),
            Json,
            LogSink { },
        )
        val records = listOf(CodeModeRecords.of("conversation-1", 1))

        store.save(records, emptyList())
        assertEquals(0, filesIn(dir), "off keeps the journal in memory only")

        on = true
        store.save(records, emptyList())
        assertTrue(filesIn(dir) > 0, "switched on, the next save reaches the disk")
    }
}
