// NEW: V4-257 — the client grammar ClaudeArgv logs an argv by, case by case: what commander takes as an
// option's value is kept, what it takes as the prompt is withheld, and what nothing places fails closed.
package splice.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.TESTED_CLAUDE_CODE

class ClaudeArgvTest {

    private fun logged(vararg args: String): List<String> = ClaudeArgv.promptFree(listOf("claude", *args))

    @Test
    fun `the table was read from the Claude Code release splice pins`() {
        assertEquals(TESTED_CLAUDE_CODE, ClaudeArgv.GRAMMAR_FROM, "re-read `claude --help` of the new pin")
    }

    @Test
    fun `a switch takes no value, so the word after it is the prompt`() {
        assertEquals(listOf("claude", "-p", "<3 chars withheld>"), logged("-p", "fix"))
        assertEquals(listOf("claude", "-cp", "<3 chars withheld>"), logged("-cp", "fix"))
    }

    @Test
    fun `an option's value is kept, spaced, glued or after an equals sign`() {
        assertEquals(listOf("claude", "--model", "opus"), logged("--model", "opus"))
        assertEquals(listOf("claude", "--model=opus"), logged("--model=opus"))
        assertEquals(listOf("claude", "-rabc", "<3 chars withheld>"), logged("-rabc", "fix"))
        assertEquals(listOf("claude", "-r", "abc"), logged("-r", "abc"))
    }

    @Test
    fun `an optional value is taken only when the next word is not a flag`() {
        assertEquals(listOf("claude", "-r", "--verbose", "<3 chars withheld>"), logged("-r", "--verbose", "fix"))
    }

    @Test
    fun `a variadic option takes every word up to the next flag`() {
        assertEquals(
            listOf("claude", "--add-dir", "/a", "/b", "-p", "<3 chars withheld>"),
            logged("--add-dir", "/a", "/b", "-p", "fix"),
        )
    }

    @Test
    fun `a prompt option's value is withheld in every spelling`() {
        assertEquals(listOf("claude", "--system-prompt", "<4 chars withheld>"), logged("--system-prompt", "be x"))
        assertEquals(listOf("claude", "--system-prompt=<4 chars withheld>"), logged("--system-prompt=be x"))
        assertEquals(listOf("claude", "--cloud", "<4 chars withheld>"), logged("--cloud", "task"))
    }

    @Test
    fun `a flag the client does not list fails closed`() {
        assertEquals(listOf("claude", "--x-unlisted", "<3 chars withheld>"), logged("--x-unlisted", "fix"))
        assertEquals(listOf("claude", "--x-unlisted=<3 chars withheld>"), logged("--x-unlisted=fix"))
        assertEquals(listOf("claude", "-z", "<3 chars withheld>"), logged("-z", "fix"))
    }

    @Test
    fun `every word after -- is positional`() {
        assertEquals(listOf("claude", "--", "<7 chars withheld>"), logged("--", "--model"))
    }
}
