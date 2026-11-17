// NEW: v0.4.0 FEATURES.md §7 — the custom compaction text on the responses wire, appended as one user
// input item after the client-built compaction input.
package splice.dialect.responses.request

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Appends one user input item after the client-built compaction input. Replacing only the input
 *  array's closing edge leaves every existing item byte-identical, including lite's leading
 *  developer item and the client's summarizer prompt. */
internal class ResponsesCompactionTail {
    private val userItem = ResponsesUserItem()

    public fun append(request: JsonObject, text: String): JsonObject {
        if (text.isEmpty()) return request
        val input = request["input"] as? JsonArray ?: return request
        val fields = request.toMutableMap()
        fields["input"] = JsonArray(input + userItem.of(text))
        return JsonObject(fields)
    }
}
