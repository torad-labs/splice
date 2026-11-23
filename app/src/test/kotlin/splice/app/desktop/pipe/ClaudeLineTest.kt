// desktop-antigravity (decisions 3 and 5): what `splice desktop launch` reads from the command line Claude Desktop
// starts a Code tab session with, and the line it starts the next program with when a live switch changes the model.
package splice.app.desktop.pipe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClaudeLineTest {
    private val reader = ClaudeLineReader()

    @Test
    fun `an Antigravity model, written either way, sends the session to agy`() {
        val joined = reader.of(listOf(CLAUDE, "--output-format", "stream-json", "--model=claude-antigravity--neon"))
        val spaced = reader.of(listOf(CLAUDE, "--model", "claude-antigravity--neon", "--input-format", "stream-json"))
        assertTrue(joined.antigravity)
        assertTrue(spaced.antigravity)
        assertTrue(spaced.streamJson)
        assertFalse(reader.of(listOf(CLAUDE, "--model", "claude-codex--gpt-5.5")).antigravity)
        assertFalse(reader.of(listOf(CLAUDE, "--version")).antigravity)
    }

    @Test
    fun `the program's words end at its first flag, so a wrapper in front of Claude Code stays in front`() {
        val line = reader.of(listOf("/usr/bin/nice", CLAUDE, "--model", "x", "--session-id", "s-1"))
        assertEquals(listOf("/usr/bin/nice", CLAUDE), line.words)
        assertEquals("s-1", line.sessionId)
        assertEquals(listOf("--stands-in-for=$CLAUDE", "--model", "x", "--session-id", "s-1"), line.acpArgs())
    }

    @Test
    fun `a switch names the new model and takes the session up with resume, keeping every other flag`() {
        val line = reader.of(
            listOf(CLAUDE, "--input-format", "stream-json", "--model=claude-opus-5-5", "--session-id", "s-1", "-v"),
        )
        val switched = line.switchedTo("claude-antigravity--neon", "s-1")
        assertEquals(
            listOf("--input-format", "stream-json", "-v", "--model", "claude-antigravity--neon", "--resume", "s-1"),
            switched.args,
        )
        assertTrue(switched.antigravity)
        assertEquals("s-1", switched.sessionId)
        assertEquals(listOf("--model", "claude-opus-5-5"), line.switchedTo("claude-opus-5-5", null).args.takeLast(2))
    }

    @Test
    fun `only a set_model or initialize control request is the pipe's to read`() {
        val asks = DesktopAskReader()
        assertEquals(
            DesktopAsk.SetModel("claude-antigravity--neon"),
            asks.of("""{"type":"control_request","request":{"subtype":"set_model","model":"$NEON"}}"""),
        )
        assertEquals(
            DesktopAsk.Initialize,
            asks.of("""{"type":"control_request","request_id":"r0","request":{"subtype":"initialize"}}"""),
        )
        assertEquals(DesktopAsk.Other, asks.of("""{"type":"user","message":{"content":"set_model"}}"""))
        assertEquals(DesktopAsk.Other, asks.of("not json"))
    }
}

private const val CLAUDE = "/home/me/.config/Claude/claude-code/2.1.300/claude"
private const val NEON = "claude-antigravity--neon"
