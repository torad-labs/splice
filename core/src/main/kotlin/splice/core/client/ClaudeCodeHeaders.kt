// NEW: LAN share (docs/specs/lan-share.md). The header Claude Code names a request's session in, read by the turn path
// (admission and a turn's session) and by the LAN share listener (the login a session is pinned to), so one spelling
// serves both.
package splice.core.client

/** Claude Code's own name for the session a request belongs to. */
public const val SESSION_HEADER: String = "x-claude-code-session-id"
