// NEW: Oct 7 CT — the per-line half of the resume rewrite, split out of TranscriptModelRewrite so that the file
// transaction (survey, preservation, staging, the swap) and the rule for one line each stay small enough to read.
// A line of a transcript is read here, and an assistant row on a model the head does not serve is moved here:
// onto the pinned model, without its thinking (see TranscriptModelRewrite's header). Nothing here touches a file.
package splice.client.resume

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import splice.client.Keys
import splice.client.transcript.CONTENT
import splice.core.util.Cancellables
import splice.core.util.JsonScalars

private const val TRANSCRIPT_TYPE = "type"
private const val TRANSCRIPT_MESSAGE = "message"
private const val ASSISTANT_TYPE = "assistant"

/** Whether a row on this model stays where it is. */
internal fun interface KeptModel {
    operator fun invoke(model: String?): Boolean
}

/** Which rows stay ([keeps]) and the model a moved row takes ([target]; null keeps the row's own). */
internal data class RowPolicy(val target: String?, val keeps: KeptModel)

/** One line of a transcript as the move reads it. Every line that is not an assistant row on a model to move is
 *  history: it is kept verbatim, never moved and never dropped, whether or not it parses. */
internal sealed class LineShape {
    /** An assistant row: the object it is, and the `message` it carries. */
    class Assistant(val row: JsonObject, val message: JsonObject) : LineShape()

    /** A JSON object that is not an assistant row. */
    object NotAssistant : LineShape()

    /** A line that is not one JSON object at all. */
    object NotAnObject : LineShape()
}

/** The per-line rule of the resume rewrite: what a line is, and how an assistant row is moved. */
internal class AssistantRowMove {
    private val json = Json { ignoreUnknownKeys = true }

    /** The blocks a signature rides on — the two Claude Code's own strip removes (aEt/Tcr). */
    private val thinkingTypes = setOf("thinking", "redacted_thinking")

    /** Claude Code's stand-in for a message its signature recovery leaves empty: kcr() in 2.1.281,
     *  xmr() in 2.1.282 and wwr() in 2.1.283, byte for byte. */
    private val thinkingRemoved =
        json.parseToJsonElement("""{"type":"text","text":"[Thinking removed]","citations":[]}""")

    /** The line as the move reads it. */
    fun read(line: String): LineShape =
        Cancellables.runCatchingCancellable { json.parseToJsonElement(line).jsonObject }
            .fold(onSuccess = { classify(it) }, onFailure = { LineShape.NotAnObject })

    /** Whether [message] holds thinking blocks a move would strip. */
    fun holdsThinking(message: JsonObject): Boolean = withoutThinking(message[CONTENT]) != null

    /** The line rewritten under [policy], or null when the line stays exactly as it is. */
    fun rewritten(line: String, policy: RowPolicy): String? {
        val assistant = when (val parsed = read(line)) {
            is LineShape.Assistant -> parsed
            LineShape.NotAssistant, LineShape.NotAnObject -> return null
        }
        val fixedMessage = moved(assistant.message, policy) ?: return null
        return json.encodeToString(
            JsonObject.serializer(),
            JsonObject(assistant.row.toMutableMap().apply { put(TRANSCRIPT_MESSAGE, fixedMessage) }),
        )
    }

    private fun classify(row: JsonObject): LineShape {
        val message = assistantMessage(row) ?: return LineShape.NotAssistant
        return LineShape.Assistant(row, message)
    }

    private fun assistantMessage(row: JsonObject): JsonObject? =
        if (JsonScalars.str(row, TRANSCRIPT_TYPE) == ASSISTANT_TYPE) row[TRANSCRIPT_MESSAGE] as? JsonObject else null

    /** [message] moved under [policy]: onto its target model, without its thinking. Null when it stays as it is. */
    private fun moved(message: JsonObject, policy: RowPolicy): JsonObject? {
        if (policy.keeps(JsonScalars.str(message, Keys.MODEL))) return null
        val stripped = withoutThinking(message[CONTENT])
        if (policy.target == null && stripped == null) return null
        return JsonObject(
            message.toMutableMap().apply {
                policy.target?.let { put(Keys.MODEL, JsonPrimitive(it)) }
                stripped?.let { put(CONTENT, it) }
            },
        )
    }

    /** [content] without its thinking blocks, or null when it holds none (or is not a block list) and
     *  stays exactly as it is. Emptied, it becomes [thinkingRemoved]: the row is never dropped. */
    private fun withoutThinking(content: JsonElement?): JsonArray? {
        val blocks = content as? JsonArray ?: return null
        val kept = blocks.filterNot { block -> JsonScalars.str(block as? JsonObject, TRANSCRIPT_TYPE) in thinkingTypes }
        if (kept.size == blocks.size) return null
        return JsonArray(kept.ifEmpty { listOf(thinkingRemoved) })
    }
}
