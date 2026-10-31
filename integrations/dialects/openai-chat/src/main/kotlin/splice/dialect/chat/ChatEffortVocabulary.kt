// NEW: map explicit client effort through a provider vocabulary without changing legacy budget tiers.
package splice.dialect.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val EFFORT_FIELD = "effort"

/** A provider-opted vocabulary. Explicit client effort wins over the default. Thinking off changes neither
 *  (Marcos, 2026-10-27): the none level is reached only by a client that asks for none. */
public class ChatEffortVocabulary(
    private val default: String,
    private val levels: Map<String, String>,
    private val modelPattern: Regex? = null,
) {
    internal fun effort(raw: JsonObject?, upstreamModel: String): String? {
        if (modelPattern?.containsMatchIn(upstreamModel) == false) return null
        val explicit = sequenceOf(
            (raw?.get("output_config") as? JsonObject)?.get(EFFORT_FIELD),
            raw?.get(EFFORT_FIELD),
            raw?.get("reasoning_effort"),
            (raw?.get("metadata") as? JsonObject)?.get(EFFORT_FIELD),
            (raw?.get("reasoning") as? JsonObject)?.get(EFFORT_FIELD),
        ).filterIsInstance<JsonPrimitive>().firstOrNull { it.isString }?.content
        return explicit?.let(::mapped) ?: default
    }

    private fun mapped(effort: String): String = levels[effort.trim().lowercase()] ?: default
}
