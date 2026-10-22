// NEW: v0.4.0 FEATURES.md §8 — all signalled MCP children share one shutdown grace budget.
package splice.control.mcp

import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val DESTROY_GRACE_MS = 2_000L

private data class McpProcessTree(
    val root: ProcessHandle,
    val descendants: List<ProcessHandle>,
)

private data class McpStopWait(
    val exited: Boolean,
    val interrupted: Boolean,
)

internal class McpProcessStop {
    /** Captures the whole tree before the parent can escape and leave an inherited stdin pipe behind. */
    fun stop(processes: List<Process>) {
        val trees = processes.map(::capture)
        trees.forEach(::signal)
        awaitTrees(trees)
    }

    /** Callers that already signalled every child wait one shared grace. */
    fun await(processes: List<Process>) {
        awaitTrees(processes.map(::capture))
    }

    private fun capture(process: Process): McpProcessTree {
        val root = process.toHandle()
        return McpProcessTree(root, root.descendants().toList())
    }

    private fun signal(tree: McpProcessTree) {
        (tree.descendants.asReversed() + tree.root).forEach(ProcessHandle::destroy)
    }

    private fun awaitTrees(trees: List<McpProcessTree>) {
        val handles = trees.flatMap { it.descendants.asReversed() + it.root }.distinct()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DESTROY_GRACE_MS)
        val waits = handles.map { handle -> waitFor(handle, deadline) }
        val overdue = handles.zip(waits).filterNot { (_, wait) -> wait.exited }.map { (handle) -> handle }
        overdue.filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly)
        val interrupted = waits.any { it.interrupted } || overdue.map(::reap).any { it }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun waitFor(process: ProcessHandle, deadline: Long): McpStopWait = try {
        val left = (deadline - System.nanoTime()).coerceAtLeast(0L)
        process.onExit().get(left, TimeUnit.NANOSECONDS)
        McpStopWait(exited = true, interrupted = false)
    } catch (_: InterruptedException) {
        McpStopWait(exited = false, interrupted = true)
    } catch (_: TimeoutException) {
        McpStopWait(exited = false, interrupted = false)
    }

    private fun reap(process: ProcessHandle): Boolean {
        var interrupted = false
        while (true) {
            try {
                process.onExit().get()
                return interrupted
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
    }
}
