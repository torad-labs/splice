package splice.codemode.nativehost

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import splice.core.util.Cancellables
import splice.upstream.codemode.CodeModeInvocationAction
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
    private val callbacks: NativeCallbacks,
    private val lease: NativeCellLease,
) : NativeCodeModeCell {
    override val cellId: String = "${connection.generation}:$wireCellId"
    private val closed = AtomicBoolean()
    private val advancing = Mutex()
    private val readySteps = ArrayDeque<CodeModeStep>()
    private val observations = NativeObservations(connection, scope, sessionId, wireCellId, initial)
    private val delegates = NativeDelegatePump(connection, scope, sessionId, wireCellId, callbacks)

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
                val queued = delegates.poll()
                if (queued != null) {
                    callbacks.consume(queued)?.let(readySteps::addLast)
                } else {
                    select<Unit> {
                        delegates.ready.onReceiveCatching { received -> consumeDelegate(received, response) }
                        response.onAwait { frame -> acceptObservation(frame) }
                    }
                }
            }
        }
        val first = readySteps.first() as? CodeModeStep.Calls ?: return readySteps.removeFirst()
        if (readySteps.all { it is CodeModeStep.Calls }) collectCallbackBurst()
        readySteps.removeFirst()
        val calls = first.calls.toMutableList()
        while (true) {
            val next = readySteps.firstOrNull() as? CodeModeStep.Calls ?: break
            readySteps.removeFirst()
            calls.addAll(next.calls)
        }
        return CodeModeStep.Calls(calls)
    }

    private suspend fun consumeDelegate(
        received: ChannelResult<Unit>,
        response: CompletableDeferred<JsonObject>,
    ) {
        val frame = delegates.poll()
        if (frame != null) {
            callbacks.consume(frame)?.let(readySteps::addLast)
            return
        }
        if (received.isSuccess) return
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

    /** Coalesce a native callback burst across reader scheduling without waiting for tool results. */
    private suspend fun collectCallbackBurst() {
        // Preserve the foreground observer's fairness/cancellation point with its calls still buffered.
        yield()
        // The native protocol has no end-of-burst marker. A quiet interval lets
        // sibling invokes cross the transport; the outer deadline prevents a
        // continuous producer from holding a client batch indefinitely.
        withTimeoutOrNull(NATIVE_CALLBACK_BATCH_MAX_MS) {
            var collecting = true
            while (collecting) {
                currentCoroutineContext().ensureActive()
                val frame = delegates.poll()
                if (frame == null) {
                    val received = withTimeoutOrNull(NATIVE_CALLBACK_BATCH_QUIET_MS) {
                        delegates.ready.receiveCatching()
                    }
                    // Return prepared calls first. The next observation retains
                    // consumeDelegate's completed-response/error precedence.
                    collecting = received?.isSuccess == true
                } else {
                    val next = callbacks.consume(frame)
                    // Keep ownership in the cell across suspension/cancellation.
                    if (next != null) readySteps.addLast(next)
                    collecting = next == null || next is CodeModeStep.Calls
                }
            }
        }
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
                delegates.close()
                lease.close()
                if (!advancing.isLocked) connection.events.release(sessionId, wireCellId)
            }
        }
    }

    private fun release() {
        closed.set(true)
        callbacks.close()
        delegates.close()
        lease.close()
        connection.events.release(sessionId, wireCellId)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            callbacks.close()
            delegates.close()
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
private const val NATIVE_CALLBACK_BATCH_QUIET_MS: Long = 10
private const val NATIVE_CALLBACK_BATCH_MAX_MS: Long = 50
