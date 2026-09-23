// NEW: V4-107 — the control audit escapes caller-supplied bytes: a newline in a launch argument
// must not forge a second audit line, and a collection must render as a record, not a toString().
package splice.app.control.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.util.LogSink

class ControlAuditTest {

    private fun audit(): Pair<ControlAudit, MutableList<String>> {
        val lines = mutableListOf<String>()
        return ControlAudit(LogSink { lines += it }) to lines
    }

    @Test
    fun `a newline in a launch argument is escaped, not a forged second line`() {
        val (audit, lines) = audit()

        audit.launch("codex", listOf("-c", "evil\n[control] forged"))

        assertEquals(1, lines.size, "one audit line, not a forged second: $lines")
        assertEquals(
            "[control] launch codex -> [\"-c\", \"evil\\n[control] forged\"]\n",
            lines.single(),
        )
    }

    @Test
    fun `a carriage return in a head action is escaped`() {
        val (audit, lines) = audit()

        audit.headAction("codex", "start\r\nstop")

        assertEquals("[control] head codex -> start\\r\\nstop\n", lines.single())
    }

    @Test
    fun `a huge argument is length-bounded`() {
        val (audit, lines) = audit()

        audit.launch("codex", listOf("x".repeat(5_000)))

        assertTrue(lines.single().contains("x".repeat(200)), "the bounded element keeps its prefix: ${lines.single()}")
        assertFalse(lines.single().contains("x".repeat(201)), "the argument must be bounded: ${lines.single().length}")
    }
}
