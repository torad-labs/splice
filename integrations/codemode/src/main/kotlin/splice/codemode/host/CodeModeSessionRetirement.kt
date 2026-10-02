// NEW: retirement keeps an engine's reservation until its acknowledged close or its host's death.
package splice.codemode.host

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import splice.codemode.HostProtocol
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock as locked

internal class CodeModeSessionRetirement(
    private val scope: CoroutineScope,
    private val lock: ReentrantLock,
    private val metrics: CodeModeHostMetrics,
) {
    fun close(session: CodeModePoolSession) {
        scope.launch {
            try {
                session.gate.withLock {
                    val host = lock.locked { session.host.boot }?.await()
                    if (host != null && !host.isClosed) {
                        try {
                            metrics.count(host.exchange(session.id, HostProtocol.command("session-close"), session.id))
                        } catch (error: IOException) {
                            host.close()
                            throw error
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: IOException) {
                // A dead host has no retained isolate.
            } finally {
                lock.locked { session.host.sessions.remove(session) }
                session.retired.complete(Unit)
            }
        }
    }
}
