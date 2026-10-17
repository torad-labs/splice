package splice.client.transcript.capture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.client.transcript.PER_RECORD
import splice.client.transcript.TranscriptRedaction
import splice.core.util.JsonScalars
import splice.sessions.transcript.TranscriptMessage
import splice.sessions.transcript.TranscriptRole
import splice.sessions.transcript.TranscriptToolUse

/** Projects only one complete captured API message at a time, using the existing credential masking. */
internal class CapturedMessageReader {
    private val redaction = TranscriptRedaction()

    fun read(body: String, span: CaptureSpan): List<TranscriptMessage> {
        return decoded(body.substring(span.start, span.end), span.start)
    }

    fun read(input: CapturedText, span: CaptureSpan): List<TranscriptMessage> {
        input.seek(span.start)
        val body = StringBuilder(span.end - span.start)
        while (input.position < span.end) body.append(input.take())
        return decoded(body.toString(), span.start)
    }

    private fun decoded(body: String, start: Int): List<TranscriptMessage> {
        val message = Json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        val out = mutableListOf<TranscriptMessage>()
        add(out, message, start.toLong() * PER_RECORD)
        val id = JsonScalars.str(message, "id")?.takeIf { JsonScalars.str(message, "role") == "assistant" }
        return if (id == null) out else out.map { it.copy(messageId = id) }
    }

    private fun add(out: MutableList<TranscriptMessage>, msg: JsonObject, first: Long) {
        val role = if (JsonScalars.str(msg, "role") == "assistant") TranscriptRole.ASSISTANT else TranscriptRole.USER
        val content = msg["content"]
        if (content !is JsonArray) {
            val text = JsonScalars.str(content) ?: return
            out += TranscriptMessage(first, role, null, redaction.shown(text))
            return
        }
        for (element in content) {
            val block = element as? JsonObject ?: continue
            blockOf(first + out.size, role, block)?.let { out += it }
        }
    }

    private fun blockOf(index: Long, role: TranscriptRole, block: JsonObject): TranscriptMessage? =
        when (JsonScalars.str(block, "type")) {
            "text" -> JsonScalars.str(block, "text")?.let { TranscriptMessage(index, role, null, redaction.shown(it)) }
            "tool_use" -> TranscriptMessage(
                index,
                TranscriptRole.ASSISTANT,
                null,
                redaction.shown(block["input"]?.toString().orEmpty()),
                TranscriptToolUse(JsonScalars.str(block, "name"), false, JsonScalars.str(block, "id")),
            )
            "tool_result" -> TranscriptMessage(
                index,
                TranscriptRole.TOOL,
                null,
                redaction.shown(resultText(block["content"])),
                TranscriptToolUse(null, true, JsonScalars.str(block, "tool_use_id")),
            )
            else -> null
        }

    private fun resultText(content: JsonElement?): String = if (content is JsonArray) {
        content.mapNotNull { JsonScalars.str(it as? JsonObject, "text") }.joinToString("\n")
    } else {
        JsonScalars.str(content).orEmpty()
    }
}
