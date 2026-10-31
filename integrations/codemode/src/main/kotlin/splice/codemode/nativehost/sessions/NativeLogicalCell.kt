package splice.codemode.nativehost.sessions

import kotlinx.coroutines.CancellationException
import splice.codemode.nativehost.DEFAULT_NATIVE_OBSERVE_MS
import splice.codemode.nativehost.NativeSessionBinding
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.NativeCodeModeCell
import splice.upstream.failure.CodeModeToolException
import java.io.IOException
import java.math.BigInteger

/** An accepted execution keeps its own observation; later waits enter through its logical session. */
internal class NativeLogicalCell(
    private val cell: NativeCodeModeCell,
    private val session: NativeLogicalSession,
    private val binding: NativeSessionBinding,
    wireCellId: String,
) : NativeCodeModeCell by cell {
    override val cellId: String = binding.publicCellId(wireCellId)
    private var initial = true
    private var observing = true

    override suspend fun advance(results: List<CodeModeResult>): CodeModeStep {
        cell.submitResults(results)
        return observe(DEFAULT_NATIVE_OBSERVE_MS)
    }

    override suspend fun observe(yieldTimeMs: Long): CodeModeStep = observe(BigInteger.valueOf(yieldTimeMs))

    override suspend fun observe(yieldTimeMs: BigInteger): CodeModeStep {
        if (!observing) {
            if (session.request() !== binding) throw CodeModeToolException(STALE_NATIVE_CELL_ERROR)
            observing = true
        }
        return try {
            observed(cell.observe(yieldTimeMs))
        } catch (cancelled: CancellationException) {
            if (!initial) observing = false
            throw cancelled
        } catch (error: IOException) {
            if (initial) cell.close()
            observing = false
            throw error
        }
    }

    private fun observed(step: CodeModeStep): CodeModeStep {
        if (step is CodeModeStep.Yielded || step is CodeModeStep.Completed) {
            initial = false
            observing = false
        }
        return if (step is CodeModeStep.Yielded) step.copy(cellId = cellId) else step
    }

    override suspend fun terminate(): CodeModeStep {
        // Cleanup checks the existing binding and never opens a replacement runtime.
        session.current().remoteCellId(cellId)
        val step = cell.terminate()
        return if (step is CodeModeStep.Yielded) step.copy(cellId = cellId) else step
    }
}

// PORT-OF: code-mode/src/grpc_session/generation.rs remote_cell_id.
internal const val STALE_NATIVE_CELL_ERROR: String = "cell belongs to a stale code-mode host generation"
