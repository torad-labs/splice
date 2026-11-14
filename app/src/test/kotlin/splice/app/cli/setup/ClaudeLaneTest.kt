// What the wizard does about the Claude head: ticking it wraps plain `claude`, and nothing is asked.
package splice.app.cli.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.daemonclient.ControlReply

private const val CLAUDE = "claude"

class ClaudeLaneTest {

    @Test
    fun `no claude head ticked, nothing is wrapped`() {
        val lane = SetupClaudeLane(ClaudeWrap { error("wrap must not be called") })

        assertEquals(ClaudeLane.NONE, lane.ask(listOf("codex", "openrouter")))
        assertNull(lane.apply(ClaudeLane.NONE, listOf("codex", "openrouter")))
    }

    // One Claude (Nov 13, 2026): the head is plain `claude`, wrapped. There is no separate command to choose instead.
    @Test
    fun `ticking the claude head wraps claude once and prints what came back`() {
        var calls = 0
        val lane = SetupClaudeLane(
            ClaudeWrap {
                calls += 1
                "wrapped: claude now runs through splice"
            },
        )

        val chosen = lane.ask(listOf(CLAUDE))
        assertEquals(ClaudeLane.WRAP, chosen)
        assertEquals("wrapped: claude now runs through splice", lane.apply(chosen, listOf(CLAUDE)))
        assertEquals(1, calls)
        assertFalse("claude-splice" in lane.summaryLine(), lane.summaryLine())
    }

    // Mutant: wrap on the ANSWER rather than on what landed. `splice add claude` can refuse (a
    // command collision, a topology that no longer parses), and wrapping then posts against a head
    // the daemon does not have — the operator's `claude` shadowed for a head that is not there.
    @Test
    fun `wrap is not attempted when the claude head did not land`() {
        val lane = SetupClaudeLane(ClaudeWrap { error("wrap must not be called") })

        val said = lane.apply(ClaudeLane.WRAP, listOf("codex"))
        assertEquals("not wrapping: the claude head was not added", said)
    }
}

/** The daemon's answer, turned into the one line the wizard prints. Separated from the ask because
 *  this half is where a refusal either reaches the operator or is swallowed into a status code. */
class ClaudeWrapReplyTest {

    @Test
    fun `a 2xx says it is wrapped and where to undo it`() {
        val said = DaemonClaudeWrap().replyLine(ControlReply(200, """{"ok":true,"mode":"wrapped"}"""))

        assertTrue(said.startsWith("wrapped:"), said)
        assertTrue("POST /api/claude-head/unwrap" in said, said)
        // There is no `splice wrap`/`splice unwrap` verb; pointing at one would be sending the
        // operator to a command that does not exist.
        assertFalse("splice unwrap" in said, said)
    }

    // Mutant: print the status instead of the body. The daemon writes the reason and only the
    // reason is actionable — "the daemon answered 409" tells an operator nothing they can do.
    @Test
    fun `a refusal is quoted in the daemon's own words`() {
        val said = DaemonClaudeWrap().replyLine(
            ControlReply(409, """{"error":"claude is not currently wrapped"}"""),
        )

        assertEquals("not wrapping: claude is not currently wrapped", said)
    }

    @Test
    fun `the unconfigured-head refusal reaches the operator verbatim`() {
        val reason = "the 'claude' head is not configured, and a wrapped claude launches it"
        val said = DaemonClaudeWrap().replyLine(ControlReply(503, """{"error":"$reason"}"""))

        assertEquals("not wrapping: $reason", said)
    }

    // Mutant: assume a body is always there and always shaped. A proxy, a truncated read or a
    // future envelope must degrade to the status, never to an empty sentence.
    @Test
    fun `a body with no error sentence falls back to the status`() {
        assertEquals("not wrapping: the daemon answered 500", DaemonClaudeWrap().replyLine(ControlReply(500, "")))
        assertEquals(
            "not wrapping: the daemon answered 502",
            DaemonClaudeWrap().replyLine(ControlReply(502, "<html>bad gateway</html>")),
        )
    }
}
