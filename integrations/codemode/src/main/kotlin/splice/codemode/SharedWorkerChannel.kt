// NEW: one reader routes replies by request id; a parked cell owns neither a thread nor a process permit.
package splice.codemode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.selects.select
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

internal data class PendingHostReply(val cell: Long, val answer: CompletableDeferred<HostFrame>)

private class HostCellLifetime {
    val closing = CompletableDeferred<Unit>()
    val exited = CompletableDeferred<Unit>()
}

internal class SharedWorkerChannel(
    private val process: Process,
    parent: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = ProcessDispatchers().io(),
    internal val pending: ConcurrentHashMap<Long, PendingHostReply> = ConcurrentHashMap(),
) : AutoCloseable {
    private val scope = LifecycleScope(parent.coroutineContext)
    private val transport = WorkerChannel(process)
    private val input = transport.input
    private val output = transport.output
    private val ready = CompletableDeferred<Unit>()
    private val sequence = AtomicLong()
    private val addresses = HostReplyAddresses()
    private val writes = Mutex()
    private val closed = AtomicBoolean()
    private val cells = ConcurrentHashMap<Long, HostCellLifetime>()
    private val exited = CompletableDeferred<Unit>()
    private val retiring = AtomicBoolean()

    /** What a task on this worker died of, by class and frame name; set before the worker is retired. */
    @Volatile var death: String? = null
        private set

    /** A retiring worker takes no new placement or control; it closes once the cell that saw the death closes. */
    val isClosed: Boolean get() = closed.get() || retiring.get()

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
        val lifetime = HostCellLifetime()
        cells[id] = lifetime
        if (closed.get()) {
            cells.remove(id)?.exited?.complete(Unit)
            throw CodeModeWorkerLostException()
        }
        return HostCellChannel(this, id, lifetime.exited, session)
    }

    suspend fun control(
        cell: Long,
        payload: JsonObject,
        session: Long = 1,
        timeoutMs: Long = DEFAULT_WORKER_START_TIMEOUT_MS,
    ): JsonObject = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
        send(cell, payload, session, timeoutMs)
    } ?: throw IOException("Code-mode control reply timed out after $timeoutMs ms")

    /** Context close ends its awaits before the worker acknowledges disposal. Controls still own that ack. */
    suspend fun exchange(
        cell: Long,
        payload: JsonObject,
        session: Long = 1,
        writeTimeoutMs: Long? = null,
    ): JsonObject {
        ensureOpen()
        val lifetime = cells[cell] ?: throw CodeModeWorkerLostException()
        return coroutineScope {
            val sending = async { send(cell, payload, session, writeTimeoutMs) }
            try {
                select {
                    lifetime.closing.onAwait { throw CodeModeWorkerLostException() }
                    sending.onAwait { it }
                }
            } finally {
                sending.cancel()
            }
        }
    }

    private suspend fun send(
        cell: Long,
        payload: JsonObject,
        session: Long,
        writeTimeoutMs: Long?,
    ): JsonObject {
        ensureOpen()
        val request = sequence.incrementAndGet()
        val answer = CompletableDeferred<HostFrame>()
        pending[request] = PendingHostReply(cell, answer)
        try {
            // Only the frame write is indivisible; cancelling an execution still cancels its await.
            withContext(NonCancellable) {
                val frame = HostProtocol.frame(cell, request, payload, session, addresses.key(cell, request))
                if (writeTimeoutMs == null) {
                    writeFrame(frame)
                } else {
                    kotlinx.coroutines.withTimeoutOrNull(writeTimeoutMs) {
                        writeFrame(frame)
                        true
                    }
                        ?: throw IOException("Code-mode control write timed out after $writeTimeoutMs ms")
                }
            }
            return answer.await().payload
        } catch (error: IOException) {
            fail(error)
            close()
            throw error
        } finally {
            // The generation-authenticated address recognizes late replies without retaining their callers.
            pending.remove(request)
            answer.cancel()
        }
    }

    private fun validateAddress(reply: HostFrame) {
        val waiting = pending[reply.request]
        if (waiting != null && reply.cell != waiting.cell) {
            throw IOException("Code-mode host replied for a different cell")
        }
        if (reply.request > sequence.get() || !addresses.matches(reply)) {
            throw IOException("Code-mode host replied for an unknown request")
        }
    }

    private fun deliver(reply: HostFrame) {
        validateAddress(reply)
        // A task death retires its generation even when the caller already cancelled. An active caller gets
        // its owed reply first; with no live claimant there is no cell left to close the untrusted process.
        val died = CodeModeFatalFrame.death(reply.payload)?.also { death = it.description }
        if (died != null) retiring.set(true)
        // Failure here still leaves the waiter in the map for the reader's fail-all path.
        val payload = died?.owed() ?: reply.payload
        val waiting = pending.remove(reply.request)
        if (waiting == null) {
            if (died != null) close()
            return
        }
        val accepted = waiting.answer.complete(reply.copy(payload = payload))
        if (died != null && !accepted) close()
    }

    private fun ensureOpen() {
        if (closed.get()) throw CodeModeWorkerLostException()
    }

    private suspend fun writeFrame(frame: JsonObject) = writes.withLock {
        ensureOpen()
        runInterruptible(ioDispatcher) { CodeModeWire.write(output, frame) }
    }

    fun closeCell(id: Long, session: Long) {
        val lifetime = cells[id] ?: return
        if (retiring.get()) return close()
        lifetime.closing.complete(Unit)
        scope.launch {
            try {
                if (!closed.get()) control(id, HostProtocol.close(), session)
            } catch (error: CancellationException) {
                throw error
            } catch (_: IOException) {
                // A failed host already closes every cell; it cannot keep guest state.
            } finally {
                cells.remove(id, lifetime)
                lifetime.exited.complete(Unit)
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
        cells.values.forEach {
            it.closing.complete(Unit)
            it.exited.complete(Unit)
        }
        cells.clear()
        try {
            transport.close()
        } finally {
            scope.cancel()
        }
    }
}
