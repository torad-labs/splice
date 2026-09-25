// NEW: cancellable framed worker I/O retains capacity until process exit is observed.
package splice.codemode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import splice.upstream.failure.CodeModeTimeoutException
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val CLOSE_WAIT_MS: Long = 250

internal fun interface WorkerExited {
    operator fun invoke()
}

internal fun interface CodeModeCleanup {
    operator fun invoke()
}

/** The blocking framed I/O one deadline covers: a whole round trip for an exchange, the ready frame
 *  alone for a start (V4-226). */
internal fun interface WorkerFrameIo {
    operator fun invoke(): JsonObject
}

/** Owns framed worker I/O and observes process exit before releasing capacity. */
internal class WorkerChannel(
    private val process: Process,
    private val input: DataInputStream = DataInputStream(process.inputStream.buffered()),
    private val output: DataOutputStream = DataOutputStream(process.outputStream.buffered()),
    private val ioDispatcher: CoroutineDispatcher,
    private val timeoutMs: Long,
    private val onExit: WorkerExited = WorkerExited {},
) : AutoCloseable {
    private val closed: AtomicBoolean = AtomicBoolean()
    private val exited = CompletableFuture<Unit>()

    init {
        process.onExit().thenRun { observeExit() }
    }

    fun afterExit(action: WorkerExited) {
        exited.thenRun { action() }
    }

    private fun observeExit() {
        onExit()
        exited.complete(Unit)
    }

    suspend fun exchange(frame: JsonObject): JsonObject = within(timeoutMs) {
        CodeModeWire.write(output, frame)
        CodeModeWire.read(input)
    }

    /** V4-226: the worker's first frame, awaited under its own [startTimeoutMs], so a JVM that is slow
     *  to start never spends the script's advance deadline. A fatal frame is a start that failed. */
    suspend fun awaitReady(startTimeoutMs: Long) {
        CodeModeFrames.parseReady(within(startTimeoutMs) { CodeModeWire.read(input) })
    }

    private suspend fun within(deadlineMs: Long, io: WorkerFrameIo): JsonObject = try {
        // A reply is never null: only this deadline maps to an ordinary worker failure.
        withTimeoutOrNull(deadlineMs) {
            suspendCancellableCoroutine<JsonObject> { continuation ->
                continuation.invokeOnCancellation { closeQuietly() }
                ioDispatcher.dispatch(
                    continuation.context,
                    Runnable {
                        try {
                            val reply = io()
                            if (continuation.isActive) continuation.resumeWith(Result.success(reply))
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: IOException) {
                            if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                        } catch (error: IllegalArgumentException) {
                            if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                        }
                    },
                )
            }
        } ?: throw CodeModeTimeoutException(deadlineMs)
    } catch (error: CancellationException) {
        close()
        throw error
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

    /** Total by contract, for the cancellation handler: kotlinx calls it on an undefined thread,
     *  where a throw becomes an uncaught exception and a block parks the canceller. It runs the same
     *  cleanup as [close] but swallows the collected cancellation instead of rethrowing it, and skips
     *  the blocking [observeExitAfterWait] wait — the synchronous exit wait stays in [close] for the
     *  lifecycle contexts that own it. */
    internal fun closeQuietly() {
        if (closed.compareAndSet(false, true)) {
            cleanup(null, CodeModeCleanup(::destroyProcess))
            cleanup(null, CodeModeCleanup { closeStream(output) })
            cleanup(null, CodeModeCleanup { closeStream(input) })
        }
    }

    private fun cleanup(
        current: CancellationException?,
        action: CodeModeCleanup,
    ): CancellationException? = try {
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
            // The process is already closing or inaccessible; stream cleanup still runs.
        }
    }

    private fun closeStream(stream: Closeable) {
        try {
            stream.close()
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            // Protocol streams are best-effort cleanup; the child has already been destroyed.
        } catch (_: RuntimeException) {
            // A custom stream may fail unchecked; the remaining cleanup still has to run.
        }
    }

    private fun observeExitAfterWait() {
        if (waitForExit()) observeExit()
    }

    private fun waitForExit(): Boolean = try {
        process.waitFor(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    } catch (error: CancellationException) {
        throw error
    } catch (_: RuntimeException) {
        // A failed wait is not evidence of exit; the onExit observer retains ownership.
        false
    }
}

/** A spawned process owns capacity until its exit is observed, even if startup is cancelled. */
internal class WorkerPermit(private val permits: Semaphore) {
    private val released = AtomicBoolean()

    @Volatile private var observed: Process? = null

    fun observe(process: Process) {
        observed = process
        process.onExit().thenRun { releaseAfterExit() }
    }

    fun releaseIfUnstarted() {
        if (observed == null) releaseAfterExit()
    }

    /** V4-214: the cancelled-startup path. Destroys the observed process and, once its exit is seen,
     *  returns the permit HERE, so a cancelled start() finishes with its capacity back; the
     *  [observe] callback runs on the JDK reaper's schedule and a start() made the moment the cancel
     *  returned could still find none. Blocks up to [waitMs]: call it on an IO context. An exit not
     *  seen by then is not an exit, and the [observe] callback keeps ownership. */
    fun reapAfterCancel(waitMs: Long) {
        val process = observed ?: return
        try {
            process.destroyForcibly()
            if (process.waitFor(waitMs, TimeUnit.MILLISECONDS)) releaseAfterExit()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: CancellationException) {
            throw error
        } catch (_: RuntimeException) {
            // A failed destroy or wait is not evidence of exit; the onExit observer keeps ownership.
        }
    }

    fun releaseAfterExit() {
        if (released.compareAndSet(false, true)) permits.release()
    }
}
