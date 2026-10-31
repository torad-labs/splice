// The wizard's Claude lane question, and what answering `wrap` does.
package splice.app.cli.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.daemonclient.ControlReply
import splice.terminal.SelectOption
import splice.terminal.SelectOutcome

private const val CLAUDE = "claude"

private fun lanes(
    answer: (List<SelectOption<ClaudeLane>>, Int) -> SelectOutcome<ClaudeLane>,
    wrap: ClaudeWrap = ClaudeWrap { error("wrap must not be called") },
    asked: MutableList<List<SelectOption<ClaudeLane>>> = mutableListOf(),
): Pair<SetupClaudeLane, MutableList<List<SelectOption<ClaudeLane>>>> {
    val picker = LanePicker { options, index ->
        asked += options
        answer(options, index)
    }
    return SetupClaudeLane(picker, wrap) to asked
}

class ClaudeLaneTest {

    // Mutant: ask unconditionally. The wizard would put a question about the Claude head in front
    // of an operator adding only OpenRouter, and whatever they answered would apply to nothing.
    @Test
    fun `no question when the claude head is not among the ticked profiles`() {
        val (lane, asked) = lanes({ _, _ -> error("must not ask") })

        assertEquals(ClaudeLane.SEPARATE, lane.ask(listOf("codex", "openrouter")))
        assertTrue(asked.isEmpty(), "asked anyway: $asked")
    }

    // Mutant: preselect Wrap, or list it first. The operator's default must be the lane that
    // touches nothing of theirs — and a non-TTY run takes exactly this option, because
    // SelectPrompt answers a consoleless ask with the one at initialIndex.
    @Test
    fun `separate is the first option and the preselected one`() {
        val (lane, asked) = lanes({ options, index -> SelectOutcome.Chosen(options[index].value) })

        assertEquals(ClaudeLane.SEPARATE, lane.ask(listOf(CLAUDE)))
        assertEquals(listOf(ClaudeLane.SEPARATE, ClaudeLane.WRAP), asked.single().map { it.value })
    }

    // Mutant: treat a cancelled prompt as an answer. Escaping out of a menu is not consent to
    // take over the operator's claude command.
    @Test
    fun `cancelling the lane prompt leaves the head separate`() {
        val (lane, _) = lanes({ _, _ -> SelectOutcome.Cancelled })

        assertEquals(ClaudeLane.SEPARATE, lane.ask(listOf(CLAUDE)))
    }

    // Mutant: wrap whatever the lane says. Separate is the lane in which splice touches nothing,
    // so a wrap call here is the whole failure this option exists to avoid.
    @Test
    fun `separate never calls the wrap path`() {
        val (lane, _) = lanes({ _, _ -> SelectOutcome.Cancelled })

        assertNull(lane.apply(ClaudeLane.SEPARATE, listOf(CLAUDE)))
    }

    @Test
    fun `wrap calls the wrap path once and prints what came back`() {
        var calls = 0
        val (lane, _) = lanes(
            { _, _ -> SelectOutcome.Cancelled },
            ClaudeWrap {
                calls += 1
                "wrapped: claude now runs through splice"
            },
        )

        assertEquals("wrapped: claude now runs through splice", lane.apply(ClaudeLane.WRAP, listOf(CLAUDE)))
        assertEquals(1, calls)
    }

    // Mutant: wrap on the ANSWER rather than on what landed. `splice add claude` can refuse (a
    // command collision, a topology that no longer parses), and wrapping then posts against a head
    // the daemon does not have — the operator's `claude` shadowed for a head that is not there.
    @Test
    fun `wrap is not attempted when the claude head did not land`() {
        val (lane, _) = lanes({ _, _ -> SelectOutcome.Cancelled })

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
        val reason = "the 'claude-splice' head is not configured, and a wrapped claude launches it"
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
