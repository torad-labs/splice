// NEW: cell disposal cancels an unfinished source reader, while normal EOF preserves its terminal outcome.
package splice.provider.codex.stream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import splice.provider.codex.CodeModePersistenceException
import splice.provider.codex.CodeModeRecord
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import splice.upstream.failure.CodeModeInfrastructureException
import java.io.IOException

internal class CodeModeStreamingCell(
    private val cell: CodeModeCell,
    private val record: CodeModeRecord,
    private val round: CodeModeLiveRound,
) : CodeModeCell by cell {
    override suspend fun advance(results: List<CodeModeResult>): CodeModeStep {
        currentCoroutineContext().ensureActive()
        checkSource()
        return try {
            val step = cell.advance(results)
            round.source.awaitCertification()
            checkSource()
            step
        } catch (error: CancellationException) {
            throw error
        } catch (error: CodeModeInfrastructureException) {
            throw error
        } catch (error: CodeModePersistenceException) {
            throw error
        } catch (error: IOException) {
            if (round.sourceInterrupted) throw CodeModeSourceInterruptedException()
            throw error
        } catch (error: IllegalStateException) {
            if (round.sourceInterrupted) throw CodeModeSourceInterruptedException()
            throw error
        }
    }

    /** Also checked under the registry key immediately before calls are saved or issued. */
    fun checkSource() {
        if (round.sourceInterrupted) throw CodeModeSourceInterruptedException()
    }

    override fun close() {
        cell.close()
        if (record.sourceState?.complete != true) round.cancel()
    }
}
