// NEW: V4-335 — the one author of the assistant text item a lite turn replays. The request builder
// and code mode's continuity record each built it, and code mode compares the two by value: the day
// the builder's gained a phase, every finished script's preface stopped matching its record, was
// placed twice, and read as new client content that stopped the next script (CI run 36279360319).
package splice.dialect.responses.request

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The Responses role of an assistant message: the same word the Anthropic wire uses. */
internal const val ROLE_ASSISTANT: String = "assistant"

/** Where an assistant's text sits in its message (OpenAI's easy-input `phase`): said on the way to a
 *  tool call, or the answer. "Missing or dropped phase can cause preambles to be treated as final
 *  answers" (OpenAI's reasoning guide). */
public enum class AssistantPhase(public val wire: String) {
    COMMENTARY("commentary"),
    FINAL_ANSWER("final_answer"),
}

public object ResponsesAssistantText {
    /** role, then [phase] when there is one, then content. The key order is part of the item: code
     *  mode digests what it records. A non-lite turn passes null and gets the item it always had. */
    public fun item(text: String, phase: AssistantPhase?): JsonObject = buildJsonObject {
        put("role", ROLE_ASSISTANT)
        if (phase != null) put("phase", phase.wire)
        put("content", text)
    }
}
