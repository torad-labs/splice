package splice.app.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.NativeCodeModeOutput

/** Exposes the production model-output formatter to independent byte-exact reference programs. */
internal object CodeModeParityFormat {
    fun run(nonce: String) {
        require(Regex("[a-f0-9]{32}").matches(nonce)) { "Expected a fresh 128-bit probe nonce" }
        System.`in`.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val input = Json.parseToJsonElement(line).jsonObject
                val tokens = CodeModeParityPayload.number(input, "maxOutputTokens", DEFAULT_FORMAT_TOKENS).toInt()
                val content = NativeCodeModeOutput.content(
                    step(input),
                    CodeModeParityPayload.number(input, "wallMillis", 0),
                    tokens,
                    JsonScalars.str(input, "supportsOriginal") != "false",
                )
                CodeModeParityText.write(
                    System.out,
                    buildJsonObject {
                        put("nonce", nonce)
                        put("input", input)
                        put("content", JsonArray(content))
                    }.toString(),
                )
            }
        }
    }

    private fun step(input: JsonObject): CodeModeStep {
        val content = (input["content"] as? JsonArray).orEmpty().map { it.jsonObject }
        return if (JsonScalars.str(input, "kind") == "yielded") {
            CodeModeStep.Yielded(JsonScalars.str(input, "cellId").orEmpty(), "", content)
        } else {
            CodeModeStep.Completed(
                "",
                JsonScalars.str(input, "error"),
                content,
                terminated = JsonScalars.str(input, "kind") == "terminated",
            )
        }
    }
}

// Codex's default model-visible exec output budget when the fixture supplies no override.
private const val DEFAULT_FORMAT_TOKENS = 10_000L
