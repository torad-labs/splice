// What the wizard prints of the daemon's answer to linking `claude`. What setup links, and where, is SetupCommandTest's
// ClaudeLink, driven through `splice setup` itself.
//
// door-default (docs/specs/door-default.md, decision 8): the cases that called SetupClaudeLane's ask and apply directly
// with a counting fake were removed for SetupCommandTest.ClaudeLink, which sees what the person sees.
package splice.app.cli.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.daemonclient.ControlReply

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
        val reason = "the 'claude' head is not configured and claude is not on the door, so a wrapped claude " +
            "would launch nothing"
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
