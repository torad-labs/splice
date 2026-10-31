// WALLS for the console's session terminal, driven against a REAL tmux server on this test's own socket.
//
// Why a real server and not a fake: every act here is a claim about what tmux does — that a pasted message
// keeps its line breaks instead of submitting a fragment per line, that a pane id is only unique inside one
// server, that a dead pane reports itself dead. A fake tmux would be me writing down what I believe tmux does
// and then checking my own belief, which is the one thing a test of an adapter cannot be allowed to do.
//
// The server is its own socket under @TempDir, so these tests never touch the person's own tmux, and it is
// killed in teardown.
package splice.tmux

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.core.session.SessionKey
import splice.core.session.SessionPane
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

// why: how long a test waits for a real tmux to show what it was told; a client answers in milliseconds, so
// ten seconds is a wedged server and not a slow one. The wait is a POLL against this deadline, never a sleep.
private const val DEADLINE_MS = 10_000L

class TmuxTerminalTest {
    @TempDir
    lateinit var tmp: Path

    private lateinit var socket: Path
    private lateinit var terminal: TmuxTerminal

    @BeforeEach
    fun server() {
        socket = tmp.resolve("tmux.sock")
        // exitWaitMs short: these sessions run `cat`, which leaves the moment its input closes, and a test
        // that waited the production ten seconds for a client that is never coming would just be slow.
        terminal = TmuxTerminal(socket = socket, exitWaitMs = 500, settleMs = 300)
    }

    @AfterEach
    fun teardown() {
        killServer(socket)
    }

    /** Ends the whole server, so a test that failed before closing its pane leaves no `cat` running under a
     *  socket JUnit is about to delete.
     *
     *  WHAT IS CHECKED IS THE OUTCOME, NOT tmux's WORDING. kill-server refuses in two different sentences —
     *  "no server running on <path>" when the socket is there with nothing behind it, and "error connecting
     *  to <path> (No such file or directory)" when a test never opened a session at all — and both of those
     *  ARE the outcome teardown asked for. Matching the prose failed on the second. So the act is run and
     *  then the question is asked plainly: is any session still there? */
    private fun killServer(at: Path) {
        val ended = TmuxCommand("tmux", at, DEADLINE_MS).run(listOf("kill-server"))
        val left = TmuxCommand("tmux", at, DEADLINE_MS).run(listOf("list-sessions"))
        check(!left.ok) { "a session is still running on $at; kill-server said: ${ended.out.trim()}" }
    }

    /** A session running [command] on this test's own server. Teardown ends the server, so nothing a test
     *  opens outlives it whether the test closed its pane or not. */
    private fun open(vararg command: String): SessionPane =
        terminal.open("11111111-2222-4333-8444-555555555555", command.toList(), tmp.toString())

    /** The tmux pane number inside an opaque id, as tmux itself takes it. */
    private fun number(pane: SessionPane): String = pane.id.substringBefore('@').substringBefore('+')

    /** Polls [screen] until [holds], and answers the last screen it read. Never sleeps for a duration. */
    private fun screenUntil(pane: SessionPane, holds: (String) -> Boolean): String {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MS)
        var last = ""
        while (System.nanoTime() < until) {
            last = terminal.screen(pane)
            if (holds(last)) return last
        }
        return last
    }

    @Test
    fun `a session opens live in its own directory and closes`() {
        val pane = open("sh", "-c", "pwd; cat")

        assertTrue(terminal.isOpen(pane), "the session splice just opened is live")
        val screen = screenUntil(pane) { it.contains(tmp.toString()) }
        assertTrue(screen.contains(tmp.toString()), "it runs in the directory it was opened in: $screen")

        terminal.close(pane)

        assertFalse(terminal.isOpen(pane), "and it is gone once closed")
    }

    /** The reason a message is pasted rather than typed. Line breaks inside one message have to arrive as
     *  part of that message; typed, each would be an Enter that submitted the fragment before it. */
    @Test
    fun `a message with line breaks arrives whole, in order, as one message`() {
        // The session TRANSFORMS each line it reads. A plain `cat` would not do: the pty echoes what arrives
        // and cat writes it again, so the screen carries every line twice and in an order neither of them owns.
        val pane = open("sh", "-c", "while IFS= read -r line; do echo \"got:\$line\"; done")

        terminal.send(pane, "first line\nsecond line\nthird line")

        val screen = screenUntil(pane) { it.contains("got:third line") }
        // Not line-anchored: the pty echoes the paste with no trailing newline, so the session's first answer
        // starts on the same screen row as the last line it echoed ("third linegot:first line").
        assertEquals(
            listOf("first line", "second line", "third line"),
            Regex("got:(.*)").findAll(screen).map { it.groupValues[1].trim() }.toList(),
            "every line of the one message arrived as a line of it, in the order it was written: $screen",
        )
    }

    /** A Stop before any reply leaves the person's message back in the prompt; cleared on his say-so, the next
     *  message is its own. */
    @Test
    fun `a clear empties what the prompt held, so the message after it arrives alone`() {
        val pane = open("sh", "-c", "while IFS= read -r line; do echo \"got:\$line\"; done")
        val typed = listOf("send-keys", "-t", number(pane), "-l", "leftover")
        TmuxCommand("tmux", socket, DEADLINE_MS).run(typed)

        terminal.press(pane, SessionKey.CLEAR)
        terminal.send(pane, "hello")

        val screen = screenUntil(pane) { it.contains("got:") }
        val got = Regex("got:(.*)").findAll(screen).map { it.groupValues[1].trim() }.toList()
        assertEquals(listOf("hello"), got, "the leftover was cleared, not sent with it: $screen")
    }

    @Test
    fun `a key press reaches the session`() {
        val pane = open("sh", "-c", "read -r answer; echo \"[\$answer]\"; cat")

        terminal.press(pane, SessionKey.ACCEPT)

        val screen = screenUntil(pane) { it.contains("[]") }
        assertTrue(screen.contains("[]"), "the Enter the console sent answered the line the session was on: $screen")
    }

    /** An id that is not a pane at all is refused by SHAPE, in every act, rather than by a check each act
     *  has to remember. This is the half of "splice drives only what it opened" that the terminal keeps:
     *  the opaque id cannot be a name somebody typed. WHICH panes splice knows is the pane record's half. */
    @Test
    fun `an id that is not a pane is refused by every act and never throws`() {
        val nonsense = SessionPane("not-a-pane")

        assertFalse(terminal.isOpen(nonsense), "splice makes no claim about an id that names no pane")
        assertEquals("", terminal.screen(nonsense), "and reads nothing off it")
        terminal.send(nonsense, "type this")
        terminal.press(nonsense, SessionKey.STOP)
        terminal.close(nonsense)
    }

    /** A WELL-FORMED pane on a server that is not there is not a refusal, it is nothing happening. This is
     *  the pane a launch registered (LaunchTerminal, 48be8c544) whose terminal the person has since closed:
     *  the console asks, learns it is not live, and no act of it reaches anything. Refusing the SHAPE here
     *  would make every launch-registered session undrivable, which is the one case the record exists for. */
    @Test
    fun `a pane whose server is gone is simply not live, and acting on it reaches nothing`() {
        val elsewhere = SessionPane("%0@" + tmp.resolve("never-started.sock"))

        assertFalse(terminal.isOpen(elsewhere), "a server that was never started holds no live pane")
        assertThrows<IOException>("a screen that cannot be read says so, never reads as an empty prompt") {
            terminal.screen(elsewhere)
        }
        terminal.send(elsewhere, "type this")
        terminal.press(elsewhere, SessionKey.STOP)
        terminal.close(elsewhere)
    }

    /** A pane id is only unique inside ONE server, which is why a pane carries its socket. */
    @Test
    fun `a pane is driven on its own server, never on another holding the same number`() {
        val other = tmp.resolve("other.sock")
        val elsewhere = TmuxTerminal(socket = other, exitWaitMs = 500)
        val mine = open("cat")
        val theirs = elsewhere.open("22222222-2222-4333-8444-555555555555", listOf("cat"), tmp.toString())
        try {
            assertEquals(
                number(mine),
                number(theirs),
                "both servers call their first pane the same number, which is the hazard",
            )
            assertTrue(mine.id != theirs.id, "and the pane splice holds is still the two of them apart")

            elsewhere.close(theirs)

            assertTrue(terminal.isOpen(mine), "closing the other server's pane of the same number left mine alone")
        } finally {
            killServer(other)
        }
    }

    /** A session moved after its own terminal closed goes on among the terminals it ran in: its server, not the
     *  person's default one, which on another desk or under another home has another environment. */
    @Test
    fun `a terminal opened beside a pane opens on that pane's server`() {
        val other = tmp.resolve("other.sock")
        val elsewhere = TmuxTerminal(socket = other, exitWaitMs = 500)
        val theirs = elsewhere.open("22222222-2222-4333-8444-555555555555", listOf("cat"), tmp.toString())
        try {
            val moved = terminal.open("33333333-2222-4333-8444-555555555555", listOf("cat"), tmp.toString(), theirs)

            assertEquals(theirs.id.substringAfter('@'), moved.id.substringAfter('@'), "it opened on the other server")
            assertTrue(elsewhere.isOpen(moved), "and runs there")
        } finally {
            killServer(other)
        }
    }

    @Test
    fun `a pane a launch recorded is driven only while that launch's process is the one in front of it`() {
        val pane = open("sh", "-c", "echo pid=\$\$; exec cat")
        val pid = screenUntil(pane) { "pid=" in it }.substringAfter("pid=").lines().first().trim().toLong()

        val recorded = requireNotNull(terminal.recorded(number(pane), socket.toString()))
        assertTrue(terminal.isOpen(recorded), "the launch's record names the live pane")
        assertTrue(terminal.hosts(recorded, pid), "the process the pane was started with is in front of it")
        assertFalse(terminal.hosts(recorded, ProcessHandle.current().pid()), "a process the pane never held is not")
        assertEquals(null, terminal.recorded("not-a-pane", socket.toString()))
    }

    /** A server that exits and starts again on the same socket numbers its panes from the start again. The pane
     *  splice opened is gone with it, and the stranger's shell that now has its number is never typed into. */
    @Test
    fun `a pane splice opened is not live once a restarted server gives its number to another shell`() {
        val pane = open("cat")
        killServer(socket)
        val shell = listOf("sh", "-c", "while IFS= read -r line; do echo \"got:\$line\"; done")
        val stranger = TmuxCommand("tmux", socket, DEADLINE_MS)
            .must(listOf("new-session", "-d", "-P", "-F", "#{pane_id}", "--") + shell).trim()
        assertEquals(number(pane), stranger, "the restarted server gave its first pane the same number")

        assertFalse(terminal.isOpen(pane), "the pane splice opened is gone, whatever holds its number now")
        terminal.send(pane, "hello")
        terminal.press(pane, SessionKey.ACCEPT)

        // A line typed to the shell directly, after the acts above: the shell answers in order, so once it has
        // answered this one, anything those acts had typed would be on the screen before it.
        val theirs = requireNotNull(terminal.recorded(stranger, socket.toString()))
        TmuxCommand("tmux", socket, DEADLINE_MS).must(listOf("send-keys", "-t", stranger, "-l", "marker"))
        TmuxCommand("tmux", socket, DEADLINE_MS).must(listOf("send-keys", "-t", stranger, "Enter"))
        val screen = screenUntil(theirs) { "got:marker" in it }
        assertTrue("got:marker" in screen, "the shell answered: $screen")
        assertFalse("hello" in screen, "nothing splice meant for its session reached the shell: $screen")
    }

    // A relaunch comes back where the session ran (Oct 26, 2026): its pane is kept once its program ends, and the
    // session starts again in that same pane, in the same tmux session and window its starter and the person find it
    // by. A relaunch that opened a new session instead left them looking for it.

    @Test
    fun `a held pane outlives its program, and the session starts again in the same session, window and pane`() {
        val pane = open("sh", "-c", "read -r line")
        val before = where(pane)
        val elsewhere = Files.createDirectories(tmp.resolve("elsewhere"))

        assertTrue(terminal.hold(pane), "the pane is kept once its program ends")
        terminal.send(pane, "bye")
        assertTrue(until { !terminal.isOpen(pane) }, "its program ended")
        val again = terminal.restart(pane, listOf("sh", "-c", "pwd; exec cat"), elsewhere.toString())

        assertNotNull(again, "a pane whose program ended is started again")
        val restarted = checkNotNull(again)
        assertTrue(terminal.isOpen(restarted), "the command runs there now, as the pane's own process")
        assertEquals(before, where(restarted), "in the same session, window and pane")
        val screen = screenUntil(restarted) { it.contains(elsewhere.toString()) }
        assertTrue(screen.contains(elsewhere.toString()), "in the folder it was given: $screen")
    }

    @Test
    fun `a program still running in the pane is never ended to start another`() {
        val pane = open("cat")

        assertTrue(terminal.hold(pane))
        assertNull(terminal.restart(pane, listOf("sh", "-c", "echo replaced; exec cat"), tmp.toString()))

        assertTrue(terminal.isOpen(pane), "the program it ran still runs there")
        assertFalse(terminal.screen(pane).contains("replaced"), "and nothing else was started in its place")
    }

    @Test
    fun `a pane given back closes with its program again, as it did before it was held`() {
        val pane = open("sh", "-c", "read -r line")

        assertTrue(terminal.hold(pane))
        terminal.release(pane)
        terminal.send(pane, "bye")

        assertTrue(until { !exists(pane) }, "given back, it closed with its program")
    }

    @Test
    fun `a pane its owner keeps on its own is still kept once given back`() {
        val pane = open("cat")
        tmux().must(listOf("set-option", "-p", "-t", number(pane), "remain-on-exit", "on"))

        assertTrue(terminal.hold(pane))
        terminal.release(pane)

        val own = tmux().must(listOf("show-options", "-p", "-v", "-t", number(pane), "remain-on-exit")).trim()
        assertEquals("on", own, "the setting it had is the setting it keeps")
    }

    @Test
    fun `a pane that is not there is not kept, and is never started again`() {
        val gone = SessionPane("%0@" + tmp.resolve("never-started.sock"))

        assertFalse(terminal.hold(gone), "no pane is kept that is not there")
        assertFalse(terminal.hold(SessionPane("not-a-pane")), "nor one an id cannot name")
        assertThrows<IOException>("a start where nothing can start says so") {
            terminal.restart(gone, listOf("cat"), tmp.toString())
        }
        terminal.release(gone)
    }

    private fun tmux() = TmuxCommand("tmux", socket, DEADLINE_MS)

    /** The tmux session, window and pane [pane] is in, as tmux names them. */
    private fun where(pane: SessionPane): String = tmux()
        .must(listOf("display-message", "-p", "-t", number(pane), "#{session_name} #{window_index} #{pane_id}")).trim()

    /** Whether tmux still has [pane] at all, live or kept after its program ended. */
    private fun exists(pane: SessionPane): Boolean =
        tmux().run(listOf("list-panes", "-a", "-F", "#{pane_id}")).let { it.ok && number(pane) in it.out.lines() }

    /** Polls until [holds], and answers whether it did before the deadline. Never sleeps for a duration. */
    private fun until(holds: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MS)
        while (System.nanoTime() < deadline) {
            if (holds()) return true
        }
        return holds()
    }
}
