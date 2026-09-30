// NEW: V4-446 — one canonical SHA-256 spelling for persisted code-mode input and preflight prefixes.
package splice.core.perf

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

private const val PREFIX_INPUT_FIELD = "input"

/** The same lowercase SHA-256/UTF-8 hex code-mode records have persisted since their creation. */
public object InputDigest {
    public fun hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    public fun capture(request: JsonObject): InputPrefix? {
        val input = request[PREFIX_INPUT_FIELD] as? JsonArray ?: return null
        val properties = JsonObject(request.filterKeys { it != PREFIX_INPUT_FIELD }).toString()
        return InputPrefix(
            count = input.size,
            inputDigest = hex(input.toString()),
            propertiesDigest = hex(properties),
        )
    }
}

/** No prompt bytes retained. Measured input anchors future growth only when the measured input
 * items are a byte-identical prefix and all non-input request properties still agree. */
public data class InputPrefix(
    val count: Int,
    val inputDigest: String,
    val propertiesDigest: String,
) {
    /** Only byte-identical measured history plus newly appended, text-only items can justify a
     * local refusal. Encoded media has no trustworthy token bound from its transport byte length. */
    public fun textGrowthBytes(request: JsonObject): Long? {
        val input = request[PREFIX_INPUT_FIELD] as? JsonArray
        if (input == null || input.size < count) return null
        val prefix = JsonArray(input.take(count)).toString()
        val properties = JsonObject(request.filterKeys { it != PREFIX_INPUT_FIELD }).toString()
        val appended = input.drop(count)
        val preserved = InputDigest.hex(prefix) == inputDigest && InputDigest.hex(properties) == propertiesDigest
        if (!preserved || appended.any { !textOnly(it) }) return null
        // A byte-level tokenizer cannot add more tokens than the new UTF-8 bytes. Count JSON
        // structure and the array separator as well as the text, but never old transport bytes.
        return if (appended.isEmpty()) {
            0L
        } else {
            val separator = if (count > 0) 1L else 0L
            JsonArray(appended).toString().toByteArray(Charsets.UTF_8).size.toLong() - 2L + separator
        }
    }

    private fun textOnly(item: JsonElement): Boolean {
        val entry = item as? JsonObject ?: return false
        return when ((entry["type"] as? JsonPrimitive)?.content) {
            "function_call_output" -> entry.keys.all { it in setOf("type", "call_id", "output") } &&
                (entry["output"] as? JsonPrimitive)?.isString == true
            null, "message" -> textualMessage(entry)
            else -> false
        }
    }

    private fun textualMessage(entry: JsonObject): Boolean {
        val phase = entry["phase"]
        val assistantPhase = phase == null || (
            (entry["role"] as? JsonPrimitive)?.content == "assistant" &&
                (phase as? JsonPrimitive)?.content in setOf("commentary", "final_answer")
            )
        return assistantPhase && entry.keys.all { it in setOf("type", "role", "content", "phase") } &&
            when (val content = entry["content"]) {
                is JsonPrimitive -> content.isString
                is JsonArray -> content.all(::textBlock)
                else -> false
            }
    }

    private fun textBlock(block: JsonElement): Boolean {
        val text = block as? JsonObject ?: return false
        return text.keys.all { it == "type" || it == "text" } &&
            (text["type"] as? JsonPrimitive)?.content in setOf("text", "input_text", "output_text") &&
            (text["text"] as? JsonPrimitive)?.isString == true
    }
}
