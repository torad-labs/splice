// NEW: map explicit client effort through a provider vocabulary without changing legacy budget tiers.
package splice.dialect.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
    /** A later message's output_config is the effort for this turn, ahead of the request's own fields: Claude Code
     *  carries a mid-session /effort there, as ChatEffortTiers and the Responses adapter read it. */
    internal fun effort(raw: JsonObject?, upstreamModel: String): String? {
        if (modelPattern?.containsMatchIn(upstreamModel) == false) return null
        val fromMessages = (raw?.get("messages") as? JsonArray)?.asReversed()?.firstNotNullOfOrNull {
            ((it as? JsonObject)?.get("output_config") as? JsonObject)?.get(EFFORT_FIELD)?.let(::named)
        }
        val explicit = fromMessages ?: sequenceOf(
            (raw?.get("output_config") as? JsonObject)?.get(EFFORT_FIELD),
            raw?.get(EFFORT_FIELD),
            raw?.get("reasoning_effort"),
            (raw?.get("metadata") as? JsonObject)?.get(EFFORT_FIELD),
            (raw?.get("reasoning") as? JsonObject)?.get(EFFORT_FIELD),
        ).firstNotNullOfOrNull { it?.let(::named) }
        return explicit?.let(::mapped) ?: default
    }

    /** The effort [element] names, when it is a string. */
    private fun named(element: JsonElement): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun mapped(effort: String): String = levels[effort.trim().lowercase()] ?: default
}
