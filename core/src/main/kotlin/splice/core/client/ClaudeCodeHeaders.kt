// NEW: LAN share (docs/specs/lan-share.md). The header Claude Code names a request's session in, read by the turn path
// (admission and a turn's session) and by the LAN share listener (the login a session is pinned to), so one spelling
// serves both.
package splice.core.client

/** Claude Code's own name for the session a request belongs to. */
public const val SESSION_HEADER: String = "x-claude-code-session-id"

/** Claude Code's own name for the subagent a request comes from; absent on a session's main thread. Two subagents of
 *  one session can open with identical words, which a conversation key cannot tell apart (fix 4, Oct 27). */
public const val AGENT_HEADER: String = "x-claude-code-agent-id"
