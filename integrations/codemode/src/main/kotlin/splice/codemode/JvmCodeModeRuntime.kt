// NEW: bounded child-JVM execution ships inside splice without an external JavaScript runtime.
package splice.codemode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.failure.CodeModeCapacityException
import splice.upstream.failure.CodeModeInfrastructureException
import splice.upstream.failure.CodeModeTimeoutException
import java.io.IOException
import java.lang.ProcessBuilder.Redirect
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

public const val DEFAULT_MAX_WORKERS: Int = 4
public const val DEFAULT_ADVANCE_TIMEOUT_MS: Long = 5_000

// why: a worker's start is a JVM boot and a JavaScript engine's set-up, which a loaded machine
// stretches to seconds (V4-226: a cell's whole first exchange took 1.1-1.3 s on an idle CI runner and
// 4.9-5.2 s under a parallel build); the bound only keeps a worker that never comes up from holding its
// slot, and the advance deadline no longer pays for the start
public const val DEFAULT_WORKER_START_TIMEOUT_MS: Long = 30_000

// why: one bounded GraalJS cell fits in 128MB and the smaller heap keeps each child's spawn and GC
// cheap
public const val DEFAULT_HEAP_MB: Int = 128

// why: a SIGKILLed worker is gone in milliseconds; the bound only keeps a cancel from hanging on a
// process stuck in the kernel, and past it the permit stays with the process's onExit observer
private const val CANCEL_REAP_WAIT_MS: Long = 2_000

// Spelled, not reflected (kt-no-reflection-in-production): every runtime test boots its worker
// through this name, so a stale one fails the suite rather than the daemon.
private const val WORKER_MAIN_CLASS: String = "splice.codemode.CodeModeWorker"

/** Starts one worker process from its builder: the runtime's one step across the OS boundary, named
 *  so a test can hold what the JDK reports about the process it spawned (its onExit callbacks). */
public fun interface WorkerSpawn {
    public operator fun invoke(builder: ProcessBuilder): Process
}

/** Runs one GraalJS cell in each bounded child JVM; it never executes client tools. */
public class JvmCodeModeRuntime(
    private val maxWorkers: Int = DEFAULT_MAX_WORKERS,
    private val advanceTimeoutMs: Long = DEFAULT_ADVANCE_TIMEOUT_MS,
    private val heapMb: Int = DEFAULT_HEAP_MB,
    private val ioDispatcher: CoroutineDispatcher = ProcessDispatchers().io(),
    private val javaExecutable: String = Path.of(System.getProperty("java.home"), "bin", "java").toString(),
    private val workerClasspath: String = System.getProperty("java.class.path"),
    private val spawn: WorkerSpawn = WorkerSpawn(ProcessBuilder::start),
    private val workerStartTimeoutMs: Long = DEFAULT_WORKER_START_TIMEOUT_MS,
) : CodeModeRuntime {
    private val closed: AtomicBoolean = AtomicBoolean()
    private val permits: Semaphore = Semaphore(maxWorkers)
    private val cells: MutableSet<JvmCodeModeCell> = ConcurrentHashMap.newKeySet()
    private val starting: MutableSet<WorkerChannel> = ConcurrentHashMap.newKeySet()

    init {
        require(maxWorkers > 0) { "Code-mode worker capacity must be positive" }
        require(advanceTimeoutMs > 0) { "Code-mode advance timeout must be positive" }
        require(workerStartTimeoutMs > 0) { "Code-mode worker start timeout must be positive" }
        require(heapMb > 0) { "Code-mode worker heap must be positive" }
        require(workerClasspath.isNotBlank()) { "Code-mode worker classpath is required" }
    }

    override suspend fun start(source: String, tools: Set<String>): CodeModeCell {
        check(!closed.get()) { "Code-mode runtime is closed" }
        val start = CodeModeWire.startFrame(source, tools)
        if (!permits.tryAcquire()) throw CodeModeCapacityException()
        var channel: WorkerChannel? = null
        var started = false
        val permit = WorkerPermit(permits)
        try {
            channel = startWorker(permit)
            channel.awaitReady(workerStartTimeoutMs)
            val initial = CodeModeFrames.parseReply(channel.exchange(start), tools, 1)
            val cell = JvmCodeModeCell(channel, initial, tools, ReleaseCodeModeCell(::releaseCell))
            cells.add(cell)
            starting.remove(channel)
            if (closed.get()) cell.close()
            check(!closed.get()) { "Code-mode runtime is closed" }
            started = true
            return cell
        } catch (error: CancellationException) {
            // V4-214: the channel is closed by now (exchange's cancellation handler, or startWorker's
            // finally). The reap waits for the exit off the caller's dispatcher and cannot itself be
            // cancelled, so when this start() finishes its permit is back.
            withContext(NonCancellable + ioDispatcher) { permit.reapAfterCancel(CANCEL_REAP_WAIT_MS) }
            throw error
        } catch (error: CodeModeTimeoutException) {
            throw error
        } catch (error: CodeModeInfrastructureException) {
            throw error
        } catch (error: IOException) {
            throw IOException("Code-mode worker failed to start", error)
        } catch (error: IllegalArgumentException) {
            throw IOException("Code-mode worker failed to start", error)
        } finally {
            if (!started) {
                channel?.close()
                permit.releaseIfUnstarted()
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            // Concurrent registries may shrink between a snapshot's size check and iteration.
            cells.forEach(JvmCodeModeCell::close)
            starting.forEach(WorkerChannel::close)
        }
    }

    private suspend fun startWorker(permit: WorkerPermit): WorkerChannel {
        var process: Process? = null
        var channel: WorkerChannel? = null
        var handedOff = false
        try {
            val worker = withContext(ioDispatcher) {
                runInterruptible {
                    val builder = ProcessBuilder(
                        javaExecutable,
                        "-Xmx${heapMb}m",
                        "-cp",
                        workerClasspath,
                        WORKER_MAIN_CLASS,
                    )
                    builder.environment().clear()
                    builder.redirectError(Redirect.DISCARD)
                    process = spawn(builder)
                    permit.observe(checkNotNull(process))
                    WorkerChannel(
                        process = checkNotNull(process),
                        ioDispatcher = ioDispatcher,
                        timeoutMs = advanceTimeoutMs,
                        onExit = permit::releaseAfterExit,
                    ).also { startedChannel ->
                        channel = startedChannel
                        starting.add(startedChannel)
                        startedChannel.afterExit(WorkerExited { starting.remove(startedChannel) })
                        if (closed.get()) startedChannel.close()
                    }
                }
            }
            handedOff = true
            return worker
        } finally {
            if (!handedOff) {
                channel?.close() ?: process?.let(::destroyStartedProcess)
            }
        }
    }

    private fun destroyStartedProcess(process: Process) {
        try {
            process.destroyForcibly()
        } catch (error: CancellationException) {
            throw error
        } catch (_: RuntimeException) {
            // Startup is already failing; the process onExit observer retains capacity ownership.
        }
    }

    private fun releaseCell(cell: JvmCodeModeCell) {
        cells.remove(cell)
    }
}
