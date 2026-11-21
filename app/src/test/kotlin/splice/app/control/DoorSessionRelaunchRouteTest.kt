// door-sessions (Nov 20, 2026): POST /api/sessions/{id}/relaunch and `splice sessions relaunch` for a session started
// on a door, this machine's or another machine's, through a real ControlServer (SessionRelaunchRig). Such a session
// runs on no head of its own, and is relaunched as a head's session is, never refused as one on no splice command:
// started again in its pane by the command its launch recorded, which goes to the same door, in his own ~/.claude, with
// his words and on the model its row reports. A command that is not on this machine is refused, naming it, before
// anything is typed.
package splice.app.control

import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.config.MgmtKey
import splice.core.config.TurnKey
import splice.core.session.SessionPane
import splice.core.topology.LaunchDoor
import java.nio.file.Files

/** Sessions started on a door: this machine's, and another machine's through `[remote.boxdoor]`. */
private const val DOOR_HERE = "5e1a0009-0000-4000-8000-000000000009"
private const val DOOR_BOX = "5e1a000a-0000-4000-8000-00000000000a"
private const val BOX_COMMAND = "boxdoor"

/** The model each session's transcript last ran, which its row reports. */
private const val ROW_MODEL = "claude-opus-5-5"

// why: above the kernel's pid_max (4194304), so no process on the machine running the test answers to these pids
private const val FIRST_PID = 5_000_001L

internal class DoorSessionRelaunchRouteTest : SessionRelaunchRig() {
    @Test
    fun `a session on this machine's door comes back in its pane on the claude it was started with`() {
        serve { daemon ->
            val pane = startDoor(DOOR_HERE, FIRST_PID + 10, LaunchDoor("claude", "http://127.0.0.1:3096/door", null))

            val reply = daemon.relaunch(DOOR_HERE)

            assertEquals(HttpStatusCode.OK, reply.first, reply.second)
            val claude = bin.resolve("claude").toString()
            assertEquals(
                listOf(
                    Restart(
                        pane,
                        listOf("env", "-u", "CLAUDE_CONFIG_DIR", claude, "--agent", "reviewer") +
                            listOf("-r", DOOR_HERE, "--model", ROW_MODEL),
                        cwd(DOOR_HERE).toString(),
                    ),
                ),
                terminal.restarted,
                "it starts again in its pane on his wrapped claude, which goes to the door again",
            )
            assertEquals(listOf(pane to "/exit"), terminal.sent, "its client left on its own exit")
        }
    }

    @Test
    fun `a door session whose command is not linked is refused naming it, before anything is typed`() {
        serve(wrapped = false) { daemon ->
            val here = startDoor(DOOR_HERE, FIRST_PID + 10, LaunchDoor("claude", "http://127.0.0.1:3096/door", null))
            val box = startDoor(
                DOOR_BOX,
                FIRST_PID + 11,
                LaunchDoor(BOX_COMMAND, "http://devbox.lan:3097/door", "devbox.lan"),
            )

            val hereReply = daemon.relaunch(DOOR_HERE)
            val boxReply = daemon.relaunch(DOOR_BOX)

            assertEquals(HttpStatusCode.Conflict, hereReply.first, hereReply.second)
            assertEquals("The claude command is not linked; run splice install.", said(hereReply.second, "error"))
            assertEquals(HttpStatusCode.Conflict, boxReply.first, boxReply.second)
            assertEquals(
                "The $BOX_COMMAND command is not linked; run splice install $BOX_COMMAND.",
                said(boxReply.second, "error"),
            )
            assertTrue(terminal.sent.isEmpty() && terminal.restarted.isEmpty(), "nothing typed, nothing started")
            assertEquals(here, panes.paneFor(DOOR_HERE), "each stays where it runs")
            assertEquals(box, panes.paneFor(DOOR_BOX), "each stays where it runs")
        }
    }

    @Test
    fun `a session on another machine's door comes back on the command its remote add linked`() {
        serve { daemon ->
            val linked = bin.resolve(BOX_COMMAND)
            Files.createSymbolicLink(linked, share.resolve("splice-launch"))
            val pane = startDoor(
                DOOR_BOX,
                FIRST_PID + 11,
                LaunchDoor(BOX_COMMAND, "http://devbox.lan:3097/door", "devbox.lan"),
            )

            val reply = daemon.relaunch(DOOR_BOX)

            assertEquals(HttpStatusCode.OK, reply.first, reply.second)
            assertEquals(
                listOf(
                    Restart(
                        pane,
                        listOf("env", "-u", "CLAUDE_CONFIG_DIR", linked.toString(), "--agent", "reviewer") +
                            listOf("-r", DOOR_BOX, "--model", ROW_MODEL),
                        cwd(DOOR_BOX).toString(),
                    ),
                ),
                terminal.restarted,
                "it starts again on the command that goes to the same box's door",
            )
        }
    }

    // `splice sessions relaunch` takes door sessions with the sessions on heads, and names each by the words
    // `splice sessions` uses for it. One it leaves is resumed by hand with the command its launch recorded.
    @Test
    fun `splice sessions relaunch names a door session by its door and resumes it by its command`() {
        serve { daemon ->
            val key = TurnKey(MgmtKey(paths)).get()
            val here = LaunchDoor("claude", "http://127.0.0.1:3096/door", null)
            val box = LaunchDoor(BOX_COMMAND, "http://devbox.lan:3097/door", "devbox.lan")
            startDoor(DOOR_HERE, FIRST_PID + 10, here, key)
            startDoor(DOOR_BOX, FIRST_PID + 11, box, key)
            // both in their turns, so the daemon leaves each where it is
            register(DOOR_HERE, FIRST_PID + 10, state = """"status":"busy"""")
            register(DOOR_BOX, FIRST_PID + 11, state = """"status":"busy"""")

            val (exit, lines) = daemon.relaunchAll()

            val hereLine = lines.firstOrNull { DOOR_HERE in it && "left" in it }.orEmpty()
            val boxLine = lines.firstOrNull { DOOR_BOX in it && "left" in it }.orEmpty()
            assertTrue("$DOOR_HERE on door where" in hereLine, "this machine's door is named `door`: $lines")
            assertTrue("resume it with `claude -r $DOOR_HERE`" in hereLine, "resumed by its command: $lines")
            assertTrue("$DOOR_BOX on door on devbox.lan where" in boxLine, "another's is `door on <host>`: $lines")
            assertTrue("resume it with `$BOX_COMMAND -r $DOOR_BOX`" in boxLine, "resumed by its command: $lines")
            assertTrue(lines.none { "no running session" in it }, "door sessions are not passed over: $lines")
            assertEquals(1, exit, "a session left where it is fails the run, so it is run again: $lines")
        }
    }

    /** [session], plain `claude` started on [door] in his own ~/.claude: the launch's overlay, then his agent, and the
     *  turn [key] its launch planted when one is given. */
    private fun startDoor(session: String, pid: Long, door: LaunchDoor, key: String? = null): SessionPane {
        doorOf[session] = door
        return start(
            session,
            pid,
            listOf(realClaude.toString(), "--settings", """{"modelPicker":[]}""", "--agent", "reviewer"),
            environ = listOf("SPLICE=1") + listOfNotNull(key?.let { "ANTHROPIC_AUTH_TOKEN=$it" }),
            transcripts = home.resolve(".claude"),
        )
    }
}
