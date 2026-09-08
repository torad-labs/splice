// NEW: bounded child-JVM execution ships inside splice without an external JavaScript runtime.
package splice.app.codemode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import splice.spi.CodeModeCapacityException
import splice.spi.CodeModeCell
import splice.spi.CodeModeInfrastructureException
import splice.spi.CodeModeRuntime
import splice.spi.CodeModeTimeoutException
import splice.spi.ProcessDispatchers
import java.io.IOException
import java.lang.ProcessBuilder.Redirect
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

private const val DEFAULT_MAX_WORKERS: Int = 4
private const val DEFAULT_ADVANCE_TIMEOUT_MS: Long = 5_000
private const val WORKER_MAIN_CLASS: String = "splice.app.codemode.CodeModeWorker"

/** Runs one GraalJS cell in each bounded child JVM; it never executes client tools. */
public class JvmCodeModeRuntime(
    private val maxWorkers: Int = DEFAULT_MAX_WORKERS,
    private val advanceTimeoutMs: Long = DEFAULT_ADVANCE_TIMEOUT_MS,
    private val ioDispatcher: CoroutineDispatcher = ProcessDispatchers().io(),
    private val javaExecutable: String = Path.of(System.getProperty("java.home"), "bin", "java").toString(),
    private val workerClasspath: String = System.getProperty("java.class.path"),
) : CodeModeRuntime {
    private val closed: AtomicBoolean = AtomicBoolean()
    private val permits: Semaphore = Semaphore(maxWorkers)
    private val cells: MutableSet<JvmCodeModeCell> = ConcurrentHashMap.newKeySet()
    private val starting: MutableSet<WorkerChannel> = ConcurrentHashMap.newKeySet()

    init {
        require(maxWorkers > 0) { "Code-mode worker capacity must be positive" }
        require(advanceTimeoutMs > 0) { "Code-mode advance timeout must be positive" }
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
            val initial = CodeModeFrames.parseReply(channel.exchange(start), tools, 1)
            val cell = JvmCodeModeCell(channel, initial, tools, ReleaseCodeModeCell(::releaseCell))
            cells.add(cell)
            starting.remove(channel)
            if (closed.get()) cell.close()
            check(!closed.get()) { "Code-mode runtime is closed" }
            started = true
            return cell
        } catch (error: CancellationException) {
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
                        "-Xmx128m",
                        "-cp",
                        workerClasspath,
                        WORKER_MAIN_CLASS,
                    )
                    builder.environment().clear()
                    builder.redirectError(Redirect.DISCARD)
                    process = builder.start()
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

/** A spawned process owns capacity until its exit is observed, even if startup is cancelled. */
internal class WorkerPermit(private val permits: Semaphore) {
    private val released = AtomicBoolean()
    private val observed = AtomicBoolean()

    fun observe(process: Process) {
        observed.set(true)
        process.onExit().thenRun { releaseAfterExit() }
    }

    fun releaseIfUnstarted() {
        if (!observed.get()) releaseAfterExit()
    }

    fun releaseAfterExit() {
        if (released.compareAndSet(false, true)) permits.release()
    }
}
