// NEW: Oct 10, 2026 — the console's session terminal on tmux, the FIRST implementation of core's contract
// (SessionTerminal.kt; console TMUX.md, Marlin, Oct 8). The only file in splice that knows how tmux is driven.
//
// WHY THE DEFAULT SERVER. A session splice opens is the person's as much as splice's: TMUX.md's last act is
// "open it in a terminal", and the way a person does that is `tmux attach` from whatever terminal they have.
// That reaches their default server, so that is where a session is opened, beside every seat they already run.
//
// WHY A PANE CARRIES ITS SERVER. tmux names a pane `%N`, and N is only unique inside one server. A launch
// recorded from inside the person's own tmux (splice-launch, 48be8c544) records the pane AND the socket it is
// on, so a pane splice did not open itself is still driven on the server that holds it, never on another
// server's pane that happens to share its number.
//
// WHY A PANE SPLICE OPENED CARRIES ITS PROCESS. N is unique inside one server only while that server lives: a
// server that exits and starts again on the same socket numbers its panes from %0 again, so a remembered `%3`
// can name a stranger's shell. A pane splice opens is recorded with the process splice started in it, the
// session's client, and it is live only while that process is still the pane's own; any other is not the
// terminal splice opened, and nothing is typed into it (PR #247 review 2).
//
// WHY PASTE, NOT TYPING. A message is loaded into a tmux buffer and pasted with bracketed paste, so its line
// breaks arrive as part of one message instead of as Enter presses that would each submit a fragment of it;
// then one Enter submits it. Claude Code takes it at once when idle and queues it mid-turn, which is its
// behaviour and not splice's to change. Each message has a buffer of its own: buffers belong to the server,
// shared by every client of it, so one named for the pane let a second message replace the first between its
// load and its paste.
package splice.tmux

import splice.core.session.SessionKey
import splice.core.session.SessionPane
import splice.core.session.SessionTerminal
import java.io.IOException
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

// why: a tmux client call answers in milliseconds; five seconds is a server that is wedged, not a slow one.
private const val CALL_DEADLINE_MS = 5_000L

// why: one Ctrl-U clears one line of what the prompt holds and does nothing on an empty prompt, so this many clears
// any message a person writes. Each is its own tmux call: Claude Code 2.1.296 drops 64 Ctrl-U sent in one burst
// (they reach it as one chunk) and honours them pressed one at a time (desk walk, Oct 10).
private const val CLEAR_PRESSES = 64

// why: how long the client gets to exit on its own after /exit before its terminal is closed under it. Claude
// Code saves the session on the way out, and that is the one thing a close must not cut short.
private const val EXIT_GRACE_MS = 10_000L

// why: how often a close looks again for the client to have gone.
private const val EXIT_POLL_MS = 100L

// why: the size a session opens at before anyone attaches, wide enough that a permission's choices are not
// wrapped mid-word when splice reads them off the screen. Attaching resizes it to the person's terminal.
private const val OPEN_COLUMNS = "200"
private const val OPEN_ROWS = "50"

// why: what the client is told to leave on, the same word a person types.
private const val EXIT_WORD = "/exit"

// why: tmux reads a session name's `.` and `:` as a window and a pane, so a name keeps only these.
private val NAME_UNSAFE = Regex("[^A-Za-z0-9_-]")

// why: what a numbered choice is named in the contract, so its own digit is what the key press sends.
private const val CHOICE_PREFIX = "CHOICE_"

/** A session's terminal on tmux. [socket] is a server of its own, or null for the person's default server. */
public class TmuxTerminal(
    socket: Path? = null,
    binary: String = "tmux",
    private val exitWaitMs: Long = EXIT_GRACE_MS,
) : SessionTerminal {
    private val tmux = TmuxCommand(binary, socket, CALL_DEADLINE_MS)

    override fun open(sessionId: String, command: List<String>, directory: String, beside: SessionPane?): SessionPane {
        require(command.isNotEmpty()) { "a session needs a command to run" }
        val name = "splice-" + NAME_UNSAFE.replace(sessionId, "_")
        // `--` before the command: a session's own arguments start with dashes (`--name`, `--resume`), and tmux
        // would read them as its own options otherwise. The command is exec'd as given, through no shell.
        val made = tmux.must(
            listOf(
                "new-session", "-d", "-s", name, "-x", OPEN_COLUMNS, "-y", OPEN_ROWS, "-c", directory,
                "-P", "-F", "#{pane_id} #{pane_pid} #{socket_path}", "--",
            ) + command,
            beside?.let(::paneOf)?.socket,
        ).trim()
        // The pane, the process the command became in it, and the server: a socket path may hold spaces, the
        // first two never do.
        val (id, rest) = made.substringBefore(' ') to made.substringAfter(' ')
        val owner = requireNotNull(rest.substringBefore(' ').toLongOrNull()) { "tmux named no process for $id" }
        return TmuxPane(id, rest.substringAfter(' '), owner).pane
    }

    override fun send(pane: SessionPane, text: String) {
        val at = paneOf(pane) ?: return
        if (!isOpen(pane)) return
        val buffer = "splice-" + NAME_UNSAFE.replace(at.id, "_") + "-" + UUID.randomUUID()
        tmux.must(listOf("load-buffer", "-b", buffer, "-"), at.socket, input = text)
        try {
            tmux.must(listOf("paste-buffer", "-d", "-p", "-b", buffer, "-t", at.id), at.socket)
        } catch (refused: IOException) {
            // a message that was not pasted is not left on the server for another paste to find
            tmux.run(listOf("delete-buffer", "-b", buffer), at.socket)
            throw refused
        }
        tmux.must(listOf("send-keys", "-t", at.id, "Enter"), at.socket)
    }

    override fun press(pane: SessionPane, key: SessionKey) {
        val at = paneOf(pane) ?: return
        if (!isOpen(pane)) return
        keyNames(key).forEach { tmux.must(listOf("send-keys", "-t", at.id, it), at.socket) }
    }

    /** A capture tmux refused throws with tmux's own line, as one that did not answer does: a screen splice could not
     *  read is never an empty one, which a caller would take for an empty prompt. */
    override fun screen(pane: SessionPane): String {
        val at = paneOf(pane) ?: return ""
        return tmux.must(listOf("capture-pane", "-p", "-t", at.id), at.socket)
    }

    override fun close(pane: SessionPane) {
        val at = paneOf(pane) ?: return
        if (!isOpen(pane)) return
        send(pane, EXIT_WORD)
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(exitWaitMs)
        while (isOpen(pane) && System.nanoTime() < until) Thread.sleep(EXIT_POLL_MS)
        // A client that did not leave on its own is ended with its terminal. A pane that went in the meantime
        // makes kill-pane refuse, and that refusal is the outcome asked for, so it is not an error here.
        if (isOpen(pane)) tmux.run(listOf("kill-pane", "-t", at.id), at.socket)
    }

    /** Live, and for a pane splice opened, still running the process splice started in it: the same number on a
     *  server that started again is another terminal, which splice did not open. */
    override fun isOpen(pane: SessionPane): Boolean {
        val at = paneOf(pane) ?: return false
        val reply = tmux.run(listOf("display-message", "-p", "-t", at.id, "#{pane_dead} #{pane_pid}"), at.socket)
        val shown = reply.out.trim().split(' ')
        val live = reply.ok && shown.first() == "0"
        return live && (at.owner == null || shown.getOrNull(1)?.toLongOrNull() == at.owner)
    }

    /** A launch records a pane by its number alone, so the pane answered carries no process: [hosts] is what checks
     *  that one. */
    override fun recorded(pane: String, server: String): SessionPane? =
        TmuxPane(pane, server).pane.takeIf { PANE_ID.matches(pane) && paneOf(it) != null }

    /** [pid] is the pane's own process or runs under it, and, where the system shows it, is in the foreground of
     *  the pane's terminal: a session suspended to its shell is still under the pane but no longer the one typed to. */
    override fun hosts(pane: SessionPane, pid: Long): Boolean {
        val at = paneOf(pane) ?: return false
        val reply = tmux.run(listOf("display-message", "-p", "-t", at.id, "#{pane_pid}"), at.socket)
        val root = reply.out.trim().toLongOrNull()?.takeIf { reply.ok } ?: return false
        return ForegroundProcess.under(pid, root) && ForegroundProcess.inFront(pid) != false
    }

    /** The key tmux sends for what the console asked to do. Every case is listed and there is no `else`: the
     *  day a key is added to the contract, this is a compile error rather than a press that quietly does
     *  the wrong thing. The nine numbered choices share one arm because they are one rule, their own digit. */
    private fun keyNames(key: SessionKey): List<String> = if (key == SessionKey.CLEAR) {
        List(CLEAR_PRESSES) { "C-u" }
    } else {
        listOf(keyName(key))
    }

    private fun keyName(key: SessionKey): String = when (key) {
        SessionKey.CLEAR -> "C-u"
        SessionKey.STOP -> "Escape"
        SessionKey.INTERRUPT -> "C-c"
        SessionKey.ACCEPT -> "Enter"
        SessionKey.NEXT -> "Down"
        SessionKey.PREVIOUS -> "Up"
        SessionKey.CHOICE_1, SessionKey.CHOICE_2, SessionKey.CHOICE_3,
        SessionKey.CHOICE_4, SessionKey.CHOICE_5, SessionKey.CHOICE_6,
        SessionKey.CHOICE_7, SessionKey.CHOICE_8, SessionKey.CHOICE_9,
        -> key.name.substringAfter(CHOICE_PREFIX)
    }

    /** The pane an opaque id names, or null when it names no pane at all.
     *
     *  THIS IS THE SHAPE HALF of "splice drives only what it opened": an id that is not a pane and a server
     *  cannot be acted on, so the opaque id can never be a name somebody typed. It is NOT a check that splice
     *  opened this particular pane, and it must not become one — a pane recorded by a launch inside the
     *  person's own tmux (LaunchTerminal, 48be8c544) is exactly a pane this process never opened, and that is
     *  the one case the record exists for. WHICH panes splice knows is the pane record's half, and a pane
     *  whose server is gone is not refused here: every act simply reaches nothing. */
    private fun paneOf(pane: SessionPane): TmuxPane? {
        val held = PANE_HELD.matchEntire(pane.id.substringBefore(PANE_AT, missingDelimiterValue = ""))
        val socket = pane.id.substringAfter(PANE_AT, missingDelimiterValue = "")
        if (held == null || !socket.startsWith("/")) return null
        return TmuxPane(held.groupValues[1], socket, held.groupValues[2].toLongOrNull())
    }
}

// why: what separates a pane's number from its server's socket in the opaque id. A pane is `%` and digits and
// can never hold one, and the socket is everything after the first, so a socket path with one in it survives.
private const val PANE_AT = "@"

// why: what separates a pane's number from the process splice started in it, in the id of a pane splice opened.
// Neither a pane number nor a pid can hold one.
private const val OWNER_AT = "+"

/** A pane on a server: tmux's pane id, the socket of the server that holds it, and, for a pane splice opened, the
 *  process splice started in it. */
internal data class TmuxPane(val id: String, val socket: String, val owner: Long? = null) {
    /** The opaque form every caller holds. Nothing outside this module reads inside it. */
    val pane: SessionPane get() = SessionPane(id + owner?.let { OWNER_AT + it }.orEmpty() + PANE_AT + socket)
}

// why: the only form a tmux pane id takes, the same rule the launch record keeps (LaunchTerminal.valid).
private val PANE_ID = Regex("%[0-9]+")

// why: a pane id, then the pid splice started in it when it opened the pane; eighteen digits always fit a Long.
private val PANE_HELD = Regex("(%[0-9]+)(?:\\+([0-9]{1,18}))?")
