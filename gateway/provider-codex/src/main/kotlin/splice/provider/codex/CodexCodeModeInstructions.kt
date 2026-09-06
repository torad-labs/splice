// NEW: appends bundled orchestration guidance without shifting Lite history or overriding client parallel policy.
package splice.provider.codex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Extends the existing Lite instruction item so logical replay offsets do not move. */
internal object CodexCodeModeInstructions {
    private val guidance = checkNotNull(javaClass.getResourceAsStream("code-mode-orchestration.txt")) {
        "missing bundled code-mode orchestration instructions"
    }.bufferedReader(Charsets.UTF_8).use { it.readText().trimEnd() }

    private val sequentialGuidance = guidance.replace(
        "Group independent read-only calls inside one cell using Promise.all.",
        "The client has disabled parallel tool use; use sequential await for every tools.call.",
    )

    fun append(request: JsonObject, disableParallel: Boolean): JsonObject {
        val input = request.getValue("input") as JsonArray
        val instruction = input[1] as JsonObject
        require(instruction["role"] == JsonPrimitive("developer")) {
            "code mode requires the existing Responses lite developer instruction item"
        }
        val original = instruction.getValue("content") as JsonPrimitive
        require(original.isString) { "Responses lite instructions must be a string" }
        val section = if (disableParallel) sequentialGuidance else guidance
        val extended = JsonObject(instruction + ("content" to JsonPrimitive(original.content + "\n\n" + section)))
        val augmented = input.mapIndexed { index, item -> if (index == 1) extended else item }
        return JsonObject(request + ("input" to JsonArray(augmented)))
    }
}
