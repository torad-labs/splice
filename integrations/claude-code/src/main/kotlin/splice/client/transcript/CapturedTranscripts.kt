// NEW: Sessions' transcript fallback to the captured request (gap 423; web-console.md:557, capture feeds the transcript
// view). When no transcript file holds a session, the newest request splice's trace kept for it carries the whole
// conversation up to that turn, as the client sent it: its messages are drawn as the page, the same text, tool calls
// and tool results the file would have given, with the same credential masking. Only the first page from either end
// falls back; a cursor into a file is never answered from a capture.
package splice.client.transcript

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.sessions.transcript.SessionTranscripts
import splice.sessions.transcript.TranscriptLookup
import splice.sessions.transcript.TranscriptMessage
import splice.sessions.transcript.TranscriptPage
import splice.sessions.transcript.TranscriptRole
import splice.sessions.transcript.TranscriptToolUse
import java.nio.file.Path

/** What the page names in place of a file path: the trace turn the conversation was read from. */
private const val CAPTURE_PATH = "captured by splice's trace, turn"

/** One captured request: the turn it was sent on and the body exactly as the client sent it. */
public data class CapturedRequest(val turnId: String, val body: String)

/** The newest captured request of a session, or null when the trace keeps none. */
public fun interface CapturedSource {
    public fun newest(sessionId: String): CapturedRequest?
}

public class CapturedTranscripts(
    private val delegate: SessionTranscripts,
    private val captured: CapturedSource,
) : SessionTranscripts by delegate {
    private val redaction = TranscriptRedaction()

    override fun page(sessionId: String, roots: List<Path>, cursor: String?, limit: Int): TranscriptLookup {
        val read = delegate.page(sessionId, roots, cursor, limit)
        return if (read == TranscriptLookup.Missing && cursor == null) fromCapture(sessionId) ?: read else read
    }

    override fun pageBefore(sessionId: String, roots: List<Path>, before: String?, limit: Int): TranscriptLookup {
        val read = delegate.pageBefore(sessionId, roots, before, limit)
        val fromEnd = before == null || before == "end"
        return if (read == TranscriptLookup.Missing && fromEnd) fromCapture(sessionId) ?: read else read
    }

    private fun fromCapture(sessionId: String): TranscriptLookup? {
        val sent = Cancellables.runCatchingBestEffort { captured.newest(sessionId) }.getOrNull() ?: return null
        val messages = Cancellables.runCatchingBestEffort { messagesOf(sent.body) }.getOrNull()
        if (messages.isNullOrEmpty()) return null
        return TranscriptLookup.Found(
            TranscriptPage(sessionId, "$CAPTURE_PATH ${sent.turnId}", messages, next = null, skipped = emptyMap()),
        )
    }

    private fun messagesOf(body: String): List<TranscriptMessage> {
        val sent = Json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        val out = mutableListOf<TranscriptMessage>()
        (sent["messages"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { add(out, it) }
        return out
    }

    /** One sent message's blocks, or its plain string content, appended to [out] in order. */
    private fun add(out: MutableList<TranscriptMessage>, msg: JsonObject) {
        val assistant = JsonScalars.str(msg, "role") == "assistant"
        val role = if (assistant) TranscriptRole.ASSISTANT else TranscriptRole.USER
        val content = msg["content"]
        if (content !is JsonArray) {
            val text = JsonScalars.str(content) ?: return
            out += TranscriptMessage(out.size.toLong(), role, null, redaction.shown(text))
            return
        }
        for (block in content.mapNotNull { it as? JsonObject }) {
            blockOf(out.size.toLong(), role, block)?.let { out += it }
        }
    }

    /** A text block is the message's text; a tool call carries its input as text, a tool result its content. */
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

    private fun resultText(content: JsonElement?): String = when (content) {
        is JsonArray -> content.mapNotNull { JsonScalars.str(it as? JsonObject, "text") }.joinToString("\n")
        else -> JsonScalars.str(content).orEmpty()
    }
}
