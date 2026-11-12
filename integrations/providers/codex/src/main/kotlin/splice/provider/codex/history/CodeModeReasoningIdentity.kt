// NEW: source reasoning keeps one identity across native receipts and legacy envelope projections.
package splice.provider.codex.history

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.reasoning.ReasoningReplay
import splice.core.util.JsonScalars

internal object CodeModeReasoningIdentity {
    fun key(item: JsonElement): JsonElement {
        val id = id(item) ?: return item
        return JsonObject(mapOf("type" to JsonPrimitive("reasoning"), "id" to JsonPrimitive(id)))
    }

    fun id(item: JsonElement): String? = (item as? JsonObject)
        ?.takeIf { JsonScalars.str(it, "type") == "reasoning" }
        ?.let { JsonScalars.strIfString(it["id"]) }?.takeIf(String::isNotEmpty)

    fun same(first: JsonElement, second: JsonElement): Boolean = key(first) == key(second)

    /** The source receipt wins unless it is exactly the legacy codec's projection of the received native item. */
    fun preferred(source: JsonElement, received: JsonElement): JsonElement {
        if (source == received) return received
        if (!same(source, received)) return source
        return if (normalized(source) == source && normalized(received) == source) received else source
    }

    private fun normalized(item: JsonElement): JsonObject? {
        val envelope = (item as? JsonObject)?.let(ReasoningReplay::encodeReasoningEnvelope)
        return ReasoningReplay.decodeReasoningEnvelope(envelope)
    }
}
