package splice.provider.codex.diagnostics

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.provider.codex.stream.CodeModeNativeInvocations
import splice.provider.codex.stream.arguments.CodeModeSearchArguments
import splice.upstream.codemode.CodeModeManual

/** Model-facing argument parsing through the production exec and wait handlers. */
public object CodeModeArgumentsProbe {
    public fun evaluate(request: JsonObject): JsonObject = try {
        if (JsonScalars.str(request, "operation") == "tool_search_arguments") {
            CodeModeSearchArguments.observation(JsonScalars.str(request, "arguments").orEmpty())
        } else {
            val invocation = invocation(request)
            buildJsonObject {
                put("source", invocation.source?.let(::JsonPrimitive) ?: JsonNull)
                put(
                    "cell_id",
                    invocation.cellId?.let(::JsonPrimitive) ?: JsonNull,
                ); put("terminate", invocation.terminate)
                put(
                    "yield_time_ms",
                    (invocation.options.unsignedYieldTimeMs ?: invocation.options.yieldTimeMs).toString(),
                )
                put(
                    "max_output_tokens",
                    (invocation.options.unsignedMaxOutputTokens ?: invocation.options.maxOutputTokens).toString(),
                )
            }
        }
    } catch (error: IllegalArgumentException) {
        buildJsonObject { put("error", error.message) }
    }
    private fun invocation(request: JsonObject): splice.provider.codex.stream.CodeModeNativeInvocation {
        return when (JsonScalars.str(request, "operation")) {
            "exec_arguments" -> CodeModeNativeInvocations.exec(
                JsonScalars.str(request, "source").orEmpty(),
                JsonScalars.long(request, "default_yield_time_ms") ?: CodeModeManual.DEFAULT_EXEC_YIELD_TIME_MS,
            )
            "wait_arguments" -> CodeModeNativeInvocations.wait(JsonScalars.str(request, "arguments").orEmpty())
            else -> error("Unknown code-mode argument probe operation")
        }
    }
}
