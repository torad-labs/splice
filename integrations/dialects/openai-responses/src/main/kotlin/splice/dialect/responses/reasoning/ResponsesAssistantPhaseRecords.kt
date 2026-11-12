package splice.dialect.responses.reasoning

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.perf.InputDigest
import splice.core.reasoning.ReasoningHistory
import splice.core.util.JsonScalars
import splice.core.wire.ToolUseBlock
import splice.dialect.responses.ResponsesTurnState
import splice.dialect.responses.request.AssistantPhase
import splice.upstream.ToolNameShortener

/** Private provenance, not a reasoning envelope or a client extension. No transcript text is stored. */
internal class ResponsesAssistantPhaseRecords {
    fun capture(
        initial: ReasoningHistory?,
        state: ResponsesTurnState,
        names: ToolNameShortener,
    ): Map<String, String>? {
        val native = state.nativeOutput.snapshot(state.finalResponse)
        val fullText = native.filter { JsonScalars.str(it, ITEM_TYPE) == NATIVE_MESSAGE_TYPE }
            .joinToString("", transform = ResponsesMessageText::text)
        val delivered = state.nativePrefix?.delivered?.phaseHistory(native)
        val deliveredText = delivered.orEmpty().filter { JsonScalars.str(it, ITEM_TYPE) == NATIVE_MESSAGE_TYPE }
            .joinToString("", transform = ResponsesMessageText::text)
        val expected = state.textBuf.toString()
        val source = when {
            fullText == expected -> native to fullText
            delivered != null && deliveredText == expected -> delivered to deliveredText
            else -> null
        }
        return source?.let { (items, text) -> capture(initial, items, text, names) }
    }

    fun capture(
        initial: ReasoningHistory?,
        items: List<JsonObject>,
        delivered: String,
        names: ToolNameShortener,
    ): Map<String, String> {
        if (initial == null) return emptyMap()
        val text = items.filter { JsonScalars.str(it, ITEM_TYPE) == NATIVE_MESSAGE_TYPE }
            .joinToString("", transform = ResponsesMessageText::text)
        if (text != delivered) return emptyMap()
        val cursor = ResponsesHistoryCursor(initial.copy())
        val result = linkedMapOf<String, String>()
        for (item in items) {
            if (!captureItem(cursor, item, names, result)) break
        }
        return result
    }

    private fun captureItem(
        cursor: ResponsesHistoryCursor,
        item: JsonObject,
        names: ToolNameShortener,
        result: MutableMap<String, String>,
    ): Boolean = when (JsonScalars.str(item, ITEM_TYPE)) {
        NATIVE_MESSAGE_TYPE -> {
            captureMessage(cursor, item, result)
            true
        }
        "function_call" -> advanceCall(cursor, item, names)
        "reasoning" -> true
        else -> false
    }

    private fun captureMessage(
        cursor: ResponsesHistoryCursor,
        item: JsonObject,
        result: MutableMap<String, String>,
    ) {
        val start = cursor.history.offset
        val before = cursor.history.key
        val content = ResponsesMessageText.text(item)
        cursor.text("assistant", content)
        if (content.isEmpty()) return
        val phase = AssistantPhase.entries.firstOrNull { it.wire == JsonScalars.str(item, "phase") } ?: return
        val record = AssistantPhaseRecord(
            start,
            cursor.history.offset,
            before,
            cursor.history.key,
            phase.wire,
        )
        val value = PHASE_RECORD_PREFIX + Json.encodeToString(AssistantPhaseRecord.serializer(), record)
        result[PHASE_RECORD_PREFIX + InputDigest.hex(JsonPrimitive(value))] = value
    }

    private fun advanceCall(
        cursor: ResponsesHistoryCursor,
        item: JsonObject,
        names: ToolNameShortener,
    ): Boolean {
        val id = JsonScalars.str(item, "call_id")
        val arguments = JsonScalars.str(item, "arguments")
        if (id == null || arguments == null) return false
        val input = JsonScalars.objectOrNull(Json, arguments) ?: return false
        val name = names.restore(JsonScalars.str(item, "name").orEmpty())
        cursor.append("assistant", ToolUseBlock(id, name, input))
        return true
    }

    fun read(history: Map<String, List<String>>): List<AssistantPhaseRecord> =
        history.values.flatten().distinct().mapNotNull(::decode)

    fun reasoningOnly(history: Map<String, List<String>>): Map<String, List<String>> =
        history.mapValues { (_, values) -> values.filterNot { it.startsWith(PHASE_RECORD_PREFIX) } }
            .filterValues { it.isNotEmpty() }

    /** Reasoning and phase evidence can share a boundary without either replacing the other. */
    fun history(
        reasoning: Map<String, List<String>>,
        phases: Map<String, List<String>>,
    ): Map<String, List<String>> {
        val result = reasoning.toMutableMap()
        phases.values.flatten().distinct().forEach { value ->
            decode(value)?.let { record -> result[record.after] = result[record.after].orEmpty() + value }
        }
        return result
    }

    private fun decode(value: String): AssistantPhaseRecord? {
        if (!value.startsWith(PHASE_RECORD_PREFIX)) return null
        return try {
            Json.decodeFromString(AssistantPhaseRecord.serializer(), value.removePrefix(PHASE_RECORD_PREFIX))
                .takeIf { record ->
                    record.start >= 0 && record.end > record.start &&
                        AssistantPhase.entries.any { it.wire == record.phase }
                }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

@Serializable
internal data class AssistantPhaseRecord(
    val start: Long,
    val end: Long,
    val before: String,
    val after: String,
    val phase: String,
)

private const val ITEM_TYPE = "type"
private const val PHASE_RECORD_PREFIX = "splice-assistant-phase-v1:"
