package splice.app.diagnostics

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.NativeCodeModeLifecycle
import java.util.concurrent.ConcurrentHashMap

/** Owns diagnostic cell lookup, without changing production cell execution or observation. */
internal class CodeModeParityCells(
    private val runtime: NativeCodeModeLifecycle,
    private val frames: CodeModeParityFrames,
    private val delegates: CodeModeParityDelegates,
) {
    private val cells = ConcurrentHashMap<Pair<String, String>, CodeModeParityCell>()

    suspend fun execute(id: JsonElement, session: String, request: JsonObject) {
        val start = CodeModeParityStarts.execute(runtime, session, request)
        val held = start.held
        cells[session to held.cell.cellId] = held
        frames.send(CodeModeParityMessages.response(id, CodeModeParityMessages.started(held.cell.cellId)))
        frames.send(CodeModeParityMessages.initial(id, held.cell.cellId, observe(held, start.budget)))
    }

    suspend fun wait(id: JsonElement, session: String, request: JsonObject) {
        val cellId = JsonScalars.str(request, "cell_id").orEmpty()
        val held = cells[session to cellId]
        val step = held?.let {
            observe(it, CodeModeParityPayload.number(request, "yield_time_ms", PARITY_DEFAULT_OBSERVATION_BUDGET))
        } ?: CodeModeStep.Completed("")
        frames.send(CodeModeParityMessages.response(id, CodeModeParityMessages.waited(cellId, step, held != null)))
    }

    suspend fun terminate(id: JsonElement, session: String, cellId: String) {
        val held = cells.remove(session to cellId)
        val step = held?.cell?.terminate() ?: CodeModeStep.Completed("", terminated = true)
        if (held != null) delegates.cancel(held)
        frames.send(CodeModeParityMessages.response(id, CodeModeParityMessages.waited(cellId, step, held != null)))
    }

    fun shutdown(session: String) {
        runtime.closeSession(session)
        cells.entries.filter { it.key.first == session }.forEach { (key, held) ->
            if (cells.remove(key, held)) delegates.cancel(held)
        }
    }

    private suspend fun observe(held: CodeModeParityCell, budget: Long): CodeModeStep {
        while (true) {
            when (val step = held.cell.observe(budget)) {
                is CodeModeStep.Calls -> step.calls.forEach { delegates.callback(held, it) }
                is CodeModeStep.Notified -> delegates.notification(held, step.text)
                is CodeModeStep.Completed, is CodeModeStep.Yielded -> {
                    if (step is CodeModeStep.Completed) {
                        cells.remove(held.session to held.cell.cellId)
                        delegates.cancel(held)
                    }
                    return step
                }
                is CodeModeStep.WaitingForResults -> error("Native observer returned a legacy waiting state")
            }
        }
    }
}
