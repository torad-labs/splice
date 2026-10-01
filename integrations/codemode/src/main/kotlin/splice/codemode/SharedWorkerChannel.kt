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
    val isClosed: Boolean get() = closed.get()

    init {
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

    fun cell(id: Long): CellChannel {
        ensureOpen()
        val exited = CompletableDeferred<Unit>()
        cells[id] = exited
        if (closed.get()) {
            cells.remove(id)?.complete(Unit)
            throw CodeModeWorkerLostException()
        }
        return HostCellChannel(this, id, exited)
    }

    suspend fun exchange(cell: Long, payload: JsonObject): JsonObject {
        ensureOpen()
        val request = sequence.incrementAndGet()
        val answer = CompletableDeferred<HostFrame>()
        pending[request] = PendingHostReply(cell, answer)
        var sent = false
        try {
            // Only the frame write is indivisible; cancelling an execution still cancels its await.
            withContext(NonCancellable) {
                writeFrame(HostProtocol.frame(cell, request, payload))
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

    fun closeCell(id: Long) {
        scope.launch {
            try {
                if (!closed.get()) exchange(id, HostProtocol.close())
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
        if (closed.compareAndSet(false, true)) {
            fail(CodeModeWorkerLostException())
            try {
                transport.close()
            } finally {
                scope.cancel()
            }
        }
    }

    private fun fail(error: IOException) {
        ready.completeExceptionally(error)
        pending.values.forEach { it.answer.completeExceptionally(error) }
        pending.clear()
        cells.values.forEach { it.complete(Unit) }
        cells.clear()
    }
}

private class HostCellChannel(
    private val host: SharedWorkerChannel,
    private val id: Long,
    private val exited: CompletableDeferred<Unit>,
) : CellChannel {
    private val closed = AtomicBoolean()

    override suspend fun exchange(frame: JsonObject): JsonObject {
        check(!closed.get()) { "Code-mode cell is closed" }
        return host.exchange(id, frame)
    }

    override fun afterExit(action: WorkerExited) {
        exited.invokeOnCompletion { action() }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) host.closeCell(id)
    }
}
