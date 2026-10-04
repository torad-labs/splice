// NEW: V4-160 — the Claude Code reader's package-local record keys. The transcript response vocabulary
// moved to :features-sessions in LAYOUT-01; concrete file traversal stays here.
package splice.client.transcript

// why: the two transcript record keys this package reads. They live here rather than in either
// reader because V4-160 split one file into several and each half would otherwise carry its own
// copy — which is exactly what the const-single-source wall caught on 2026-09-18.
internal const val MESSAGE = "message"
internal const val CONTENT = "content"
