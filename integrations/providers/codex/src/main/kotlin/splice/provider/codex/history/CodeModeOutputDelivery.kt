package splice.provider.codex.history

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars
import splice.dialect.responses.request.AssistantPhase
import splice.dialect.responses.request.ResponsesAssistantText

/** The client acknowledges display text; only the recorded native output supplies the model's next input. */
internal object CodeModeOutputDelivery {
    fun items(output: List<JsonObject>): List<JsonElement> = output.mapNotNull { item ->
        if (JsonScalars.str(item, "role") != "assistant") return@mapNotNull null
        val text = (item["content"] as? JsonPrimitive)?.takeIf {
            it.isString
        }?.let(JsonScalars::str) ?: (item["content"] as? JsonArray)
            ?.mapNotNull { part -> (part as? JsonObject)?.let { JsonScalars.str(it, "text") } }?.joinToString("")
            ?: return@mapNotNull null
        val phase = AssistantPhase.entries.firstOrNull { it.wire == JsonScalars.str(item, "phase") }
        ResponsesAssistantText.item(text, phase)
    }
}
