// NEW: v0.4.0 FEATURES.md §8 — all signalled MCP children share one shutdown grace budget.
package splice.control.mcp

import java.util.concurrent.TimeUnit

private const val DESTROY_GRACE_MS = 2_000L

internal class McpProcessStop {
    /** Callers signal every child first. Waiting on N stubborn children still costs one grace. */
    fun await(processes: List<Process>) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DESTROY_GRACE_MS)
        val interrupted = processes.map { process -> await(process, deadline) }.any { it }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun await(process: Process, deadline: Long): Boolean {
        var interrupted = false
        val exited = try {
            val left = (deadline - System.nanoTime()).coerceAtLeast(0L)
            process.waitFor(left, TimeUnit.NANOSECONDS)
        } catch (_: InterruptedException) {
            interrupted = true
            false
        }
        if (!exited) {
            process.destroyForcibly()
            interrupted = reap(process) || interrupted
        }
        return interrupted
    }

    private fun reap(process: Process): Boolean {
        var interrupted = false
        while (true) {
            try {
                process.waitFor()
                return interrupted
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
    }
}
