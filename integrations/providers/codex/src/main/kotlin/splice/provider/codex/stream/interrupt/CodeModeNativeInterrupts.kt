package splice.provider.codex.stream.interrupt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import splice.provider.codex.CodeModeInterruptedCell
import splice.provider.codex.stream.CodeModeHeldNativeCell
import splice.upstream.codemode.CodeModeStep
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** An interrupt snapshots existing engine owners; it never starts a runtime or closes a session. */
internal class CodeModeNativeInterrupts {
    private val active = ConcurrentHashMap<Pair<String, String>, CodeModeHeldNativeCell>()

    fun started(key: String, held: CodeModeHeldNativeCell) {
        active.putIfAbsent(key to held.cell.cellId, held)
    }

    fun completed(key: String, id: String) {
        active.remove(key to id)
    }

    fun release(key: String, cell: splice.upstream.codemode.NativeCodeModeCell) {
        val id = key to cell.cellId
        active[id]?.takeIf { it.cell === cell }?.let { active.remove(id, it) }
    }

    fun closeSession(key: String) {
        active.keys.removeIf { it.first == key }
    }

    fun close() {
        active.clear()
    }

    fun snapshot(key: String): List<CodeModeNativeInterruptTarget> =
        active.entries.filter {
            it.key.first == key
        }.map { CodeModeNativeInterruptTarget(it.key.first, it.key.second, it.value) }

    suspend fun interrupt(selected: List<CodeModeNativeInterruptTarget>): List<CodeModeNativeInterruptedCell> {
        return supervisorScope {
            selected.map { target ->
                async {
                    val result = terminate(target.owner)
                    if (result.completed) active.remove(target.key to target.cellId, target.owner)
                    result
                }
            }.awaitAll()
        }
    }

    private suspend fun terminate(owner: CodeModeHeldNativeCell): CodeModeNativeInterruptedCell = try {
        when (val step = owner.cell.terminate()) {
            is CodeModeStep.Completed -> CodeModeNativeInterruptedCell(
                owner,
                CodeModeInterruptedCell(owner.cell.cellId, step.terminated, step.error),
                completed = true,
            )
            is CodeModeStep.Calls, is CodeModeStep.WaitingForResults, is CodeModeStep.Notified,
            is CodeModeStep.Yielded,
            -> failure(owner, "Native termination did not finish the cell")
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: IOException) {
        failed(owner, error)
    } catch (error: IllegalStateException) {
        failed(owner, error)
    } catch (error: IllegalArgumentException) {
        failed(owner, error)
    }

    private fun failed(owner: CodeModeHeldNativeCell, error: Exception): CodeModeNativeInterruptedCell =
        failure(owner, error.message ?: "Native termination failed")

    private fun failure(owner: CodeModeHeldNativeCell, message: String): CodeModeNativeInterruptedCell =
        CodeModeNativeInterruptedCell(
            owner,
            CodeModeInterruptedCell(owner.cell.cellId, false, message),
            completed = false,
        )
}

internal data class CodeModeNativeInterruptTarget(
    val key: String,
    val cellId: String,
    val owner: CodeModeHeldNativeCell,
)

internal data class CodeModeNativeInterruptedCell(
    val owner: CodeModeHeldNativeCell,
    val result: CodeModeInterruptedCell,
    val completed: Boolean,
)
