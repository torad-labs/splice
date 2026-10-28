package splice.provider.codex.history.notification

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.dialect.responses.request.AssistantPhase
import splice.dialect.responses.request.ResponsesAssistantText
import splice.provider.codex.CodeModeRecord

/** Only counted, accepted progress after an existing primary result can extend authoritative model input. */
internal object CodeModeNotificationInputs {
    fun missing(input: List<JsonElement>, records: List<CodeModeRecord>): List<JsonElement> {
        val primary = input.mapNotNull { value ->
            val item = value as? JsonObject
            val result = JsonScalars.str(item, "type") == "custom_tool_call_output" &&
                JsonScalars.str(item, "name") != "exec"
            if (result) JsonScalars.str(item, "call_id") else null
        }.toSet()
        val expected = records.asSequence().distinctBy { it.id }.flatMap { it.progress.nativeNotifications }
            .filter { JsonScalars.str(it as? JsonObject, "call_id") in primary }.toList()
        if (expected.isEmpty()) return emptyList()
        val known = expected.toHashSet()
        val observed = input.filter { it in known }.groupingBy { it }.eachCount()
        val counted = mutableMapOf<JsonElement, Int>()
        return expected.filter { item ->
            val occurrence = (counted[item] ?: 0) + 1
            counted[item] = occurrence
            occurrence > (observed[item] ?: 0)
        }
    }

    fun echoes(items: List<JsonElement>): List<JsonElement> = items.map { value ->
        val item = value as JsonObject
        ResponsesAssistantText.item(checkNotNull(JsonScalars.str(item, "output")), AssistantPhase.COMMENTARY)
    }
}
