package splice.provider.codex.diagnostics.current

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.diagnostics.CodeModeGatewayProbeSupport
import splice.upstream.BuiltTurn
import splice.upstream.codemode.CodeModeManual
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeResultDisposition
import splice.upstream.codemode.CodeModeToolOutputs
import java.nio.file.Path

/** Pair the actual freeform permission callback with a real local tool effect and its source-defined value. */
internal class CodeModeGatewayFreeformRequests(
    fixture: CodeModeGatewayProbeSupport,
    specification: JsonObject,
    directory: Path,
) {
    private val requests = CodeModeGatewayTypedRequests(fixture, specification)
    private val effect = CodeModeGatewayPatchEffect(directory, specification)

    init {
        val tool = requests.definitions.single()
        check(tool.codeModeName == "apply_patch" && tool.metadata.kind == CodeModeManual.Kind.FREEFORM)
    }

    suspend fun prepare(manager: CodexCodeModeBridge, callbacks: List<JsonObject>): BuiltTurn {
        val results = callbacks.map { callback ->
            effect.apply(callback)
            // PORT-OF: core/src/tools/handlers/apply_patch.rs @ rust-v0.165.0.
            // ApplyPatchToolOutput::code_mode_result defines the value independently of process stdout.
            // The builtin guest value is empty only after the actual patch tool has completed successfully.
            CodeModeResult(
                checkNotNull(JsonScalars.str(callback, "id")),
                "{}",
                payload = JsonObject(emptyMap()),
                disposition = CodeModeResultDisposition.TOOL_OUTPUT,
            )
        }
        return requests.prepare(manager, callbacks, results)
    }

    fun report(manager: CodexCodeModeBridge, report: JsonObject): JsonObject = JsonObject(
        report + mapOf(
            "boundary" to JsonPrimitive("builtin-apply-patch"),
            "freeformEffects" to effect.report(),
            "toolResults" to JsonArray(
                manager.registry.records.toList().flatMap { record ->
                    record.results.values.map { result ->
                        buildJsonObject {
                            put("id", result.id); put("origin", result.origin.name)
                            put("disposition", result.disposition.name); put("isError", result.isError)
                            put(
                                "payload",
                                checkNotNull(result.payload),
                            ); put("value", CodeModeToolOutputs.value(result))
                        }
                    }
                },
            ),
        ),
    )
}
