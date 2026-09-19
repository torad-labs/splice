// NEW: v0.4.0 FEATURES.md §7 — the custom compaction text on the responses wire, appended as one user
// input item after the client-built compaction input.
package splice.dialect.responses

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Appends one user input item after the client-built compaction input. Replacing only the input
 *  array's closing edge leaves every existing item byte-identical, including lite's leading
 *  developer item and the client's summarizer prompt. */
public class ResponsesCompactionTail {
    public fun append(request: JsonObject, text: String): JsonObject {
        if (text.isEmpty()) return request
        val input = request["input"] as? JsonArray ?: return request
        val fields = request.toMutableMap()
        fields["input"] = JsonArray(input + userItem(text))
        return JsonObject(fields)
    }

    private fun userItem(text: String): JsonObject = buildJsonObject {
        put("role", "user")
        put(
            "content",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "input_text")
                        put("text", text)
                    },
                )
            },
        )
    }
}
