// PORT-OF: core/src/tools/code_mode/mod.rs serialize_function_tool_arguments/build_freeform_tool_payload @ rust-v0.164.0.
package splice.codemode.nativehost

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.upstream.codemode.CodeModeManual

/** Validate presence separately from explicit null before crossing an object-only client boundary. */
internal object NativeToolInputs {
    fun error(definition: CodeModeManual.NestedTool, input: JsonElement?): String? {
        val namespace = definition.metadata.namespace
        val displayName = when (namespace) {
            null, "", "functions" -> definition.name
            else -> namespace + definition.name
        }
        return when (definition.metadata.kind) {
            CodeModeManual.Kind.FUNCTION -> if (input == null || input is JsonObject) {
                null
            } else {
                "tool `$displayName` expects a JSON object for arguments"
            }
            CodeModeManual.Kind.FREEFORM -> if (input is JsonPrimitive && input.isString) {
                null
            } else {
                "tool `$displayName` expects a string input"
            }
        }
    }
}
