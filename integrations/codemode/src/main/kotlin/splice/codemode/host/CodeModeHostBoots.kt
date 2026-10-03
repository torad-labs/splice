// NEW: a failed host generation is observed without starting its replacement or rerunning source.
package splice.codemode.host

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import splice.codemode.SharedWorkerChannel
import splice.core.util.LogSink
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class CodeModeHostBoots(
    private val scope: CoroutineScope,
    private val start: CodeModeHostStart,
    private val lock: ReentrantLock,
    private val log: LogSink = LogSink {},
) {
    /** Called under the placement lock; opening a cell is the only action that boots a host. */
    fun open(host: CodeModePoolHost): Deferred<SharedWorkerChannel> {
        host.boot?.let {
            if (reusable(it)) return it
            lost(host, it)
        }
        val boot = scope.async(start = CoroutineStart.LAZY) { start.open() }
        host.boot = boot
        scope.launch {
            try {
                boot.await().afterExit { lost(host, boot) }
            } catch (error: CancellationException) {
                lost(host, boot)
                throw error
            } catch (_: IOException) {
                lost(host, boot)
            } catch (_: RuntimeException) {
                lost(host, boot)
            }
        }
        return boot
    }

    /** Completion is immutable: inspect a cached result without awaiting under the placement lock. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun reusable(boot: Deferred<SharedWorkerChannel>): Boolean = if (boot.isCompleted) {
        boot.getCompletionExceptionOrNull() == null && !boot.getCompleted().isClosed
    } else {
        !boot.isCancelled
    }

    private fun lost(host: CodeModePoolHost, boot: Deferred<SharedWorkerChannel>) {
        lock.withLock {
            if (host.boot !== boot) return
            host.boot = null
            host.draining = false
            host.sessions.filter { it.closing }.forEach {
                host.sessions.remove(it)
                it.retired.complete(Unit)
            }
            host.sessions.forEach {
                it.initialized = false
                it.opening = null
            }
        }
        log("[code-mode] host generation ended; placement cleared")
    }
}
