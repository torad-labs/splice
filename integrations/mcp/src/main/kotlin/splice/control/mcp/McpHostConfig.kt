// NEW: v0.4.0 FEATURES.md §8 — the host's knobs, mirrored on code mode's worker pool — the one
// pool splice already runs — so the lifecycle story (idle reap, eviction at capacity) is the same
// story twice, not a new one.
// Oct 10, 2026: all four lifecycle VALUES are now readers, not numbers, because each already had an
// enforcement point of its own — the idle sweep, spawn admission, a child's handshake, a forwarded
// request — and was reading the number this record was built with. The clock and the slice stay
// plain: one is an injected seam, the other a name read once per spawn and never compared.
// Consequence worth knowing before anyone compares two of these: four of the six properties are now
// lambdas, so the generated equals is identity on them. Nothing in the tree compares or copies an
// McpHostConfig today, which is why `data` is still here; the first code that needs either must give
// this record real equality rather than assume it has some.
package splice.control.mcp

import kotlinx.coroutines.CoroutineDispatcher
import splice.core.config.Knob
import splice.core.mcp.McpToolResultStore
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

public fun interface HostClock {
    public fun millis(): Long
}

/** How long one forwarded request may wait for a hosted child's answer, asked for at every forwarded request (knob
 *  `mcpRequestTimeoutMs`) so a raised budget governs the next one without a daemon restart. A request already
 *  waiting keeps the budget it started under: it is what its own deadline was set from. */
public fun interface McpRequestBudget {
    public operator fun invoke(): Duration
}

/** How long a hosted server with no open notification stream and no request is kept, asked for at every idle sweep
 *  (knob `mcpIdleTimeoutMs`). Shortening it reaps on the NEXT sweep rather than at the next restart, and nothing
 *  already reaped comes back. */
public fun interface McpIdleBudget {
    public operator fun invoke(): Duration
}

/** How long a starting child may take to answer the initialize handshake, asked for at every start (knob
 *  `mcpInitializeTimeoutMs`). A handshake already running keeps the budget it began under. */
public fun interface McpHandshakeBudget {
    public operator fun invoke(): Duration
}

/** How many child PROCESSES this host may own at once, asked for at every spawn attempt (knob `mcpMaxServers`).
 *  Read ONCE per attempt and carried into the eviction path, so the ceiling named in a refusal is the ceiling that
 *  refused: two readings could straddle an operator's raise and print a number that was never applied. */
public fun interface McpServerCeiling {
    public operator fun invoke(): Int
}

public data class McpHostConfig(
    /** A server with no open notification stream and no request for this long is closed (see [McpIdleBudget]). */
    val idleTimeout: McpIdleBudget = McpIdleBudget { 30.minutes },
    /** Hosted processes at most; past it the longest-idle streamless server is evicted first
     *  (see [McpServerCeiling]). */
    val maxServers: McpServerCeiling = McpServerCeiling { Knob.MCP_MAX_SERVERS.count().toInt() },
    /** How long one forwarded request may wait for the child's answer before it fails in words
     *  (see [McpRequestBudget]). */
    val requestTimeout: McpRequestBudget = McpRequestBudget { 30.minutes },
    /** Child initialize handshake budget — a server that cannot answer this is not hostable
     *  (see [McpHandshakeBudget]). */
    val initializeTimeout: McpHandshakeBudget = McpHandshakeBudget { 1.minutes },
    /** How long Settings' Test waits for a started server to list its tools. The start itself is bounded by
     *  [initializeTimeout]. This is its own bound, apart from [requestTimeout], which is for real tool calls: a
     *  server that cannot list its tools in this long is the hung one the person is looking for. */
    val testListTimeout: Duration = 10.seconds,
    val runtime: Runtime = Runtime(),
) {
    public val clock: HostClock get() = runtime.clock
    public val slice: String get() = runtime.slice

    /** Process resources are explicit so fixtures never publish receipts into the operator's state. */
    public data class Runtime(
        val clock: HostClock = HostClock(System::currentTimeMillis),
        /** The existing systemd slice name, checked at every hosted spawn. */
        val slice: String = APP_MCP_SLICE,
        val capture: Capture? = null,
    )

    public data class Capture(val store: McpToolResultStore, val io: CoroutineDispatcher)
}
