package splice.provider.codex.branch

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.provider.codex.CodeModePending
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRunContext
import splice.upstream.codemode.CodeModeCall

/** A cleared result still needs the unique issued callback's original name, arguments and error state. */
internal class CodeModeClearedIdentity(
    private val context: CodeModeRunContext,
    items: List<JsonElement>,
    private val log: LogSink,
) {
    private val calls = items.filterIsInstance<JsonObject>().filter { text(it, "type") == "function_call" }
        .groupBy { text(it, "call_id") }
    private val outputs = items.filterIsInstance<JsonObject>().filter { text(it, "type") == "function_call_output" }
        .groupBy { text(it, "call_id") }

    fun matches(owner: CodeModeRecord, id: String): Boolean {
        val candidate = context.turn.rendering.clearedCallbacks[id] ?: return false
        val call = owner.issued.flatMap { it.calls }
            .filter { it.clientId == id }.distinct().singleOrNull() ?: return false
        val identity = matchesCall(call, candidate) && uniqueWire(id, candidate)
        val result = context.turn.toolResults.singleOrNull { it.id == id }
        return identity && result?.isError == owner.results[id]?.isError
    }

    private fun matchesCall(call: CodeModePending, candidate: CodeModeCall): Boolean =
        call.exposed && call.name == candidate.name && call.arguments == candidate.arguments

    private fun uniqueWire(id: String, candidate: CodeModeCall): Boolean {
        val wire = calls[id]?.singleOrNull() ?: return false
        return outputs[id]?.size == 1 && matchesWire(wire, candidate.name, candidate.arguments)
    }

    private fun matchesWire(item: JsonObject, name: String, arguments: JsonObject): Boolean {
        val parsed = try {
            Json.parseToJsonElement(text(item, "arguments"))
        } catch (_: SerializationException) {
            log("[code-mode] cleared callback replay skipped: malformed callback arguments")
            return false
        }
        return text(item, "name") == name && parsed == arguments
    }

    private fun text(item: JsonObject, field: String): String = JsonScalars.str(item, field).orEmpty()
}
