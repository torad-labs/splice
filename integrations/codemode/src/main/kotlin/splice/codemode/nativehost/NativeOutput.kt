package splice.codemode.nativehost

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.util.JsonScalars
import splice.upstream.codemode.CodeModeStep
import java.io.IOException

/** Retains native typed content verbatim; text is only a compatibility view for older callers. */
internal object NativeOutput {
    fun decode(value: JsonObject): CodeModeStep {
        val observation = (value["outcome"] as? JsonObject)?.values?.singleOrNull()?.jsonObject ?: value
        val kind = observation.keys.singleOrNull() ?: throw IOException("Invalid native observation variant")
        val payload = observation.getValue(kind).jsonObject
        val content = (payload["content_items"] as? JsonArray)?.map { it.jsonObject }.orEmpty()
        val text = content.mapNotNull { item ->
            if (JsonScalars.str(item, "type") == "input_text") JsonScalars.str(item, "text") else null
        }.joinToString("\n")
        return when (kind) {
            "Yielded" -> CodeModeStep.Yielded(
                requireNotNull(JsonScalars.str(payload, "cell_id")) { "Native yield has no cell id" },
                text,
                content,
            )
            "Result" -> CodeModeStep.Completed(text, JsonScalars.str(payload, "error_text"), content)
            "Terminated" -> CodeModeStep.Completed(text, content = content, terminated = true)
            else -> throw IOException("Unknown native observation variant: $kind")
        }
    }
}
