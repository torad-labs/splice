// PORT-OF: daemon/control/.../api/diagnostics/McpRoutes.kt (SseWrite) — invariants unchanged: the one
// frame-writer role of the MCP host's stream. The console's /api/events shared it until Oct 12, 2026, when that
// stream, which nothing opened, was deleted.
package splice.http

/** Writes one SSE frame to the client. */
public fun interface SseWrite {
    public suspend operator fun invoke(frame: String)
}
