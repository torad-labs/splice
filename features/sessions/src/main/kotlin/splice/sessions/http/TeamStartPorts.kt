// NEW: Oct 10, 2026 — what starting a team member's session needs from the daemon, as ports (split from TeamStart).
package splice.sessions.http

import splice.core.session.SessionPanes
import splice.core.session.SessionTerminal

/** A terminal and the record of which of its panes carries which session: two halves of one capability. */
public data class SessionDriver(val terminal: SessionTerminal, val panes: SessionPanes)

/** The daemon's session driver, read per request: null until one is wired. */
public fun interface TerminalSource {
    public operator fun invoke(): SessionDriver?
}

/** What a head's launch command is, or why it cannot be launched. */
public sealed class StartCommand {
    /** The argv that starts the head's Claude Code, before the flags a member adds. */
    public data class Ready(val argv: List<String>) : StartCommand()

    /** The sentence the person reads, including the fix when there is one. */
    public data class Refused(val reason: String) : StartCommand()
}

/** The launch command of a head, by its key. */
public fun interface StartCommands {
    public fun forHead(head: String): StartCommand
}

/** Pins one session to one account of its head's pool; false when the head has no such account. */
public fun interface AccountPins {
    public fun pin(head: String, label: String, session: String): Boolean
}

/** Whether a session Claude Code was started with registers itself within [seconds]. */
public fun interface SessionArrival {
    public suspend fun arrived(session: String, seconds: Long): Boolean
}
