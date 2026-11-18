// NEW: the one user input item splice adds to a Responses request of its own (the compaction tail, a capability's
// text), shared so both are sent in one shape.
package splice.dialect.responses.usertext

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A user message input item holding one input text. */
internal class ResponsesUserItem {
    fun of(text: String): JsonObject = buildJsonObject {
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
