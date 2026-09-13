// NEW (v0.4.0, FEATURES.md §8): the host's knobs, mirrored on code mode's worker pool — the one
// pool splice already runs — so the lifecycle story (idle reap, eviction at capacity) is the same
// story twice, not a new one.
package splice.control.mcp

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

public fun interface HostClock {
    public fun millis(): Long
}

public data class McpHostConfig(
    /** A server with no open notification stream and no request for this long is closed. */
    val idleTimeout: Duration = 30.minutes,
    /** Hosted processes at most; past it the longest-idle streamless server is evicted first. */
    val maxServers: Int = 32,
    /** How long one forwarded request may wait for the child's answer before it fails in words. */
    val requestTimeout: Duration = 30.minutes,
    /** Child initialize handshake budget — a server that cannot answer this is not hostable. */
    val initializeTimeout: Duration = 1.minutes,
    val clock: HostClock = HostClock(System::currentTimeMillis),
)
