// NEW: one process-launch boundary owns classpath pins, child cleanup and pre-dispatch readiness.
package splice.codemode.host

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import splice.codemode.SharedWorkerChannel
import splice.codemode.WorkerArtifacts
import splice.codemode.WorkerSpawn
import splice.upstream.LifecycleScope
import splice.upstream.failure.CodeModeTimeoutException
import java.lang.ProcessBuilder.Redirect
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val WORKER_MAIN_CLASS: String = "splice.codemode.CodeModeWorker"

internal class CodeModeHostLauncher(
    workerClasspath: String,
    private val javaExecutable: String,
    private val heapMb: Int,
    private val spawn: WorkerSpawn,
    private val scope: LifecycleScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val timeoutMs: Long,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val pinnedWorkerClasspath = WorkerArtifacts.pinClasspath(workerClasspath)
    private val channels: MutableSet<SharedWorkerChannel> = ConcurrentHashMap.newKeySet()

    suspend fun open(): SharedWorkerChannel {
        val host = runInterruptible {
            val builder = ProcessBuilder(
                javaExecutable,
                "-Xmx${heapMb}m",
                "-cp",
                pinnedWorkerClasspath,
                WORKER_MAIN_CLASS,
                "host",
            )
            builder.environment().clear()
            builder.redirectError(Redirect.DISCARD)
            SharedWorkerChannel(spawn(builder), scope, ioDispatcher).also {
                channels.add(it)
                it.afterExit { channels.remove(it) }
                if (closed.get()) it.close()
            }
        }
        var initialized = false
        try {
            withTimeoutOrNull(timeoutMs) {
                host.awaitReady()
                true
            } ?: throw CodeModeTimeoutException(timeoutMs)
            initialized = true
            return host
        } finally {
            if (!initialized) retire(host)
        }
    }

    /** Confirm native process death before replacement; old stream cleanup cannot hold the boot result. */
    private suspend fun retire(host: SharedWorkerChannel) {
        val exited = CompletableDeferred<Unit>()
        host.afterExit { exited.complete(Unit) }
        scope.launch { runInterruptible(ioDispatcher) { host.close() } }
        exited.await()
    }

    override fun close() {
        closed.set(true)
        channels.forEach(SharedWorkerChannel::close)
    }
}
