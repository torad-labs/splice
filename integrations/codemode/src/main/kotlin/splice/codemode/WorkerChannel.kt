// NEW: shared-host transport owns process cleanup independently of individual script contexts.
package splice.codemode

import kotlinx.coroutines.CancellationException
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val CLOSE_WAIT_MS: Long = 250

internal fun interface WorkerExited {
    operator fun invoke()
}

internal fun interface CodeModeCleanup {
    operator fun invoke()
}

/** Owns the shared host's streams and observes exit even when stream cleanup fails. */
internal class WorkerChannel(
    private val process: Process,
    val input: DataInputStream = DataInputStream(process.inputStream.buffered()),
    val output: DataOutputStream = DataOutputStream(process.outputStream.buffered()),
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val exited = CompletableFuture<Unit>()

    init {
        process.onExit().thenRun { exited.complete(Unit) }
    }

    fun afterExit(action: WorkerExited) {
        exited.thenRun { action() }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            var cancellation: CancellationException? = null
            cancellation = cleanup(cancellation, CodeModeCleanup(::destroyProcess))
            cancellation = cleanup(cancellation, CodeModeCleanup { closeStream(output) })
            cancellation = cleanup(cancellation, CodeModeCleanup { closeStream(input) })
            cancellation = cleanup(cancellation, CodeModeCleanup(::observeExitAfterWait))
            cancellation?.let { throw it }
        }
    }

    /** Cancellation callbacks must not block on the reaper or throw on its undefined thread. */
    internal fun closeQuietly() {
        if (closed.compareAndSet(false, true)) {
            cleanup(null, CodeModeCleanup(::destroyProcess))
            cleanup(null, CodeModeCleanup { closeStream(output) })
            cleanup(null, CodeModeCleanup { closeStream(input) })
        }
    }

    private fun cleanup(current: CancellationException?, action: CodeModeCleanup): CancellationException? = try {
        action()
        current
    } catch (error: CancellationException) {
        current ?: error
    }

    private fun destroyProcess() {
        try {
            process.destroyForcibly()
        } catch (error: CancellationException) {
            throw error
        } catch (_: RuntimeException) {
            // A failed destroy must not prevent cleanup of the remaining streams.
        }
    }

    private fun closeStream(stream: Closeable) {
        try {
            stream.close()
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            // Cleanup continues; the process exit observer is the lifetime evidence.
        } catch (_: RuntimeException) {
            // Custom streams may throw unchecked; the other stream still needs closing.
        }
    }

    private fun observeExitAfterWait() {
        if (waitForExit()) exited.complete(Unit)
    }

    private fun waitForExit(): Boolean = try {
        process.waitFor(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    } catch (error: CancellationException) {
        throw error
    } catch (_: RuntimeException) {
        // A failed wait is not evidence of exit; onExit retains ownership.
        false
    }
}
