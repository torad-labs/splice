// NEW: Oct 10, 2026 — saved prompts and answers follow the one history window, and an install that chose a longer
// trace window of its own keeps it.
package splice.core.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class TraceKeptDaysTest {
    @TempDir
    lateinit var tmp: Path

    private fun written(vararg pairs: Pair<String, String>) = ConfigService(
        statePaths = StatePaths(baseOverride = tmp.resolve("state")),
        headOverrides = pairs.toMap(),
        envReader = { null },
    ).getConfig()

    @Test
    fun `the trace follows the history window, not the old fixed week`() {
        assertEquals(30, written("historyRetentionDays" to "30").traceKeptDays)
        assertEquals(1, written("historyRetentionDays" to "0").traceKeptDays, "today only keeps the running day")
        assertEquals(Int.MAX_VALUE, written("historyRetentionDays" to "forever").traceKeptDays)
    }

    @Test
    fun `a trace window the install chose that is longer than the history window is kept`() {
        val chosen = written("historyRetentionDays" to "30", "traceRetentionDays" to "60")
        assertEquals(60, chosen.traceKeptDays, "an upgrade never shortens what someone has")
        assertEquals(90, written("historyRetentionDays" to "90", "traceRetentionDays" to "60").traceKeptDays)
    }

    @Test
    fun `a shorter or default trace window does not hold the history window down`() {
        assertEquals(35, written("historyRetentionDays" to "35", "traceRetentionDays" to "3").traceKeptDays)
        assertEquals(35, written("historyRetentionDays" to "35").traceKeptDays)
    }
}
