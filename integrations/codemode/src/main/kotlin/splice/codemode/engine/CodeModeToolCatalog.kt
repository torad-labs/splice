// NEW: one catalog projection supplies declared client names and descriptions to every guest context.
package splice.codemode.engine

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.codemode.CodeModeJson
import splice.codemode.WorkerStart
import splice.upstream.codemode.CodeModeManual

internal object CodeModeToolCatalog {
    fun render(start: WorkerStart): String = CodeModeJson.codec.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("allowed", buildJsonArray { start.tools.sorted().forEach(::add) })
            put(
                "nested",
                buildJsonArray {
                    CodeModeManual.nestedNames(start.tools).forEach { name ->
                        add(
                            buildJsonObject {
                                put("name", name)
                                put("global", CodeModeManual.identifier(name))
                                put("description", start.descriptions[name].orEmpty())
                            },
                        )
                    }
                },
            )
        },
    )
}
