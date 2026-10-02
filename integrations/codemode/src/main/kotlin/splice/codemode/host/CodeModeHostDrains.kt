// NEW: an unconfirmed control close quarantines admission and replaces a host only after its retained cells drain.
package splice.codemode.host

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import splice.codemode.SharedWorkerChannel
import splice.core.util.LogSink
import splice.upstream.failure.CodeModeCapacityException
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class CodeModeHostDrains(
    private val scope: CoroutineScope,
    private val lock: ReentrantLock,
    private val admission: CodeModePoolAdmission,
    private val log: LogSink,
) {
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
