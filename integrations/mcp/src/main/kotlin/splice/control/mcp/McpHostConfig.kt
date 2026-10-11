// NEW: v0.4.0 FEATURES.md §8 — the host's knobs, mirrored on code mode's worker pool — the one
// pool splice already runs — so the lifecycle story (idle reap, eviction at capacity) is the same
// story twice, not a new one.
package splice.control.mcp

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

public fun interface HostClock {
    public fun millis(): Long
}

/** How long one forwarded request may wait for a hosted child's answer, asked for at every forwarded request (knob
 *  `mcpRequestTimeoutMs`) so a raised budget governs the next one without a daemon restart. A request already
 *  waiting keeps the budget it started under: it is what its own deadline was set from. */
public fun interface McpRequestBudget {
    public operator fun invoke(): Duration
}

public data class McpHostConfig(
    /** A server with no open notification stream and no request for this long is closed. */
    val idleTimeout: Duration = 30.minutes,
    /** Hosted processes at most; past it the longest-idle streamless server is evicted first. */
    val maxServers: Int = 32,
    /** How long one forwarded request may wait for the child's answer before it fails in words. The only one of
     *  these four read LIVE, per request (see [McpRequestBudget]); the other three are read where this host is
     *  built and stay restartRequired, which is what their knobs say. */
    val requestTimeout: McpRequestBudget = McpRequestBudget { 30.minutes },
    /** Child initialize handshake budget — a server that cannot answer this is not hostable. */
    val initializeTimeout: Duration = 1.minutes,
    val clock: HostClock = HostClock(System::currentTimeMillis),
    /** V4-176: the slice hosted children are spawned into, by name. splice never creates it — it
     *  asks systemd for the CEILING on it at every spawn and says so in the log when there is none. */
    val slice: String = APP_MCP_SLICE,
)
