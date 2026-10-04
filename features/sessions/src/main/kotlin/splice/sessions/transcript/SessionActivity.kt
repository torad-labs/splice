// NEW: V4-444 — the compact, redacted transcript projection for session cards.
package splice.sessions.transcript

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

// why: a card shows one activity line, never a transcript body.
private const val ACTIVITY_TEXT_CHARS = 160

internal object SessionActivity {
    private val whitespace = Regex("[\\s\\p{Z}]+")

    fun last(
        sessionId: String?,
        cwd: String?,
        roots: List<Path>,
        source: SessionTranscripts,
        viewEnabled: SessionTranscriptViewEnabled,
    ): JsonElement {
        if (!viewEnabled()) return JsonNull
        return json(sessionId?.let { source.last(it, roots, cwd) })
    }

    /** A call is shown as what it was for, read from its whole input before any clip, so a long command in front of its
     *  description cannot push the description out; what was said is shown as it was said. */
    private fun shown(message: TranscriptMessage): String {
        val tool = message.tool
        if (message.role != TranscriptRole.ASSISTANT || tool == null) return message.text
        return CallSummary.of(tool, message.text)
    }

    /** The port supplies redacted text; collapse and clip only after that redaction. */
    private fun json(message: TranscriptMessage?): JsonElement {
        if (message == null) return JsonNull
        return buildJsonObject {
            put("role", message.role.name.lowercase())
            put("tool", message.tool)
            put("text", shown(message).replace(whitespace, " ").trim().take(ACTIVITY_TEXT_CHARS))
            put("ts", message.ts)
            val asked = message.role == TranscriptRole.ASSISTANT
            val asks = if (asked) AskedQuestions.of(message.tool, message.text) else null
            if (asks != null) put("asks", asks)
        }
    }
}
