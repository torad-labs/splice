// NEW: retirement keeps its reservation on an unconfirmed close and drains that host without discarding siblings.
package splice.codemode.host

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import splice.codemode.HostProtocol
import splice.codemode.SharedWorkerChannel
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock as locked

internal class CodeModeSessionRetirement(
    private val scope: CoroutineScope,
    private val lock: ReentrantLock,
    private val metrics: CodeModeHostMetrics,
    private val drains: CodeModeHostDrains,
    private val timeoutMs: Long,
) {
    fun close(session: CodeModePoolSession) {
        scope.launch {
            var confirmed = false
            var channel: SharedWorkerChannel? = null
            try {
                session.gate.withLock {
                    val host = lock.locked { session.host.boot }?.await()
                    channel = host
                    if (host != null && !host.isClosed) {
                        val reply = host.control(
                            session.id,
                            HostProtocol.command("session-close"),
                            session.id,
                            timeoutMs,
                        )
                        metrics.count(reply)
                    }
                    confirmed = true
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: IOException) {
                val refusal = channel?.let { drains.failed(session.host, it) } ?: drains.capacity()
                session.retired.completeExceptionally(refusal)
            } finally {
                if (confirmed) {
                    lock.locked { session.host.sessions.remove(session) }
                    session.retired.complete(Unit)
                }
                drains.closeWhenDrained(session.host)
            }
        }
    }
}
