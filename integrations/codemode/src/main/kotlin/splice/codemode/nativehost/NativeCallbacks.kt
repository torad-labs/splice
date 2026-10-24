package splice.codemode.nativehost

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Permission-checked client callbacks own their original delegate identity independently of observation. */
internal class NativeCallbacks(
    private val connection: NativeConnection,
    private val scope: CoroutineScope,
    private val tools: Set<String>,
) {
    private val closed = AtomicBoolean()
    private val submitting = Mutex()
    private val pending = ConcurrentHashMap<String, JsonElement>()

    suspend fun submit(results: List<CodeModeResult>): Unit = submitting.withLock {
        check(!closed.get() || results.isEmpty()) { "Native cell is closed" }
        require(results.map { it.id }.distinct().size == results.size) { "Duplicate native callback results" }
        require(results.all { pending.containsKey(it.id) }) { "Result does not belong to a pending native callback" }
        results.forEach { result ->
            val id = pending.getValue(result.id)
            connection.send(NativeRequests.delegate(id, result))
            connection.events.answered(id.toString())
            pending.remove(result.id)
        }
    }

    fun consume(frame: JsonObject): CodeModeStep? {
        val id = frame.getValue("id")
        if (JsonScalars.str(frame, "type") == "delegate/cancel") {
            pending.entries.removeIf { it.value == id }
            return null
        }
        val request = frame.getValue("request").jsonObject
        return when (JsonScalars.str(request, "type")) {
            "notification/send" -> {
                acknowledge(id, NativeRequests.notified(id))
                CodeModeStep.Notified(JsonScalars.str(request, "text").orEmpty())
            }
            "tool/invoke" -> invoke(id, request.getValue("invocation").jsonObject)
            else -> error("Unknown native delegate request")
        }
    }

    private fun invoke(id: JsonElement, invocation: JsonObject): CodeModeStep? {
        val tool = invocation.getValue("tool_name").jsonObject
        val name = JsonScalars.str(tool, "name") ?: error("Native tool callback has no name")
        check(name in tools) { "Native callback requested a tool outside the client catalog" }
        val arguments = invocation["input"] as? JsonObject
        if (arguments == null) {
            acknowledge(
                id,
                NativeRequests.delegate(
                    id,
                    CodeModeResult(
                        "native-$id",
                        "Client function tool $name requires a JSON object argument",
                        isError = true,
                    ),
                ),
            )
            return null
        }
        val callId = "native-$id"
        pending[callId] = id
        return CodeModeStep.Calls(listOf(CodeModeCall(callId, name, arguments)))
    }

    private fun acknowledge(id: JsonElement, frame: JsonObject) {
        scope.launch {
            Cancellables.runCatchingBestEffort {
                connection.send(frame)
                connection.events.answered(id.toString())
            }
        }
    }

    fun close() {
        closed.set(true)
        pending.clear()
    }
}
