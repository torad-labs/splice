// NEW: map explicit client effort through a provider vocabulary without changing legacy budget tiers.
package splice.dialect.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.wire.AnthropicRequest

private const val EFFORT_FIELD = "effort"

/** A provider-opted vocabulary. Explicit client effort wins over the thinking switch and default. */
public class ChatEffortVocabulary(
    private val default: String,
    levels: Map<String, String>,
    private val modelPattern: Regex? = null,
) {
    private val levels = levels.mapKeys { it.key.trim().lowercase() }

    internal fun effort(raw: JsonObject?, body: AnthropicRequest, upstreamModel: String): String? {
        if (modelPattern?.containsMatchIn(upstreamModel) == false) return null
        val explicit = sequenceOf(
            (raw?.get("output_config") as? JsonObject)?.get(EFFORT_FIELD),
            raw?.get(EFFORT_FIELD),
            raw?.get("reasoning_effort"),
            (raw?.get("metadata") as? JsonObject)?.get(EFFORT_FIELD),
            (raw?.get("reasoning") as? JsonObject)?.get(EFFORT_FIELD),
        ).filterIsInstance<JsonPrimitive>().firstOrNull { it.isString }?.content
        return when {
            explicit != null -> mapped(explicit)
            body.thinking?.disabled == true -> levels["none"]
            else -> default
        }
    }

    private fun mapped(effort: String): String = levels[effort.trim().lowercase()] ?: default
}
