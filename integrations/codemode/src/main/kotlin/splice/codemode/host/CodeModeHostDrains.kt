// NEW: an unconfirmed control close quarantines admission and replaces a host only after its retained cells drain.
package splice.codemode.host

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import splice.codemode.SharedWorkerChannel
import splice.core.util.LogSink
import splice.upstream.failure.CodeModeCapacityException
import splice.upstream.failure.CodeModeWorkerLostException
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class CodeModeHostDrains(
    private val scope: CoroutineScope,
    private val lock: ReentrantLock,
    private val admission: CodeModePoolAdmission,
    private val log: LogSink,
) {
    /** Caller cancellation abandons a wait, never the independently timed engine exchange on this pool scope. */
    suspend fun admit(session: CodeModePoolSession, channel: SharedWorkerChannel, metrics: CodeModeHostMetrics) {
        val opening = lock.withLock {
            requireCurrent(session, channel)
            session.opening ?: scope.async(start = CoroutineStart.LAZY) {
                exchange(session, channel, metrics)
                lock.withLock {
                    requireCurrent(session, channel)
                    session.initialized = true
                }
            }.also { session.opening = it }
        }
        try {
            opening.await()
        } catch (error: CodeModeCapacityException) {
            session.opening = null
            throw error
        }
    }

    /** Both the opening future and its result belong to this generation under the placement lock. */
    private fun requireCurrent(session: CodeModePoolSession, channel: SharedWorkerChannel) {
        if (session.closing || !session.host.owns(channel)) throw CodeModeWorkerLostException()
    }

    private suspend fun exchange(
        session: CodeModePoolSession,
        channel: SharedWorkerChannel,
        metrics: CodeModeHostMetrics,
    ) {
        val failure = try {
            metrics.admit(session, channel, admission)
            return
        } catch (error: CodeModeCapacityException) {
            error
        } catch (error: IOException) {
            failed(session.host, channel)
            error
        }
        throw failure
    }

    fun failed(host: CodeModePoolHost, channel: SharedWorkerChannel): CodeModeCapacityException {
        val changed = lock.withLock {
            if (!host.owns(channel)) return ended()
            val changed = !host.draining
            host.draining = true
            changed
        }
        if (changed) log("[code-mode] host control failed; marked for replacement after retained cells drain")
        closeWhenDrained(host)
        return capacity()
    }

    private fun ended(): CodeModeCapacityException = CodeModeCapacityException(
        "${admission.capacity().message}; failed host generation has already ended",
    )

    fun capacity(): CodeModeCapacityException = CodeModeCapacityException(
        "${admission.capacity().message}; host marked for replacement while retained cells drain",
    )

    private fun drained(host: CodeModePoolHost): Boolean =
        host.draining && host.sessions.none { !it.closing && it.users > 0 }

    fun closeWhenDrained(host: CodeModePoolHost) {
        scope.launch {
            val boot = lock.withLock {
                if (drained(host)) host.boot else null
            } ?: return@launch
            try {
                val channel = boot.await()
                if (lock.withLock { drained(host) }) {
                    log("[code-mode] drained host retired; the next admitted cell boots its replacement")
                    channel.close()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: IOException) {
                // Host death confirms that no native engine remains.
            }
        }
    }
}
