package splice.codemode.nativehost

import kotlinx.coroutines.CancellationException
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.NativeCodeModeCell
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

/** A control request for an unretained ID asks the host directly without evaluating another module. */
internal class NativeControlCell(
    override val cellId: String,
    private val wireCellId: String,
    private val sessionId: String,
    private val connection: NativeConnection,
    private val preemption: NativeControlPreemption? = null,
) : NativeCodeModeCell {
    private val observations = ConcurrentHashMap.newKeySet<NativeControlObservation>()

    override suspend fun submitResults(results: List<CodeModeResult>) {
        require(results.isEmpty()) { "A native control observation has no delegated calls" }
    }

    override suspend fun advance(results: List<CodeModeResult>): CodeModeStep {
        submitResults(results)
        return observe(DEFAULT_NATIVE_OBSERVE_MS)
    }

    override suspend fun observe(yieldTimeMs: Long): CodeModeStep = observe(BigInteger.valueOf(yieldTimeMs))

    override suspend fun observe(yieldTimeMs: BigInteger): CodeModeStep {
        require(
            yieldTimeMs.signum() >= 0 && yieldTimeMs.bitLength() <= Long.SIZE_BITS,
        ) { "Native observation time must fit uint64" }
        val operation = connection.prepare()
        val observation = NativeControlObservation(operation)
        observations.add(observation)
        if (preemption?.requested == true) observation.requestYield()
        return try {
            connection.dispatch(operation, NativeRequests.wait(sessionId, wireCellId, yieldTimeMs))
            yieldRequested(observation)
            NativeOutput.decode(NativeFrames.value(operation.response.await()))
        } catch (cancelled: CancellationException) {
            if (operation.wasDispatched()) connection.cancelObservation(operation.id)
            throw cancelled
        } finally {
            observations.remove(observation)
            if (!operation.wasDispatched()) connection.discard(operation)
        }
    }

    override suspend fun yieldObservation() {
        observations.forEach { observation ->
            observation.requestYield()
            yieldRequested(observation)
        }
    }

    private suspend fun yieldRequested(observation: NativeControlObservation) {
        if (observation.claimYield()) {
            connection.send(
                NativeRequests.control("operation/yield", observation.operation.id),
            )
        }
    }

    override suspend fun terminate(): CodeModeStep {
        val result = NativeUnaryOperations.response(connection, NativeRequests.terminate(sessionId, wireCellId))
        return NativeOutput.decode(NativeFrames.value(result))
    }

    override fun close(): Unit = Unit
}
