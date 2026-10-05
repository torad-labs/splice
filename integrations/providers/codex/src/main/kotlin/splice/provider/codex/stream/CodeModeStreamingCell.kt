// NEW: cell disposal ends execution; the already-posted response reader drains to its usage terminal.
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
    var deliveredText: String? = null
        private set
    val deliveredNative: List<kotlinx.serialization.json.JsonElement>? get() = round.switching.deliveredNative

    override suspend fun advance(results: List<CodeModeResult>): CodeModeStep {
        currentCoroutineContext().ensureActive()
        checkSource()
        return try {
            val step = cell.advance(results)
            round.source.awaitCertification()
            checkSource()
            if (step is CodeModeStep.Calls) deliveredText = round.switching.detach()
            step
        } catch (error: CancellationException) {
            throw error
        } catch (error: CodeModeInfrastructureException) {
            throw error
        } catch (error: CodeModePersistenceException) {
            throw error
        } catch (error: IOException) {
            checkClosed()
            throw error
        } catch (error: IllegalStateException) {
            checkClosed()
            throw error
        }
    }

    /** Also checked under the registry key immediately before calls are saved or issued. A step holding this cell when
     *  the round lost its source ends as a torn source's step does. */
    fun checkSource() {
        if (round.sourceInterrupted || round.sourceLost) {
            throw CodeModeSourceInterruptedException(round.permanentEnding)
        }
    }

    /** After the cell threw: a cell the round closed under this step, a dead reader's included, ends the step as a
     *  torn source's step, never as splice's protocol failure. A dead reader alone does not stop a step that has not
     *  met the closed cell, so a result already delivered before the loss still reaches the script. */
    private fun checkClosed() {
        if (round.sourceInterrupted || round.closedLiveCell) {
            throw CodeModeSourceInterruptedException(round.permanentEnding)
        }
    }

    override fun close() {
        cell.close()
        if (record.sourceState?.complete != true) round.cancel()
    }
}
