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
import splice.client.transcript.ASSISTANT_TYPE
import splice.client.transcript.CONTENT
import splice.client.transcript.THINKING_STAND_IN
import splice.core.util.Cancellables
import splice.core.util.JsonScalars

private const val TRANSCRIPT_TYPE = "type"
private const val TRANSCRIPT_MESSAGE = "message"

/** Whether a row on this model stays where it is. */
internal fun interface KeptModel {
    operator fun invoke(model: String?): Boolean
}

/** Which models are Claude's own. Discovery IDs use a head's double-hyphen namespace, not the native Claude model
 *  namespace. */
internal object NativeClaude {
    private const val ID_PREFIX = "claude-"

    fun isModel(model: String?): Boolean = model?.startsWith(ID_PREFIX) == true && "--" !in model
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

/** A row a move takes: its assistant row, and whether it holds thinking, which goes with its model. */
internal data class RowMove(val assistant: LineShape.Assistant, val strips: Boolean)

/** The per-line rule of the resume rewrite: what a line is, and how an assistant row is moved. */
internal class AssistantRowMove {
    private val json = Json { ignoreUnknownKeys = true }

    /** The blocks a signature rides on — the two Claude Code's own strip removes (aEt/Tcr). */
    private val thinkingTypes = setOf("thinking", "redacted_thinking")

    /** Claude Code's stand-in for a message its signature recovery leaves empty: kcr() in 2.1.281,
     *  xmr() in 2.1.282 and wwr() in 2.1.283, byte for byte. */
    private val thinkingRemoved =
        json.parseToJsonElement("""{"type":"text","text":"$THINKING_STAND_IN","citations":[]}""")

    /** The line as the move reads it. */
    fun read(line: String): LineShape =
        Cancellables.runCatchingCancellable { json.parseToJsonElement(line).jsonObject }
            .fold(onSuccess = { classify(it) }, onFailure = { LineShape.NotAnObject })

    /** The one decision a move makes about a line, shared by the survey that counts the moves and the write that
     *  applies them: null when the line stays exactly as it is (history, or an assistant row on a model [kept]
     *  serves), otherwise the row the move takes. */
    fun moveOf(line: LineShape, kept: KeptModel): RowMove? {
        if (line !is LineShape.Assistant) return null
        if (kept(JsonScalars.str(line.message, Keys.MODEL))) return null
        return RowMove(line, strips = holdsThinking(line.message))
    }

    /** The line rewritten under [policy], or null when the line stays exactly as it is. */
    fun rewritten(line: String, policy: RowPolicy): String? {
        val move = moveOf(read(line), policy.keeps) ?: return null
        val stripped = withoutThinking(move.assistant.message[CONTENT])
        if (policy.target == null && stripped == null) return null
        val message = JsonObject(
            move.assistant.message.toMutableMap().apply {
                policy.target?.let { put(Keys.MODEL, JsonPrimitive(it)) }
                stripped?.let { put(CONTENT, it) }
            },
        )
        return json.encodeToString(
            JsonObject.serializer(),
            JsonObject(move.assistant.row.toMutableMap().apply { put(TRANSCRIPT_MESSAGE, message) }),
        )
    }

    private fun classify(row: JsonObject): LineShape {
        val message = assistantMessage(row) ?: return LineShape.NotAssistant
        return LineShape.Assistant(row, message)
    }

    private fun assistantMessage(row: JsonObject): JsonObject? =
        if (JsonScalars.str(row, TRANSCRIPT_TYPE) == ASSISTANT_TYPE) row[TRANSCRIPT_MESSAGE] as? JsonObject else null

    /** Whether a content block is thinking, the kind a move strips. */
    private fun isThinking(block: JsonElement): Boolean =
        JsonScalars.str(block as? JsonObject, TRANSCRIPT_TYPE) in thinkingTypes

    /** Whether [message] holds thinking blocks a move would strip. */
    private fun holdsThinking(message: JsonObject): Boolean =
        (message[CONTENT] as? JsonArray)?.any { isThinking(it) } == true

    /** [content] without its thinking blocks, or null when it holds none (or is not a block list) and
     *  stays exactly as it is. Emptied, it becomes [thinkingRemoved]: the row is never dropped. */
    private fun withoutThinking(content: JsonElement?): JsonArray? {
        val blocks = content as? JsonArray ?: return null
        if (blocks.none { isThinking(it) }) return null
        return JsonArray(blocks.filterNot { isThinking(it) }.ifEmpty { listOf(thinkingRemoved) })
    }
}
