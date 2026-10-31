package splice.app.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.util.JsonScalars
import splice.upstream.codemode.NativeCodeModeLifecycle
import splice.upstream.codemode.NativeCodeModeOptions

internal object CodeModeParityStarts {
    suspend fun execute(runtime: NativeCodeModeLifecycle, session: String, request: JsonObject): CodeModeParityStart {
        val definitions = (request["enabled_tools"] as? JsonArray).orEmpty().map { it.jsonObject }
        val tools = definitions.associate { item ->
            val name = item.getValue("tool_name").jsonObject
            JsonScalars.str(name, "name").orEmpty() to JsonScalars.str(item, "description").orEmpty()
        }
        val options = NativeCodeModeOptions(
            CodeModeParityPayload.number(request, "yield_time_ms", PARITY_DEFAULT_OBSERVATION_BUDGET),
            CodeModeParityPayload.number(request, "max_output_tokens", PARITY_DEFAULT_OBSERVATION_BUDGET),
        )
        val cell = runtime.startNativeSession(
            session,
            JsonScalars.str(request, "source").orEmpty(),
            tools.keys,
            tools,
            options,
        )
        return CodeModeParityStart(CodeModeParityCell(session, cell), options.yieldTimeMs)
    }
}

internal data class CodeModeParityStart(val held: CodeModeParityCell, val budget: Long)

// Match the pinned native host's default observation milliseconds and output-token request budget.
internal const val PARITY_DEFAULT_OBSERVATION_BUDGET = 10_000L
