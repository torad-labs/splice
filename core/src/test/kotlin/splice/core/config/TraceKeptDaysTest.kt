// NEW: Oct 10, 2026 — saved prompts and answers follow the one history window until someone sets "Keep prompts and
// answers for", which then governs, shorter or longer (splice-lead, Oct 12).
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
    fun `a knob someone set governs prompts and answers, shorter or longer than the window`() {
        assertEquals(60, written("historyRetentionDays" to "30", "traceRetentionDays" to "60").traceKeptDays)
        assertEquals(60, written("historyRetentionDays" to "90", "traceRetentionDays" to "60").traceKeptDays)
        assertEquals(3, written("historyRetentionDays" to "35", "traceRetentionDays" to "3").traceKeptDays)
        assertEquals(3, written("historyRetentionDays" to "forever", "traceRetentionDays" to "3").traceKeptDays)
        val week = written("historyRetentionDays" to "35", "traceRetentionDays" to "7")
        assertEquals(7, week.traceKeptDays, "a knob set to its default value is still set")
    }

    @Test
    fun `an unset knob follows the window, so an upgrade shortens nothing`() {
        assertEquals(35, written("historyRetentionDays" to "35").traceKeptDays)
    }
}
