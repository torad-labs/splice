package splice.provider.codex.v4388

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.provider.codex.CodeModeExecOutput
import splice.upstream.codemode.CodeModeLimits

/** V4-388: an exec output reads as codex frames it (codex-rs core/src/tools/code_mode/output.rs:31 and
 *  mod.rs:283): status, wall time, "Output:", the script's text, and "Script error:" last on a failure. */
class CodeModeExecOutputTest {
    @Test
    fun `a completed script carries codex's header over its output`() {
        assertEquals(
            "Script completed\nWall time 1.3 seconds\nOutput:\nalpha\nbeta",
            CodeModeExecOutput.completed("alpha\nbeta", 1_250, BUDGET),
        )
    }

    @Test
    fun `a failed script shows what it logged, then the error codex's way`() {
        assertEquals(
            "Script failed\nWall time 0.4 seconds\nOutput:\nbefore\nScript error:\nError: ENOENT",
            CodeModeExecOutput.failed("before", "Error: ENOENT", 400, BUDGET),
        )
        assertEquals(
            "Script failed\nWall time 0.0 seconds\nOutput:\nScript error:\nSyntaxError: Unexpected token",
            CodeModeExecOutput.failed("", "SyntaxError: Unexpected token", 0, BUDGET),
        )
    }

    @Test
    fun `a full worker output stays whole under its header, and only the upstream ceiling cuts it`() {
        val full = "x".repeat(CodeModeLimits.MAX_TEXT_BYTES)
        assertEquals(
            "Script completed\nWall time 0.0 seconds\nOutput:\n$full",
            CodeModeExecOutput.completed(full, 10, BUDGET),
        )
        val small = CodeModeExecOutput.failed(full, "Error: late", 10, SMALL_BUDGET)
        assertTrue(small.length in SMALL_BUDGET - MARKER_SLACK..SMALL_BUDGET, "${small.length} chars")
        assertTrue(small.contains("[truncated "), small.takeLast(80))
        assertTrue(small.endsWith("\nScript error:\nError: late"), small.takeLast(80))
    }

    private companion object {
        const val BUDGET = 1_048_576
        const val SMALL_BUDGET = 4_096
        const val MARKER_SLACK = 8
    }
}
