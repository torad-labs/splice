// NEW: prewarmed multiplexed child-JVM execution keeps parked scripts as isolated contexts, not processes.
package splice.codemode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import splice.core.util.Cancellables
import splice.upstream.LifecycleScope
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.failure.CodeModeStartException
import splice.upstream.failure.CodeModeTimeoutException
import splice.upstream.failure.CodeModeWorkerLostException
import java.io.IOException
import java.lang.ProcessBuilder.Redirect
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// Retained constructor compatibility: this never bounds the number of script contexts.
public const val DEFAULT_MAX_WORKERS: Int = 4
public const val DEFAULT_ADVANCE_TIMEOUT_MS: Long = 5_000

// why: JVM and native-isolate warm-up get a separate boot budget, never a script execution deadline.
public const val DEFAULT_WORKER_START_TIMEOUT_MS: Long = 30_000
public const val DEFAULT_HEAP_MB: Int = 512

private const val WORKER_MAIN_CLASS: String = "splice.codemode.CodeModeWorker"

/** Starts the shared host at the OS boundary; injectable for ownership and boot measurements. */
public fun interface WorkerSpawn {
    public operator fun invoke(builder: ProcessBuilder): Process
}

/** Runs independent GraalJS contexts on one prewarmed engine. A parked context holds no permit or thread. */
public class JvmCodeModeRuntime(
    maxWorkers: Int = DEFAULT_MAX_WORKERS,
    advanceTimeoutMs: Long = DEFAULT_ADVANCE_TIMEOUT_MS,
    private val heapMb: Int = DEFAULT_HEAP_MB,
    private val ioDispatcher: CoroutineDispatcher = ProcessDispatchers().io(),
    private val javaExecutable: String = Path.of(System.getProperty("java.home"), "bin", "java").toString(),
    workerClasspath: String = WorkerArtifacts.runningClasspath(),
    private val spawn: WorkerSpawn = WorkerSpawn(ProcessBuilder::start),
    private val workerStartTimeoutMs: Long = DEFAULT_WORKER_START_TIMEOUT_MS,
) : CodeModeRuntime {
    private val closed = AtomicBoolean()
    private val cells: MutableSet<JvmCodeModeCell> = ConcurrentHashMap.newKeySet()
    private val sequence = AtomicLong()
    private val scope = LifecycleScope(ioDispatcher)

    @Volatile private var channel: SharedWorkerChannel? = null

    init {
        require(maxWorkers > 0) { "Code-mode execution hint must be positive" }
        require(advanceTimeoutMs > 0) { "Code-mode advance hint must be positive" }
        require(workerStartTimeoutMs > 0) { "Code-mode worker start timeout must be positive" }
        require(heapMb > 0) { "Code-mode host heap must be positive" }
        require(workerClasspath.isNotBlank()) { "Code-mode worker classpath is required" }
    }

    private val pinnedWorkerClasspath = WorkerArtifacts.pinClasspath(workerClasspath)
    private val boots = Mutex()
    private var boot: Deferred<SharedWorkerChannel> = prewarm()

    private fun prewarm(): Deferred<SharedWorkerChannel> = scope.async {
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
            val process = spawn(builder)
            SharedWorkerChannel(process, scope, ioDispatcher).also {
                channel = it
                if (closed.get()) it.close()
            }
        }
        var initialized = false
        try {
            withTimeoutOrNull(workerStartTimeoutMs) {
                host.awaitReady()
                true
            }
                ?: throw CodeModeTimeoutException(workerStartTimeoutMs)
            initialized = true
            host
        } finally {
            if (!initialized) host.close()
        }
    }

    override suspend fun start(source: String, tools: Set<String>, descriptions: Map<String, String>): CodeModeCell {
        val frame = CodeModeWire.startFrame(source, tools, descriptions)
        val pipe = openCell()
        var started = false
        try {
            val initial = CodeModeFrames.parseReply(pipe.exchange(frame), tools, 1)
            val cell = JvmCodeModeCell(pipe, initial, tools, ReleaseCodeModeCell(::releaseCell))
            cells.add(cell)
            if (closed.get()) {
                cell.stop()
                throw CodeModeWorkerLostException()
            }
            started = true
            return cell
        } finally {
            if (!started) pipe.close()
        }
    }

    // This boundary ends before exchange: only these failures prove that source was never dispatched.
    private suspend fun openCell(): CellChannel = Cancellables.runCatchingBestEffort {
        check(!closed.get()) { "Code-mode runtime is closed" }
        val current = boots.withLock {
            if (boot.isCompleted) {
                if (boot.isCancelled || channel?.isClosed == true) boot = prewarm()
            }
            boot
        }
        val host = awaitHost(current)
        if (closed.get()) throw CodeModeWorkerLostException()
        host.cell(sequence.incrementAndGet())
    }.getOrElse { error ->
        when (error) {
            is IllegalArgumentException -> throw error
            is IOException -> throw CodeModeStartException(error)
            is RuntimeException -> throw CodeModeStartException(error)
            else -> throw error
        }
    }

    private suspend fun awaitHost(current: Deferred<SharedWorkerChannel>): SharedWorkerChannel = try {
        current.await()
    } catch (error: CancellationException) {
        // A stopped runtime owns the cancelled boot, not the still-live caller's turn.
        currentCoroutineContext().ensureActive()
        if (closed.get()) throw CodeModeWorkerLostException(error)
        throw error
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                cells.forEach(JvmCodeModeCell::stop)
                channel?.close()
            } finally {
                scope.cancel()
            }
        }
    }

    private fun releaseCell(cell: JvmCodeModeCell) {
        cells.remove(cell)
    }
}
