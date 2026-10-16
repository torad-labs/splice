// NEW: one process-launch boundary owns classpath pins, child cleanup and pre-dispatch readiness.
package splice.codemode.host

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import splice.codemode.SharedWorkerChannel
import splice.codemode.WorkerArtifacts
import splice.upstream.LifecycleScope
import splice.upstream.failure.CodeModeTimeoutException
import java.lang.ProcessBuilder.Redirect
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val WORKER_MAIN_CLASS: String = "splice.codemode.CodeModeWorker"

// why: without an initial heap the JVM takes 1/64 of the machine's memory up to -Xmx, so every host committed its
// whole 256 MiB at boot and its young generation filled with relay garbage: 166 MiB of each host's 400 MiB on Oct 15
// was its own heap, about 9 MiB of it live. A small start grows on demand up to -Xmx; the maximum, which sizes the
// guest heap, is unchanged. It never exceeds the maximum, which the JVM refuses.
private const val WORKER_INITIAL_HEAP_MB: Int = 16

// why: G1 explicitly, so the maximum heap the guest heap is sized from never shifts with collector ergonomics; and a
// periodic collection on an idle host gives the memory a burst used back to the system instead of holding it.
private const val WORKER_COLLECTOR: String = "-XX:+UseG1GC"
private const val WORKER_IDLE_COLLECTION: String = "-XX:G1PeriodicGCInterval=30000"

internal class CodeModeHostLauncher(
    private val options: HostLaunch,
    private val scope: LifecycleScope,
    private val ioDispatcher: CoroutineDispatcher,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val pinnedWorkerClasspath = WorkerArtifacts.pinClasspath(options.classpath)
    private val channels: MutableSet<SharedWorkerChannel> = ConcurrentHashMap.newKeySet()

    suspend fun open(): SharedWorkerChannel {
        val host = runInterruptible {
            val builder = ProcessBuilder(
                options.javaExecutable,
                "-Xms${minOf(WORKER_INITIAL_HEAP_MB, options.heapMb)}m",
                "-Xmx${options.heapMb}m",
                WORKER_COLLECTOR,
                WORKER_IDLE_COLLECTION,
                "-cp",
                pinnedWorkerClasspath,
                WORKER_MAIN_CLASS,
                "host",
            )
            builder.environment().clear()
            builder.redirectError(Redirect.DISCARD)
            SharedWorkerChannel(options.spawn(builder), scope, ioDispatcher).also {
                channels.add(it)
                it.afterExit { channels.remove(it) }
                if (closed.get()) it.close()
            }
        }
        var initialized = false
        try {
            withTimeoutOrNull(options.startTimeoutMs) {
                host.awaitReady()
                true
            } ?: throw CodeModeTimeoutException(options.startTimeoutMs)
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
