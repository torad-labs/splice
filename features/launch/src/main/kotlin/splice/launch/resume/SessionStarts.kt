// NEW: Oct 13, 2026 — what else a session's start tells the daemon, beyond the head's ownership record: the
// SessionStart hook is the one moment splice knows a new session's head, id and folder before its first turn.
// Teams binds by rule here (TeamRuleBinding); the launch module names no team.
package splice.launch.resume

/** A session started on the head keyed [head] in [cwd]. Answers one line for the daemon log, or null. */
public fun interface SessionStarts {
    public fun started(head: String, sessionId: String, cwd: String): String?
}
