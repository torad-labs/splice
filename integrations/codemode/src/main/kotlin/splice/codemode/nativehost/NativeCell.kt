package splice.codemode.nativehost

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import splice.core.util.Cancellables
import splice.upstream.codemode.CodeModeInvocationAction
import splice.upstream.codemode.CodeModeManual
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.NativeCodeModeCell
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicBoolean

/** Delegate results resume their original promises; observing a yield never cancels execution. */
internal class NativeCell(
    private val wireCellId: String,
    private val sessionId: String,
    private val connection: NativeConnection,
    private val scope: CoroutineScope,
    initial: NativeOperation,
    definitions: List<CodeModeManual.NestedTool>,
    private val lease: NativeCellLease,
) : NativeCodeModeCell {
    override val cellId: String = "${connection.generation}:$wireCellId"
    private val closed = AtomicBoolean()
    private val advancing = Mutex()
    private val callbacks = NativeCallbacks(connection, scope, definitions)
    private val readySteps = ArrayDeque<CodeModeStep>()
    private val observations = NativeObservations(connection, scope, sessionId, wireCellId, initial)

    override suspend fun advance(results: List<CodeModeResult>): CodeModeStep {
        submitResults(results)
        return observe(DEFAULT_NATIVE_OBSERVE_MS)
    }

    override suspend fun submitResults(results: List<CodeModeResult>): Unit = callbacks.submit(results)

    override suspend fun <T> withInvocationContext(id: String, action: CodeModeInvocationAction<T>): T =
        callbacks.withInvocationContext(id, action)

    override suspend fun observe(yieldTimeMs: Long): CodeModeStep = observe(BigInteger.valueOf(yieldTimeMs))

    override suspend fun observe(yieldTimeMs: BigInteger): CodeModeStep = advancing.withLock {
        require(yieldTimeMs.signum() >= 0 && yieldTimeMs.bitLength() <= NATIVE_UINT64_BITS) {
            "Native observation time must fit uint64"
        }
        check(!closed.get() || readySteps.isNotEmpty()) { "Native cell is closed" }
        try {
            observeLocked(yieldTimeMs)
        } catch (cancelled: CancellationException) {
            // Buffered callbacks remain owned by the cell; only a canceled host wait needs retirement.
            if (readySteps.isEmpty()) observations.cancelled()
            throw cancelled
        }
    }

    private suspend fun observeLocked(yieldTimeMs: BigInteger): CodeModeStep {
        if (readySteps.isEmpty()) {
            val response = observations.response(yieldTimeMs)
            while (readySteps.isEmpty()) {
                select<Unit> {
                    connection.events.cell(sessionId, wireCellId).onReceiveCatching { received ->
                        consumeDelegate(received, response)
                    }
                    response.onAwait { frame ->
                        acceptObservation(frame)
                    }
                }
            }
        }
        val step = readySteps.first()
        return if (step is CodeModeStep.Calls) batchCalls(step) else readySteps.removeFirst()
    }

    private suspend fun consumeDelegate(
        received: ChannelResult<JsonObject>,
        response: CompletableDeferred<JsonObject>,
    ) {
        val frame = received.getOrNull()
        if (frame != null) {
            callbacks.consume(frame)?.let(readySteps::addLast)
            return
        }
        // A session failure can close callbacks after this request's specific error was already delivered.
        if (response.isCompleted) {
            acceptObservation(response.await())
            return
        }
        throw checkNotNull(received.exceptionOrNull())
    }

    private fun acceptObservation(frame: JsonObject) {
        observations.consumed()
        val result = decode(frame)
        readySteps.addLast(result)
        if (result is CodeModeStep.Completed) release()
    }

    override suspend fun yieldObservation(): Unit = observations.yieldObservation()

    /** Let the reader route the same native event-loop turn, then issue ready siblings in one client batch. */
    private suspend fun batchCalls(first: CodeModeStep.Calls): CodeModeStep.Calls {
        yield()
        val calls = first.calls.toMutableList()
        val channel = connection.events.cell(sessionId, wireCellId)
        var draining = true
        while (draining) {
            val frame = channel.tryReceive().getOrNull()
            if (frame == null) {
                draining = false
            } else {
                when (val next = callbacks.consume(frame)) {
                    is CodeModeStep.Calls -> calls.addAll(next.calls)
                    null -> Unit
                    is CodeModeStep.Notified, is CodeModeStep.Completed, is CodeModeStep.Yielded,
                    is CodeModeStep.WaitingForResults,
                    -> {
                        readySteps.addLast(next)
                        draining = false
                    }
                }
            }
        }
        readySteps.removeFirst()
        return CodeModeStep.Calls(calls)
    }

    private fun decode(frame: JsonObject): CodeModeStep = when (
        val step = NativeOutput.decode(NativeFrames.value(frame))
    ) {
        is CodeModeStep.Yielded -> step.copy(cellId = cellId)
        is CodeModeStep.Completed, is CodeModeStep.Calls, is CodeModeStep.Notified,
        is CodeModeStep.WaitingForResults,
        -> step
    }

    override suspend fun terminate(): CodeModeStep {
        check(!closed.get()) { "Native cell is closed" }
        val response = NativeUnaryOperations.response(connection, NativeRequests.terminate(sessionId, wireCellId))
        return NativeOutput.decode(NativeFrames.value(response)).also { step ->
            if (step !is CodeModeStep.Yielded) {
                closed.set(true)
                callbacks.close()
                lease.close()
                if (!advancing.isLocked) connection.events.release(sessionId, wireCellId)
            }
        }
    }

    private fun release() {
        closed.set(true)
        callbacks.close()
        lease.close()
        connection.events.release(sessionId, wireCellId)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            callbacks.close()
            scope.launch {
                Cancellables.runCatchingBestEffort {
                    observations.abandon()
                }
                lease.close()
                if (!advancing.isLocked) connection.events.release(sessionId, wireCellId)
            }
        }
    }
}

// Matches Codex's foreground default; the caller's explicit wait budget overrides this.
internal const val DEFAULT_NATIVE_OBSERVE_MS: Long = 10_000
