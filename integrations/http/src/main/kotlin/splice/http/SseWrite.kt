// PORT-OF: daemon/control/.../api/diagnostics/McpRoutes.kt (SseWrite) — invariants unchanged: the one
// frame-writer role two SSE surfaces share, the MCP host's stream and the console's /api/events, so it
// lives in the HTTP integration both already reach rather than inside either one.
package splice.http

/** Writes one SSE frame to the client. */
public fun interface SseWrite {
    public suspend operator fun invoke(frame: String)
}
