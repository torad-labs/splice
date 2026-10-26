package splice.codemode.nativehost

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.codemode.nativehost.trace.NativeTraceSpan
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeInvocationAction
import splice.upstream.codemode.CodeModeManual
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Permission-checked client callbacks own their original delegate identity independently of observation. */
internal class NativeCallbacks(
    private val connection: NativeConnection,
    private val scope: CoroutineScope,
    definitions: List<CodeModeManual.NestedTool>,
) {
    private val closed = AtomicBoolean()
    private val submitting = Mutex()
    private val pending = ConcurrentHashMap<String, NativeCallbackPending>()
    private val tools = definitions.associateBy {
        (it.metadata.namespace?.takeIf(String::isNotEmpty) ?: "functions") to it.name
    }

    suspend fun submit(results: List<CodeModeResult>): Unit = submitting.withLock {
        check(!closed.get() || results.isEmpty()) { "Native cell is closed" }
        require(results.map { it.id }.distinct().size == results.size) { "Duplicate native callback results" }
        require(results.all { pending.containsKey(it.id) }) { "Result does not belong to a pending native callback" }
        results.forEach { result ->
            withInvocationContext(result.id) {
                val id = pending.getValue(result.id).id
                connection.send(NativeRequests.delegate(id, result))
                connection.events.answered(id.toString())
                pending.remove(result.id)
            }
        }
    }

    suspend fun <T> withInvocationContext(id: String, action: CodeModeInvocationAction<T>): T {
        val trace = pending[id]?.trace
        return if (trace == null) action.run() else trace.invocationContext.within(action)
    }

    fun consume(frame: JsonObject): CodeModeStep? {
        val id = frame.getValue("id")
        if (JsonScalars.str(frame, "type") == "delegate/cancel") {
            pending.entries.removeIf { it.value.id == id }
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
        val namespace = JsonScalars.str(tool, "namespace")
        val definition = tools[(namespace?.takeIf(String::isNotEmpty) ?: "functions") to name]
            ?: error("Native callback requested a tool outside the client catalog")
        val expectedKind = when (definition.metadata.kind) {
            CodeModeManual.Kind.FUNCTION -> "function"
            CodeModeManual.Kind.FREEFORM -> "freeform"
        }
        check(JsonScalars.str(invocation, "tool_kind") == expectedKind) { "Native callback changed its tool kind" }
        val input = invocation["input"]
        val error = NativeToolInputs.error(definition, input)
        if (error != null) {
            acknowledge(
                id,
                NativeRequests.delegate(
                    id,
                    CodeModeResult(
                        "native-$id",
                        error,
                        isError = true,
                    ),
                ),
            )
            return null
        }
        val callId = "native-${UUID.randomUUID()}"
        val trace = JsonScalars.str(id)?.let(connection.tracing.invocations::get)
        pending[callId] = NativeCallbackPending(id, trace)
        return CodeModeStep.Calls(
            listOf(
                CodeModeCall(
                    callId,
                    definition.codeModeName,
                    input as? JsonObject ?: JsonObject(emptyMap()),
                    rawInput = input,
                    namespace = namespace,
                    kind = definition.metadata.kind,
                    originalName = name,
                ),
            ),
        )
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

private data class NativeCallbackPending(val id: JsonElement, val trace: NativeTraceSpan?)
