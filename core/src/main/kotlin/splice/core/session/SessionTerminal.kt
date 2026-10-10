// NEW: Oct 10, 2026 — the ONE contract for a session's terminal, so the console can start a session, send a
// message, answer a permission, stop a turn, end it, and tell the person how to open it (TMUX.md, Marlin).
//
// WHY A CONTRACT AND NOT tmux CALLS. Marcos, Oct 8: splice should not depend on another product, however
// universal. tmux is the FIRST IMPLEMENTATION, not the design. Everything above this file speaks these six
// acts and never a tmux word, so when seatd carries all six the tmux implementation is one module deleted,
// with nothing else to change. A `tmux` string anywhere outside that module is the defect this file exists
// to prevent.
//
// WHY THESE SIX ACTS. They are the table in TMUX.md, each written as what the PERSON does, never as how a
// terminal does it: [open] a session, [send] them a message, [press] the key that answers what is on the
// screen, [screen] to read the choices this Claude Code version is actually showing, [close] it, and
// [howToOpen] so they can sit in front of it themselves. Reading the screen is an act of its own because
// the choices belong to the client's version, never to a list splice keeps.
//
// SPLICE DRIVES ONLY WHAT IT OPENED. A [SessionPane] is handed out by [open] or by registering a pane
// splice launched, and every other act takes one. There is no act that takes a name, so a pane splice does
// not know cannot be typed into — the rule is kept by the shape, not by a check somebody has to remember.
package splice.core.session

/** One session's live terminal, as splice knows it. Opaque on purpose: what is inside is the implementation's
 *  business, and a caller that could read it would start depending on which implementation is running. */
public data class SessionPane(val id: String)

/** What the person's terminal can be asked to do, for a session splice opened. One implementation at a time
 *  (tmux today, seatd next); no caller names which. */
public interface SessionTerminal {
    /** Start [command] in a new terminal for this session, in [directory], and answer its pane. */
    public fun open(sessionId: String, command: List<String>, directory: String): SessionPane

    /** Give the session [text] as the person would: whole, line breaks intact, then submit it. The client
     *  takes it at once when idle and queues it mid-turn, which is its behaviour and not splice's to change. */
    public fun send(pane: SessionPane, text: String)

    /** Press one [key] — the answer to a permission, or the stop that ends a turn. Named keys only, so a
     *  caller never spells a terminal's escape sequence. */
    public fun press(pane: SessionPane, key: SessionKey)

    /** What the session is showing right now, so a caller reads the choices this client version offers
     *  instead of a list splice would have to keep in step with it. */
    public fun screen(pane: SessionPane): String

    /** End the session: let the client exit on its own, and close the terminal if it does not. */
    public fun close(pane: SessionPane)

    /** What the person types to sit in front of this session themselves. Shown, never run by splice. */
    public fun howToOpen(pane: SessionPane): String

    /** Whether [pane] is still live. A pane the person closed is gone, and acting on it is a no-op. */
    public fun isOpen(pane: SessionPane): Boolean

    /** The pane a session's own launch recorded ([LaunchTerminal][splice.core.process.LaunchTerminal]: the pane it
     *  ran in and the server holding it), as this terminal's pane, or null when it names none this terminal has. */
    public fun recorded(pane: String, server: String): SessionPane?

    /** Whether the client in front of [pane] right now is process [pid]: the pane still holds it and nothing else
     *  has taken the foreground. A pane whose session exited, or was put in the background, holds another client,
     *  and nothing is typed into it on that session's behalf. */
    public fun hosts(pane: SessionPane, pid: Long): Boolean
}

/** The keys the console sends, named for what they DO. A terminal's actual bytes are the implementation's. */
public enum class SessionKey {
    /** Stop the turn that is running. */
    STOP,

    /** Accept what is highlighted. */
    ACCEPT,

    /** Move the highlight down a choice, and [PREVIOUS] up one. */
    NEXT,
    PREVIOUS,

    /** Answer a numbered choice, 1 to 9, as the screen lists them. */
    CHOICE_1,
    CHOICE_2,
    CHOICE_3,
    CHOICE_4,
    CHOICE_5,
    CHOICE_6,
    CHOICE_7,
    CHOICE_8,
    CHOICE_9,

    /** Empty what the prompt holds, on the person's say-so: never as part of sending a message. */
    CLEAR,
}

/** Where splice remembers which pane belongs to which session, so the console can find a session's terminal
 *  after a restart, and so a session launched outside splice stays readable but undriveable (TMUX.md's first
 *  known limit) until it is resumed through a splice command. */
public interface SessionPanes {
    /** The pane splice opened or registered for [sessionId], or null when it knows none. */
    public fun paneFor(sessionId: String): SessionPane?

    /** Remember that [pane] carries [sessionId]. Called by the launch that started it. */
    public fun remember(sessionId: String, pane: SessionPane)

    /** Forget [sessionId]'s pane, once its session has ended. */
    public fun forget(sessionId: String)
}
