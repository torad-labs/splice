// NEW: v0.4.0 FEATURES.md §8 — all signalled MCP children share one shutdown grace budget.
package splice.control.mcp

import java.util.concurrent.TimeUnit

private const val DESTROY_GRACE_MS = 2_000L

internal class McpProcessStop {
    /** Callers signal every child first. Waiting on N stubborn children still costs one grace. */
    fun await(processes: List<Process>) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DESTROY_GRACE_MS)
        processes.forEach { process ->
            try {
                val left = (deadline - System.nanoTime()).coerceAtLeast(0L)
                if (!process.waitFor(left, TimeUnit.NANOSECONDS)) process.destroyForcibly()
            } catch (_: InterruptedException) {
                process.destroyForcibly()
                Thread.currentThread().interrupt()
            }
        }
    }
}
