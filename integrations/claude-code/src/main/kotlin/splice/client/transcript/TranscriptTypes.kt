// NEW: V4-160 — the Claude Code reader's package-local record keys. The transcript response vocabulary
// moved to :features-sessions in LAYOUT-01; concrete file traversal stays here.
package splice.client.transcript

// why: the two transcript record keys this package reads. They live here rather than in either
// reader because V4-160 split one file into several and each half would otherwise carry its own
// copy — which is exactly what the const-single-source wall caught on 2026-09-18.
internal const val MESSAGE = "message"
internal const val CONTENT = "content"

/** What Claude Code writes in place of an assistant message its signature recovery leaves empty, and what a move
 *  to another model writes for a row it strips bare (resume/AssistantRowMove). It is plumbing, not something the
 *  agent said, so a conversation leaves it out the way it leaves out thinking. */
internal const val THINKING_STAND_IN = "[Thinking removed]"
