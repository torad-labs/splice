package splice.app.diagnostics

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Reply delivery stays independent of cell observation, including fast dependency chains. */
internal class CodeModeParityDelegates(private val frames: CodeModeParityFrames) {
    private data class Pending(val held: CodeModeParityCell, val callback: String)
    private val pending = ConcurrentHashMap<String, Pending>()
    private val ids = AtomicLong()

    fun callback(held: CodeModeParityCell, call: CodeModeCall) {
        val id = ids.incrementAndGet().toString()
        pending[id] = Pending(held, call.id)
        frames.send(CodeModeParityMessages.delegate(id, held, CodeModeParityInvocations.call(held, call)))
    }

    fun notification(held: CodeModeParityCell, text: String) {
        frames.send(
            CodeModeParityMessages.delegate(
                ids.incrementAndGet().toString(),
                held,
                CodeModeParityInvocations.notification(held, text),
            ),
        )
    }

    /** A terminal isolate discards unresolved delegates; preserve that cancellation on the wire. */
    fun cancel(held: CodeModeParityCell) {
        pending.entries.filter { it.value.held === held }.forEach { (id, callback) ->
            if (pending.remove(id, callback)) frames.send(CodeModeParityMessages.cancelled(id))
        }
    }

    suspend fun reply(frame: JsonObject) {
        val id = JsonScalars.str(frame, "id") ?: return
        val awaiting = pending.remove(id) ?: return
        val result = frame.getValue("result").jsonObject
        val success = JsonScalars.str(result, "status") == "ok"
        val value = (result["value"] as? JsonObject)?.get("result") ?: result["message"] ?: JsonNull
        awaiting.held.cell.submitResults(listOf(CodeModeResult(awaiting.callback, JsonWire.string(value), !success)))
    }
}
