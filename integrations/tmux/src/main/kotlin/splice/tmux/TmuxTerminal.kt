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
// WHY PASTE, NOT TYPING. A message is loaded into a tmux buffer and pasted with bracketed paste, so its line
// breaks arrive as part of one message instead of as Enter presses that would each submit a fragment of it;
// then one Enter submits it. Claude Code takes it at once when idle and queues it mid-turn, which is its
// behaviour and not splice's to change.
package splice.tmux

import splice.core.session.SessionKey
import splice.core.session.SessionPane
import splice.core.session.SessionTerminal
import java.nio.file.Path
import java.util.concurrent.TimeUnit

// why: a tmux client call answers in milliseconds; five seconds is a server that is wedged, not a slow one.
private const val CALL_DEADLINE_MS = 5_000L

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

    override fun open(sessionId: String, command: List<String>, directory: String): SessionPane {
        require(command.isNotEmpty()) { "a session needs a command to run" }
        val name = "splice-" + NAME_UNSAFE.replace(sessionId, "_")
        // `--` before the command: a session's own arguments start with dashes (`--name`, `--resume`), and tmux
        // would read them as its own options otherwise. The command is exec'd as given, through no shell.
        val made = tmux.must(
            listOf(
                "new-session", "-d", "-s", name, "-x", OPEN_COLUMNS, "-y", OPEN_ROWS, "-c", directory,
                "-P", "-F", "#{pane_id} #{socket_path}", "--",
            ) + command,
        ).trim()
        return TmuxPane(made.substringBefore(' '), made.substringAfter(' ')).pane
    }

    override fun send(pane: SessionPane, text: String) {
        val at = paneOf(pane) ?: return
        if (!isOpen(pane)) return
        val buffer = "splice-" + NAME_UNSAFE.replace(at.id, "_")
        tmux.must(listOf("load-buffer", "-b", buffer, "-"), at.socket, input = text)
        tmux.must(listOf("paste-buffer", "-d", "-p", "-b", buffer, "-t", at.id), at.socket)
        tmux.must(listOf("send-keys", "-t", at.id, "Enter"), at.socket)
    }

    override fun press(pane: SessionPane, key: SessionKey) {
        val at = paneOf(pane) ?: return
        if (!isOpen(pane)) return
        tmux.must(listOf("send-keys", "-t", at.id, keyName(key)), at.socket)
    }

    override fun screen(pane: SessionPane): String {
        val at = paneOf(pane) ?: return ""
        val reply = tmux.run(listOf("capture-pane", "-p", "-t", at.id), at.socket)
        return if (reply.ok) reply.out else ""
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

    override fun howToOpen(pane: SessionPane): String {
        val at = paneOf(pane) ?: return ""
        return "tmux -S ${quoted(at.socket)} attach -t ${quoted(at.id)}"
    }

    override fun isOpen(pane: SessionPane): Boolean {
        val at = paneOf(pane) ?: return false
        val reply = tmux.run(listOf("display-message", "-p", "-t", at.id, "#{pane_dead}"), at.socket)
        return reply.ok && reply.out.trim() == "0"
    }

    /** The key tmux sends for what the console asked to do. Every case is listed and there is no `else`: the
     *  day a key is added to the contract, this is a compile error rather than a press that quietly does
     *  the wrong thing. The nine numbered choices share one arm because they are one rule, their own digit. */
    private fun keyName(key: SessionKey): String = when (key) {
        SessionKey.STOP -> "Escape"
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
        val id = pane.id.substringBefore(PANE_AT, missingDelimiterValue = "")
        val socket = pane.id.substringAfter(PANE_AT, missingDelimiterValue = "")
        return if (PANE_ID.matches(id) && socket.startsWith("/")) TmuxPane(id, socket) else null
    }

    /** One shell word, so a socket path with a space in it still pastes into a terminal as one argument. */
    private fun quoted(word: String): String = "'" + word.replace("'", "'\\''") + "'"
}

// why: what separates a pane's number from its server's socket in the opaque id. A pane is `%` and digits and
// can never hold one, and the socket is everything after the first, so a socket path with one in it survives.
private const val PANE_AT = "@"

/** A pane on a server: tmux's pane id and the socket of the server that holds it. */
internal data class TmuxPane(val id: String, val socket: String) {
    /** The opaque form every caller holds. Nothing outside this module reads inside it. */
    val pane: SessionPane get() = SessionPane(id + PANE_AT + socket)
}

// why: the only form a tmux pane id takes, the same rule the launch record keeps (LaunchTerminal.valid).
private val PANE_ID = Regex("%[0-9]+")
