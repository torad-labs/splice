package splice.codemode.nativehost.sessions

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splice.codemode.nativehost.NativeConnection
import splice.codemode.nativehost.NativeFrames
import splice.codemode.nativehost.NativeRequests
import splice.codemode.nativehost.NativeSessionBinding
import splice.codemode.nativehost.trace.NativeTraceContext
import splice.core.util.Cancellables
import splice.upstream.failure.CODE_MODE_RUNTIME_REPLACED_ERROR
import splice.upstream.failure.CODE_MODE_SESSION_SHUTDOWN_ERROR
import splice.upstream.failure.CodeModeToolException
import java.util.UUID

/** A logical session retains the lost generation until a request reports its replacement. */
internal class NativeLogicalSession(
    private val connection: NativeSessionConnection,
    private val heapBytes: Long,
    private val maxYieldTimeMs: Long?,
) {
    private val opening = Mutex()
    private val state = Any()
    private val shutdown = CompletableDeferred<Unit>()
    private var nextGeneration = 1uL

    @Volatile private var binding: NativeSessionBinding? = null

    @Volatile private var closed = false

    suspend fun initialize() {
        if (binding == null) getOrOpen()
    }

    suspend fun request(): NativeSessionBinding {
        // Capture before waiting for admission: every already queued old-generation request is rejected.
        val expected = binding
        val current = getOrOpen()
        if (expected != null && expected !== current) throw CodeModeToolException(CODE_MODE_RUNTIME_REPLACED_ERROR)
        return current
    }

    fun current(): NativeSessionBinding {
        requireOpen()
        return binding ?: throw CodeModeToolException(CODE_MODE_SESSION_SHUTDOWN_ERROR)
    }

    private fun requireOpen() {
        if (closed) throw CodeModeToolException(CODE_MODE_SESSION_SHUTDOWN_ERROR)
    }

    private fun live(): NativeSessionBinding? = binding?.takeIf { it.host.isAvailable() && it.host.hasSession(it.id) }

    private suspend fun getOrOpen(): NativeSessionBinding = NativeTraceContext.preserve {
        requireOpen()
        live()?.let { return@preserve it }
        supervisorScope {
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                opening.withLock {
                    requireOpen()
                    live() ?: open()
                }
            }
            try {
                select {
                    shutdown.onAwait { throw CodeModeToolException(CODE_MODE_SESSION_SHUTDOWN_ERROR) }
                    pending.onAwait { it }
                }
            } finally {
                pending.cancel()
            }
        }
    }

    private suspend fun open(): NativeSessionBinding {
        // PORT-OF: reconnect.rs reserves a generation even when opening that generation fails.
        val generation = nextGeneration++
        val host = connection.current()
        val id = UUID.randomUUID().toString()
        val operation = host.prepare()
        var published = false
        try {
            host.dispatch(operation, NativeRequests.open(id, heapBytes, maxYieldTimeMs))
            NativeFrames.value(operation.response.await())
            currentCoroutineContext().ensureActive()
            val next = NativeSessionBinding(host, id, generation)
            published = synchronized(state) {
                if (closed) false else { binding = next; true }
            }
            if (!published) throw CodeModeToolException(CODE_MODE_SESSION_SHUTDOWN_ERROR)
            return next
        } catch (cancelled: CancellationException) {
            operation.response.cancel(cancelled)
            throw cancelled
        } finally {
            if (!operation.wasDispatched()) {
                host.discard(operation)
            } else if (!published) {
                withContext(NonCancellable) {
                    Cancellables.runCatchingBestEffort {
                        host.operation(NativeRequests.session("session/shutdown", id))
                    }
                }
            }
        }
    }

    fun close(): NativeSessionBinding? = synchronized(state) {
        closed = true
        shutdown.complete(Unit)
        binding.also { binding = null }
    }
}

internal fun interface NativeSessionConnection {
    suspend fun current(): NativeConnection
}
