package splice.provider.codex.history

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars

/** Only the existing session/callback/result proof can authorize a cleared placeholder. */
internal object CodeModeClearedHistory {
    fun restore(
        received: List<JsonElement>,
        acknowledged: List<JsonElement>,
        verified: Set<String>,
    ): List<JsonElement> {
        if (verified.isEmpty()) return received
        val outputs = acknowledged.filterIsInstance<JsonObject>()
            .filter { JsonScalars.str(it, "type") == "function_call_output" }
            .groupBy { JsonScalars.str(it, "call_id") }
        return received.map { item ->
            val value = item as? JsonObject
            val id = value?.let { JsonScalars.str(it, "call_id") }
            val cleared = value != null && JsonScalars.str(value, "type") == "function_call_output" &&
                JsonScalars.str(value, "output") == "[Old tool result content cleared]"
            if (cleared && id in verified) outputs[id]?.singleOrNull() ?: item else item
        }
    }
}
