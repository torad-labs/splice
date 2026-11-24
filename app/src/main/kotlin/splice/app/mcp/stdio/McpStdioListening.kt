// NEW: desktop-3p-parity (Nov 23, 2026). What the stdio MCP bridge hears from a server on its own (notifications, the
// server's own requests), split from McpStdioBridge.kt: each stream is launched in the bridge's scope, so reading stdin
// never waits on it, and every one is stopped when the bridge ends, however it ends.
package splice.app.mcp.stdio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

internal class McpStdioListening(
    @PublishedApi internal val scope: CoroutineScope,
) {
    @PublishedApi
    internal val jobs: ConcurrentLinkedQueue<Job> = ConcurrentLinkedQueue()

    /** [step] heard in the bridge's scope until it ends or [stop] is called. */
    inline fun start(crossinline step: suspend () -> Unit) {
        jobs += scope.launch { step() }
    }

    /** Every stream stopped, and waited for. */
    suspend fun stop() {
        jobs.forEach { it.cancelAndJoin() }
    }
}
