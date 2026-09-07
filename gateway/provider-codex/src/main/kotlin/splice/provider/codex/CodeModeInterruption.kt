// NEW: preserves exact completed callback evidence when interrupted execution cannot be resumed.
package splice.provider.codex

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal object CodeModeInterruption {
    fun output(record: CodeModeRecord, detail: String): String = buildJsonObject {
        put("status", "interrupted")
        put("sourceRerun", false)
        put("detail", detail)
        putJsonArray("results") {
            record.results.forEach { (id, result) ->
                add(
                    buildJsonObject {
                        put("id", id)
                        put("output", result.output)
                        put("isError", result.isError)
                    },
                )
            }
        }
        putJsonArray("unresolved") {
            record.pending.filter { it.clientId !in record.results }.forEach { pending ->
                add(
                    buildJsonObject {
                        put("id", pending.clientId)
                        put("name", pending.name)
                        put("status", if (pending.exposed) "result unavailable" else "not scheduled")
                    },
                )
            }
        }
    }.toString()
}
