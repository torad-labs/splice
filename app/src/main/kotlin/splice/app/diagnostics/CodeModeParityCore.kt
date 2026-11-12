package splice.app.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.models.discovery.ModelMetadataProbe
import splice.provider.codex.diagnostics.CodeModeArgumentsProbe
import splice.provider.codex.diagnostics.CodeModeCatalogProbe
import splice.upstream.codemode.diagnostics.CodeModeCoreProbe

/** JSONL adapter for the production core probe, entered only through Main's explicit fixture command. */
internal object CodeModeParityCore {
    fun run(nonce: String) {
        System.`in`.bufferedReader(Charsets.UTF_8).use { reader ->
            for (line in reader.lineSequence()) {
                val request = Json.parseToJsonElement(line).jsonObject
                val output = when (JsonScalars.str(request, "operation")) {
                    "exec_arguments", "wait_arguments", "tool_search_arguments" -> CodeModeArgumentsProbe.evaluate(
                        request,
                    )
                    "catalog_wait" -> CodeModeCatalogProbe.evaluate(request)
                    "model_metadata" -> ModelMetadataProbe.evaluate(request)
                    else -> CodeModeCoreProbe.evaluate(request)
                }
                val result = buildJsonObject {
                    put("nonce", nonce)
                    put("id", request["id"] ?: JsonNull)
                    put("result", output)
                }
                CodeModeParityText.write(System.out, JsonWire.string(result))
            }
        }
    }
}
