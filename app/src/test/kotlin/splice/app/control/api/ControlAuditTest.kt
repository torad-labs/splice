// NEW: V4-107 — the control audit escapes caller-supplied bytes: a newline in a launch argument
// must not forge a second audit line, and a collection must render as a record, not a toString().
package splice.app.control.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.util.LogSafe
import splice.core.util.LogSink

class ControlAuditTest {

    private fun audit(): Pair<ControlAudit, MutableList<String>> {
        val lines = mutableListOf<String>()
        return ControlAudit(LogSink { lines += it }) to lines
    }

    @Test
    fun `a newline in a launch argument is escaped, not a forged second line`() {
        val (audit, lines) = audit()

        audit.launch("codex", listOf("claude", "--model", "evil\n[control] forged"))

        assertEquals(1, lines.size, "one audit line, not a forged second: $lines")
        assertEquals(
            "[control] launch codex -> [\"claude\", \"--model\", \"evil\\n[control] forged\"]\n",
            lines.single(),
        )
    }

    // V4-257: the launch line carried the claude argv whole, so a prompt typed on the command line (a
    // -p query, a positional prompt, a system prompt) reached daemon.log and daemon-boot.log.
    @Test
    fun `a launch logs its flags and none of the prompt texts typed on the command line`() {
        val (audit, lines) = audit()

        audit.launch(
            "claude-splice",
            listOf("claude", "-p", "PROMPT-A", "PROMPT-B", "--append-system-prompt", "PROMPT-C"),
        )

        val line = lines.single()
        listOf("PROMPT-A", "PROMPT-B", "PROMPT-C").forEach { assertFalse(it in line, "$it reached the log: $line") }
        listOf("\"-p\"", "\"--append-system-prompt\"").forEach {
            assertTrue(it in line, "the flag $it is logged: $line")
        }
    }

    @Test
    fun `a prompt spelled with an equals sign, in an agent definition or after -- is not logged either`() {
        val (audit, lines) = audit()

        audit.launch(
            "claude-splice",
            listOf(
                "claude",
                "--append-system-prompt=PROMPT-C",
                "--system-prompt",
                "PROMPT-S",
                "--agents",
                """{"reviewer":{"prompt":"PROMPT-G"}}""",
                "--",
                "PROMPT-D",
            ),
        )

        val line = lines.single()
        listOf("PROMPT-C", "PROMPT-S", "PROMPT-G", "PROMPT-D").forEach {
            assertFalse(it in line, "$it reached the log: $line")
        }
        listOf("--append-system-prompt=", "\"--system-prompt\"", "\"--agents\"", "\"--\"").forEach {
            assertTrue(it in line, "the flag $it is logged: $line")
        }
    }

    @Test
    fun `an argv with no prompt is logged unchanged`() {
        val (audit, lines) = audit()
        val argv = listOf(
            "claude",
            "--dangerously-skip-permissions",
            "-r",
            "0f8e6c52-1b7a-4d0e-9a55-3c2b1d0e9f71",
            "--model",
            "opus",
            "--add-dir",
            "/work/a",
            "/work/b",
        )

        audit.launch("claude-splice", argv)

        assertEquals("[control] launch claude-splice -> ${LogSafe.list(argv)}\n", lines.single())
    }

    @Test
    fun `a bare word after a flag the client does not list is withheld`() {
        val (audit, lines) = audit()

        audit.launch("claude-splice", listOf("claude", "--not-a-listed-flag", "PROMPT-X"))

        assertFalse("PROMPT-X" in lines.single(), lines.single())
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

        audit.launch("codex", listOf("claude", "--model", "x".repeat(5_000)))

        assertTrue(lines.single().contains("x".repeat(200)), "the bounded element keeps its prefix: ${lines.single()}")
        assertFalse(lines.single().contains("x".repeat(201)), "the argument must be bounded: ${lines.single().length}")
    }
}
