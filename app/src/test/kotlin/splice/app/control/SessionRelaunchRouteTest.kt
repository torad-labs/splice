// NEW: Oct 26, 2026 (a relaunch brings a session back as it was) — POST /api/sessions/{id}/relaunch through a real
// ControlServer, the way `splice sessions relaunch` drives it after a key rotation: over a registry of running Claude
// Code sessions, their processes as /proc shows them, and a terminal that records what it is asked. The relaunch of
// Oct 26 resumed every session on its head's own command in a new tmux session: two sessions started as `claude` came
// back "Not logged in" in the head's config folder, reviews came back without their agent, and nobody could find the
// sessions it moved.
//
// What a relaunch must give back: the session started again on the command it was started with, in its own config
// folder (so signed in as it was), with its agent and every other option it was given and never its first prompt, on
// the model its row reports, in the same pane, and only between its turns.
//
// What it must never do: type /exit into a session holding a feedback draft that has not been sent. Claude Code
// 2.1.310 answers that /exit with "You have 1 unsent feedback draft · Enter to review & send · Esc to discard and
// exit", and sending or discarding the draft is the person's call. On Oct 27 a relaunch did, and left a
// session in ~/.claude-claudex sitting at that question.
//
// The daemon, the registry and the terminal are SessionRelaunchRig's, which DoorSessionRelaunchRouteTest shares.
package splice.app.control

import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.config.MgmtKey
import splice.core.config.TurnKey
import splice.core.session.SessionPane
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

private const val HEAD = "claude"
private const val REVIEW = "5e1a0001-0000-4000-8000-000000000001"
private const val SEAT = "5e1a0002-0000-4000-8000-000000000002"

/** A session beside [SEAT] in one config folder, as the sessions of the claudex head share ~/.claude-claudex. */
private const val NEIGHBOR = "5e1a0004-0000-4000-8000-000000000004"

/** Sessions in that folder holding a feedback draft they have not sent, each held otherwise too: in its turn, running
 *  background shells, waiting on an answer, and on no splice command. */
private const val IN_TURN = "5e1a0005-0000-4000-8000-000000000005"
private const val SHELLS = "5e1a0006-0000-4000-8000-000000000006"
private const val ASKING = "5e1a0007-0000-4000-8000-000000000007"
private const val OUTSIDE = "5e1a0008-0000-4000-8000-000000000008"

/** What a refusal for a feedback draft that has not been sent says, in the daemon's reply and the CLI's line. */
private const val HOLDS_FEEDBACK = "holds a feedback draft that has not been sent"

/** The model each session's transcript last ran, which its row reports. */
private const val ROW_MODEL = "claude-opus-5-5"

// why: above the kernel's pid_max (4194304), so no process on the machine running the test answers to these pids
private const val FIRST_PID = 5_000_001L

internal class SessionRelaunchRouteTest : SessionRelaunchRig() {
    // ── the sessions, as they were started ─────────────────────────────────────────────────────────────────

    /** A review started through his wrapped `claude` in his own ~/.claude: its agent and its name, and the prompt it
     *  was started with, after the words the launch put first. */
    private fun startReview(): SessionPane = start(
        REVIEW,
        FIRST_PID,
        listOf(
            realClaude.toString(), "--settings", """{"env":{"SPLICE":"1"}}""",
            "--mcp-config", paths.stateDir.resolve("mcp-hub/launch-$FIRST_PID.json").toString(),
            "--plugin-dir-no-mcp", "/home/p/.claude/plugins/review",
            "--agent", "reviewer", "-n", "splice-review-3", "/review-pr https://github.com/torad/splice/pull/251",
        ),
        environ = listOf("SPLICE=1"),
        transcripts = home.resolve(".claude"),
    )

    /** A session started through a wrapped `claude` in a config folder of its own, with its own model named: [SEAT] as
     *  [FIRST_PID] + 1 unless another [session] and [pid] are named, holding the turn [key] its launch planted when one
     *  is given. */
    private fun startSeat(
        folder: Path,
        session: String = SEAT,
        pid: Long = FIRST_PID + 1,
        key: String? = null,
    ): SessionPane = start(
        session,
        pid,
        listOf(realClaude.toString(), "--settings", """{"env":{"SPLICE":"1"}}""") +
            listOf("--mcp-config", paths.stateDir.resolve("wrapped-mcp-claude.json").toString()) +
            listOf("--allow-dangerously-skip-permissions", "--model", "claude-fable-5-1"),
        environ = listOf("SPLICE=1", "CLAUDE_CONFIG_DIR=$folder") +
            listOfNotNull(key?.let { "ANTHROPIC_AUTH_TOKEN=$it" }),
        transcripts = folder,
    )

    /** A feedback draft as Claude Code 2.1.310 writes one, <[folder]>/feedback/drafts/<draft_id>.json: from [session],
     *  and "queued" in [status] until it is sent. A [cut] one stops after its status, as a file left unfinished. */
    private fun feedbackDraft(folder: Path, session: String, status: String, cut: Boolean = false) {
        val id = UUID.randomUUID().toString()
        val text = """{"type":"bug","title":"a tool call failed","details":"the edit did not apply",""" +
            """"source_session_id":"$session","status":"$status","created_at":"2026-10-27T13:30:00.000Z",""" +
            """"draft_id":"$id","trigger":"model_judgment","cli_version":"2.1.310"}"""
        val written = if (cut) text.substringBefore(",\"draft_id\"") else text
        Files.writeString(Files.createDirectories(folder.resolve("feedback/drafts")).resolve("$id.json"), written)
    }

    @Test
    fun `a session started through his claude comes back in its pane, in its own config folder, as it was started`() {
        val seatFolder = home.resolve(".claude-work")
        serve { daemon ->
            val review = startReview()
            val seat = startSeat(seatFolder)

            val reviewed = daemon.relaunch(REVIEW)
            assertEquals(HttpStatusCode.OK, reviewed.first, reviewed.second)
            val seated = daemon.relaunch(SEAT)
            assertEquals(HttpStatusCode.OK, seated.first, seated.second)

            val claude = bin.resolve("claude").toString()
            assertEquals(
                listOf(
                    Restart(
                        review,
                        listOf("env", "-u", "CLAUDE_CONFIG_DIR", claude) +
                            listOf("--agent", "reviewer", "-n", "splice-review-3") +
                            listOf("-r", REVIEW, "--model", ROW_MODEL),
                        cwd(REVIEW).toString(),
                    ),
                    Restart(
                        seat,
                        listOf("env", "CLAUDE_CONFIG_DIR=$seatFolder", claude) +
                            listOf("--allow-dangerously-skip-permissions", "--model", "claude-fable-5-1", "-r", SEAT),
                        cwd(SEAT).toString(),
                    ),
                ),
                terminal.restarted,
                "each starts again in the pane it ran in, in its own folder, on the command it was started with",
            )
            assertEquals(listOf(review to "/exit", seat to "/exit"), terminal.sent, "each client left on its own exit")
            assertTrue(terminal.opened.isEmpty(), "no new terminal is opened: ${terminal.opened}")
            assertEquals(listOf(review, seat), terminal.held, "each pane is kept before its client leaves")
            assertEquals(listOf(review, seat), terminal.released, "and given back its own end once it is back")
        }
    }

    @Test
    fun `a session in its turn is left where it is, and nothing is typed into it`() {
        serve { daemon ->
            val review = startReview()
            val file = sessionsDir.resolve("$FIRST_PID.json")
            Files.writeString(file, Files.readString(file).replace("\"idle\"", "\"busy\""))

            val reply = daemon.relaunch(REVIEW)

            assertEquals(HttpStatusCode.Conflict, reply.first, reply.second)
            assertTrue(terminal.sent.isEmpty() && terminal.held.isEmpty(), "nothing typed, no pane kept")
            assertTrue(terminal.restarted.isEmpty() && terminal.opened.isEmpty(), "nothing started")
            assertEquals(review, panes.paneFor(REVIEW), "it stays where it runs")
        }
    }

    // The sessions of one head share its config folder, and Claude Code keeps every session's feedback drafts in it: a
    // draft names the session it came from, and its status reads "queued" until it is sent. On Oct 27
    // ~/.claude-claudex held ten queued drafts of several sessions, and one file Claude Code had left cut off mid-way.
    @Test
    fun `a session holding a feedback draft that has not been sent is left where it is, and its neighbor relaunches`() {
        val folder = home.resolve(".claude-claudex")
        serve { daemon ->
            val holder = startSeat(folder)
            val neighbor = startSeat(folder, NEIGHBOR, FIRST_PID + 3)
            feedbackDraft(folder, SEAT, "queued")
            // what the neighbor's folder holds besides the holder's draft: its own, already sent, and one cut off
            feedbackDraft(folder, NEIGHBOR, "sent")
            feedbackDraft(folder, NEIGHBOR, "queued", cut = true)

            val held = daemon.relaunch(SEAT)
            val relaunched = daemon.relaunch(NEIGHBOR)

            assertEquals(HttpStatusCode.Conflict, held.first, held.second)
            assertTrue(HOLDS_FEEDBACK in said(held.second, "error").orEmpty(), "it says why: ${held.second}")
            assertEquals("feedback", said(held.second, "reason"), held.second)
            assertEquals(HttpStatusCode.OK, relaunched.first, relaunched.second)
            assertEquals(listOf(neighbor to "/exit"), terminal.sent, "nothing is typed into the holder's pane")
            assertEquals(listOf(neighbor), terminal.held, "and its pane is not kept for a restart")
            assertEquals(listOf(neighbor), terminal.restarted.map { it.pane }, "only the neighbor starts again")
            assertEquals(holder, panes.paneFor(SEAT), "the holder stays where it runs")
        }
    }

    // Continue on ends the client with the same /exit, so it leaves such a session where it is too.
    @Test
    fun `Continue on leaves a session holding a feedback draft that has not been sent where it is`() {
        val folder = home.resolve(".claude-claudex")
        serve { daemon ->
            val holder = startSeat(folder)
            feedbackDraft(folder, SEAT, "queued")

            val reply = daemon.move(SEAT, """{"head":"$HEAD","model":"claude-opus-5"}""")

            assertEquals(HttpStatusCode.Conflict, reply.first, reply.second)
            assertTrue(HOLDS_FEEDBACK in said(reply.second, "error").orEmpty(), "it says why: ${reply.second}")
            assertEquals("feedback", said(reply.second, "reason"), reply.second)
            assertTrue(terminal.sent.isEmpty(), "nothing is typed into its pane: ${terminal.sent}")
            assertTrue(terminal.opened.isEmpty() && terminal.restarted.isEmpty(), "nothing is started")
            assertEquals(holder, panes.paneFor(SEAT), "it stays where it runs")
        }
    }

    // `splice sessions relaunch` runs after a rotation until it passes. On Oct 27 three sessions due for a relaunch
    // held queued drafts: each is named with why it was left, and every other session is still relaunched.
    @Test
    fun `splice sessions relaunch names a session holding a feedback draft and goes on with the others`() {
        val folder = home.resolve(".claude-claudex")
        serve { daemon ->
            val key = TurnKey(MgmtKey(paths)).get()
            val neighbor = startSeat(folder, NEIGHBOR, FIRST_PID + 3, key)
            val holder = startSeat(folder, SEAT, FIRST_PID + 1, key)
            // the holder's client wrote its registration last, so the run comes to it first
            register(SEAT, FIRST_PID + 1, at = System.currentTimeMillis() + 60_000)
            feedbackDraft(folder, SEAT, "queued")

            val (exit, said) = daemon.relaunchAll()

            val left = said.indexOfFirst { SEAT in it && "left" in it }
            val relaunched = said.indexOfFirst { NEIGHBOR in it && "relaunched" in it }
            assertTrue(left >= 0 && HOLDS_FEEDBACK in said[left], "the holder is named with why: $said")
            assertTrue(relaunched > left, "and the run goes on to the neighbor, which is relaunched: $said")
            assertEquals(listOf(neighbor to "/exit"), terminal.sent, "nothing is typed into the holder's pane")
            assertEquals(holder, panes.paneFor(SEAT), "the holder stays where it runs")
            assertEquals(1, exit, "a session left where it is fails the run, so it is run again: $said")
        }
    }

    // A session holding a draft can be held otherwise too: in its turn, running background shells, waiting on an
    // answer, or on no splice command at all. Whatever else holds it, sending or discarding the draft is the person's,
    // so a relaunch and a Continue on each refuse it for that draft, and type nothing into it.
    @Test
    fun `whatever else holds a session, a relaunch or a move names the feedback draft it has not sent`() {
        val folder = home.resolve(".claude-claudex")
        serve { daemon ->
            val holders = startHeldOtherwise(folder, key = null) + (OUTSIDE to startOutside())

            for (session in holders.keys) {
                val relaunched = daemon.relaunch(session)
                val moved = daemon.move(session, """{"head":"$HEAD","model":"claude-opus-5"}""")
                for ((act, reply) in listOf("relaunch" to relaunched, "move" to moved)) {
                    val (status, body) = reply
                    assertEquals(HttpStatusCode.Conflict, status, "$act of $session: $body")
                    assertEquals("feedback", said(body, "reason"), "$act of $session: $body")
                    assertTrue(HOLDS_FEEDBACK in said(body, "error").orEmpty(), "$act of $session says why: $body")
                }
            }
            assertTrue(terminal.sent.isEmpty(), "nothing is typed into a holder's pane: ${terminal.sent}")
            assertTrue(terminal.held.isEmpty(), "no holder's pane is kept for a restart: ${terminal.held}")
            assertTrue(terminal.opened.isEmpty() && terminal.restarted.isEmpty(), "nothing is started")
            holders.forEach { (session, pane) -> assertEquals(pane, panes.paneFor(session), "$session stays") }
        }
    }

    // On Oct 27 three sessions due for a relaunch held queued drafts, and a session holding one may be in its turn,
    // running background shells or waiting on an answer too. The run names the draft of each, and still relaunches
    // every other session.
    @Test
    fun `splice sessions relaunch names each holder's draft whatever else holds it, and goes on with the others`() {
        val folder = home.resolve(".claude-claudex")
        serve { daemon ->
            val key = TurnKey(MgmtKey(paths)).get()
            val neighbor = startSeat(folder, NEIGHBOR, FIRST_PID + 3, key)
            val holders = startHeldOtherwise(folder, key)

            val (exit, lines) = daemon.relaunchAll()

            val relaunched = lines.indexOfFirst { NEIGHBOR in it && "relaunched" in it }
            for (session in holders.keys) {
                val left = lines.indexOfFirst { session in it && "left" in it }
                assertTrue(left >= 0 && HOLDS_FEEDBACK in lines[left], "$session is named with its draft: $lines")
                assertTrue(relaunched > left, "and the run goes on to the neighbor, which is relaunched: $lines")
            }
            assertEquals(listOf(neighbor to "/exit"), terminal.sent, "nothing is typed into a holder's pane")
            holders.forEach { (session, pane) -> assertEquals(pane, panes.paneFor(session), "$session stays") }
            assertEquals(1, exit, "a session left where it is fails the run, so it is run again: $lines")
        }
    }

    // ── more sessions, as they were started ─────────────────────────────────────────────────────────────

    /** Sessions in [folder], each holding a feedback draft it has not sent and held otherwise too: [IN_TURN] in its
     *  turn, [SHELLS] running background shells and [ASKING] waiting on an answer, each holding the turn [key] when one
     *  is given. They wrote their registrations after every session started before them, so a run comes to them first.
     *  Each with the pane it runs in. */
    private fun startHeldOtherwise(folder: Path, key: String?): Map<String, SessionPane> {
        val states = listOf(
            IN_TURN to """"status":"busy"""",
            SHELLS to """"status":"shell"""",
            ASKING to """"status":"waiting","waitingFor":"permission prompt"""",
        )
        return states.withIndex().associate { (n, held) ->
            val (session, state) = held
            val pid = FIRST_PID + 5 + n
            val pane = startSeat(folder, session, pid, key)
            register(session, pid, at = System.currentTimeMillis() + 60_000, state = state)
            feedbackDraft(folder, session, "queued")
            session to pane
        }
    }

    /** [OUTSIDE]: plain `claude` started from his own shell in his own ~/.claude, on no splice command, at rest at its
     *  prompt and holding a feedback draft it has not sent. */
    private fun startOutside(): SessionPane {
        val pid = FIRST_PID + 8
        direct += pid
        val own = home.resolve(".claude")
        val pane = start(OUTSIDE, pid, listOf(realClaude.toString()), environ = listOf("HOME=$home"), transcripts = own)
        feedbackDraft(own, OUTSIDE, "queued")
        return pane
    }
}
