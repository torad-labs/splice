package splice.codemode.nativehost.sessions

import splice.codemode.nativehost.DEFAULT_NATIVE_OBSERVE_MS
import splice.codemode.nativehost.NativeControlCell
import splice.codemode.nativehost.NativeControlPreemption
import splice.codemode.nativehost.NativeSessionBinding
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.NativeCodeModeCell
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

/** A wait may replace a lost session; termination is cleanup of the existing one. */
internal class NativeLogicalControl(
    private val session: NativeLogicalSession,
    override val cellId: String,
) : NativeCodeModeCell {
    private val observations = ConcurrentHashMap.newKeySet<NativeLogicalControlObservation>()

    override suspend fun submitResults(results: List<CodeModeResult>) {
        require(results.isEmpty()) { "A native control observation has no delegated calls" }
    }

    override suspend fun advance(results: List<CodeModeResult>): CodeModeStep {
        submitResults(results)
        return observe(DEFAULT_NATIVE_OBSERVE_MS)
    }

    override suspend fun observe(yieldTimeMs: Long): CodeModeStep = observe(BigInteger.valueOf(yieldTimeMs))

    override suspend fun observe(yieldTimeMs: BigInteger): CodeModeStep {
        val observation = NativeLogicalControlObservation()
        observations.add(observation)
        return try {
            val control = control(session.request(), observation.preemption)
            observation.cell = control
            val step = control.observe(yieldTimeMs)
            if (step is CodeModeStep.Yielded) step.copy(cellId = cellId) else step
        } finally {
            observations.remove(observation)
        }
    }

    override suspend fun yieldObservation() {
        observations.forEach { it.yieldObservation() }
    }

    override suspend fun terminate(): CodeModeStep {
        val step = control(session.current()).terminate()
        return if (step is CodeModeStep.Yielded) step.copy(cellId = cellId) else step
    }

    private fun control(binding: NativeSessionBinding, preemption: NativeControlPreemption? = null): NativeControlCell =
        NativeControlCell(cellId, binding.remoteCellId(cellId), binding.id, binding.host, preemption)

    override fun close(): Unit = Unit
}

private class NativeLogicalControlObservation {
    val preemption = NativeControlPreemption()

    @Volatile var cell: NativeControlCell? = null

    suspend fun yieldObservation() {
        preemption.request()
        cell?.yieldObservation()
    }
}
