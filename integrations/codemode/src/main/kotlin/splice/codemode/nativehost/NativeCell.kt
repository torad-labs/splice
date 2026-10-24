package splice.codemode.nativehost

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import splice.core.util.Cancellables
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.NativeCodeModeCell
import java.util.concurrent.atomic.AtomicBoolean

/** Delegate results resume their original promises; observing a yield never cancels execution. */
internal class NativeCell(
    private val wireCellId: String,
    private val sessionId: String,
    private val connection: NativeConnection,
    private val scope: CoroutineScope,
    initial: CompletableDeferred<JsonObject>,
    tools: Set<String>,
    private val lease: NativeCellLease,
) : NativeCodeModeCell {
    override val cellId: String = "${connection.generation}:$wireCellId"
    private val closed = AtomicBoolean()
    private val advancing = Mutex()
    private val callbacks = NativeCallbacks(connection, scope, tools)
    private val readySteps = ArrayDeque<CodeModeStep>()
    private var observation: CompletableDeferred<JsonObject>? = initial

    override suspend fun advance(results: List<CodeModeResult>): CodeModeStep {
        submitResults(results)
        return observe(DEFAULT_NATIVE_OBSERVE_MS)
    }

    override suspend fun submitResults(results: List<CodeModeResult>): Unit = callbacks.submit(results)

    override suspend fun observe(yieldTimeMs: Long): CodeModeStep = advancing.withLock {
        require(yieldTimeMs >= 0) { "Native observation time must be nonnegative" }
        check(!closed.get() || readySteps.isNotEmpty()) { "Native cell is closed" }
        observeLocked(yieldTimeMs)
    }

    private suspend fun observeLocked(yieldTimeMs: Long): CodeModeStep {
        if (readySteps.isEmpty()) {
            val response = response(yieldTimeMs)
            while (readySteps.isEmpty()) {
                select<Unit> {
                    connection.events.cell(sessionId, wireCellId).onReceive { frame ->
                        callbacks.consume(frame)?.let(readySteps::addLast)
                    }
                    response.onAwait { frame ->
                        observation = null
                        val result = decode(frame)
                        readySteps.addLast(result)
                        if (result is CodeModeStep.Completed) release()
                    }
                }
            }
        }
        val step = readySteps.first()
        return if (step is CodeModeStep.Calls) batchCalls(step) else readySteps.removeFirst()
    }

    private suspend fun response(yieldTimeMs: Long): CompletableDeferred<JsonObject> {
        observation?.let { return it }
        val operation = connection.prepare()
        observation = operation.response
        try {
            connection.dispatch(operation, NativeRequests.wait(sessionId, wireCellId, yieldTimeMs))
        } finally {
            if (!operation.wasDispatched()) {
                connection.discard(operation)
                observation = null
            }
        }
        return operation.response
    }

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
        val operation = connection.operation(NativeRequests.terminate(sessionId, wireCellId))
        return NativeOutput.decode(NativeFrames.value(operation.response.await())).also {
            closed.set(true)
            callbacks.close()
            lease.close()
            if (!advancing.isLocked) connection.events.release(sessionId, wireCellId)
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
                    connection.operation(NativeRequests.terminate(sessionId, wireCellId)).response.await()
                }
                lease.close()
                if (!advancing.isLocked) connection.events.release(sessionId, wireCellId)
            }
        }
    }
}

// Matches Codex's foreground default; the caller's explicit wait budget overrides this.
internal const val DEFAULT_NATIVE_OBSERVE_MS: Long = 10_000
