// NEW: one reader routes replies by request id; a parked cell owns neither a thread nor a process permit.
package splice.codemode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import splice.upstream.LifecycleScope
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.failure.CodeModeWorkerLostException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private data class PendingHostReply(val cell: Long, val answer: CompletableDeferred<HostFrame>)

internal class SharedWorkerChannel(
    private val process: Process,
    parent: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = ProcessDispatchers().io(),
) : AutoCloseable {
    private val scope = LifecycleScope(parent.coroutineContext)
    private val transport = WorkerChannel(process)
    private val input = transport.input
    private val output = transport.output
    private val ready = CompletableDeferred<Unit>()
    private val pending = ConcurrentHashMap<Long, PendingHostReply>()
    private val sequence = AtomicLong()
    private val writes = Mutex()
    private val closed = AtomicBoolean()
    private val cells = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()
    private val exited = CompletableDeferred<Unit>()
    val isClosed: Boolean get() = closed.get()

    init {
        transport.afterExit { exited.complete(Unit) }
        scope.launch {
            try {
                runInterruptible(ioDispatcher) { CodeModeFrames.parseReady(CodeModeWire.read(input)) }
                ready.complete(Unit)
                while (!closed.get()) {
                    val reply = runInterruptible(ioDispatcher) { HostProtocol.parse(CodeModeWire.read(input)) }
                    deliver(reply)
                }
            } catch (error: CancellationException) {
                fail(CodeModeWorkerLostException(error))
                throw error
            } catch (error: IOException) {
                fail(error)
            } catch (error: IllegalArgumentException) {
                fail(IOException("Code-mode host sent an invalid frame", error))
            } finally {
                close()
            }
        }
    }

    suspend fun awaitReady() = ready.await()

    fun afterExit(action: WorkerExited) {
        exited.invokeOnCompletion { action() }
    }

    fun cell(id: Long, session: Long = 1): CellChannel {
        ensureOpen()
        val exited = CompletableDeferred<Unit>()
        cells[id] = exited
        if (closed.get()) {
            cells.remove(id)?.complete(Unit)
            throw CodeModeWorkerLostException()
        }
        return HostCellChannel(this, id, exited, session)
    }

    suspend fun control(
        cell: Long,
        payload: JsonObject,
        session: Long = 1,
        timeoutMs: Long = DEFAULT_WORKER_START_TIMEOUT_MS,
    ): JsonObject = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
        exchange(cell, payload, session, timeoutMs)
    } ?: throw IOException("Code-mode control reply timed out after $timeoutMs ms")

    suspend fun exchange(
        cell: Long,
        payload: JsonObject,
        session: Long = 1,
        writeTimeoutMs: Long? = null,
    ): JsonObject {
        ensureOpen()
        val request = sequence.incrementAndGet()
        val answer = CompletableDeferred<HostFrame>()
        pending[request] = PendingHostReply(cell, answer)
        var sent = false
        try {
            // Only the frame write is indivisible; cancelling an execution still cancels its await.
            withContext(NonCancellable) {
                val frame = HostProtocol.frame(cell, request, payload, session)
                if (writeTimeoutMs == null) {
                    writeFrame(frame)
                } else {
                    kotlinx.coroutines.withTimeoutOrNull(writeTimeoutMs) {
                        writeFrame(frame)
                        true
                    }
                        ?: throw IOException("Code-mode control write timed out after $writeTimeoutMs ms")
                }
                sent = true
            }
            return answer.await().payload
        } catch (error: IOException) {
            fail(error)
            close()
            throw error
        } finally {
            // A cancelled caller's legitimate late reply must still be recognized by the reader.
            if (sent) answer.cancel() else pending.remove(request)
        }
    }

    private fun deliver(reply: HostFrame) {
        val waiting = pending[reply.request] ?: throw IOException("Code-mode host replied for an unknown request")
        if (reply.cell != waiting.cell) throw IOException("Code-mode host replied for a different cell")
        pending.remove(reply.request)
        waiting.answer.complete(reply)
    }

    private fun ensureOpen() {
        if (closed.get()) throw CodeModeWorkerLostException()
    }

    private suspend fun writeFrame(frame: JsonObject) = writes.withLock {
        ensureOpen()
        runInterruptible(ioDispatcher) { CodeModeWire.write(output, frame) }
    }

    fun closeCell(id: Long, session: Long) {
        scope.launch {
            try {
                if (!closed.get()) control(id, HostProtocol.close(), session)
            } catch (error: CancellationException) {
                throw error
            } catch (_: IOException) {
                // A failed host already closes every cell; it cannot keep guest state.
            } finally {
                cells.remove(id)?.complete(Unit)
            }
        }
    }

    override fun close() {
        fail(CodeModeWorkerLostException())
    }

    private fun fail(error: IOException) {
        // Publish generation loss before waking callers that can immediately request another host.
        if (!closed.compareAndSet(false, true)) return
        ready.completeExceptionally(error)
        pending.values.forEach { it.answer.completeExceptionally(error) }
        pending.clear()
        cells.values.forEach { it.complete(Unit) }
        cells.clear()
        try {
            transport.close()
        } finally {
            scope.cancel()
        }
    }
}

private class HostCellChannel(
    private val host: SharedWorkerChannel,
    private val id: Long,
    private val exited: CompletableDeferred<Unit>,
    private val session: Long,
) : CellChannel {
    private val closed = AtomicBoolean()

    override suspend fun exchange(frame: JsonObject): JsonObject {
        if (host.isClosed) throw CodeModeWorkerLostException()
        check(!closed.get()) { "Code-mode cell is closed" }
        return host.exchange(id, frame, session)
    }

    override fun afterExit(action: WorkerExited) {
        exited.invokeOnCompletion { action() }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) host.closeCell(id, session)
    }
}
