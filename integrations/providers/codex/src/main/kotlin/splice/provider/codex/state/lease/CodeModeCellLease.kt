// NEW: parked-cell lease policy owns session-liveness evidence and its monotonic clock.
package splice.provider.codex.state.lease

import splice.core.util.ElapsedClock
import splice.provider.codex.CodeModeBridgeConfig
import java.util.concurrent.TimeUnit

/** Positive process-liveness evidence for a session. Null means unknown, including headless clients;
 *  absence from the interactive registry is never evidence that a session died. */
public fun interface CodeModeSessionAlive {
    public operator fun invoke(sessionId: String): Boolean?
}

/** Whether a live session sits at its prompt with no turn in flight, as Claude Code's own status says. Its client
 *  cannot be running the tool calls a parked cell waits on, so the cell's quiet is no longer evidence of work. */
public fun interface CodeModeSessionIdle {
    public operator fun invoke(sessionId: String): Boolean
}

/** How a parked cell is kept alive: session-liveness evidence and the monotonic clock its lease runs on. */
public data class CodeModeCellLease(
    /** Alive cells survive timer sweeps; capacity may reclaim any parked cell past its five-minute lease. */
    val sessionAlive: CodeModeSessionAlive = CodeModeSessionAlive { null },
    /** A live session idle at its prompt leases its parked cells as an unknown one does. Records are unaffected. */
    val sessionIdle: CodeModeSessionIdle = CodeModeSessionIdle { false },
    /** Monotonic lease time; persisted timestamps continue to use [CodeModeBridgeConfig.clock]. */
    val clock: ElapsedClock = ElapsedClock { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
)
