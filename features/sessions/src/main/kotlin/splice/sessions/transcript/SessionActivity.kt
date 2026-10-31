// NEW: V4-444 — the compact, redacted transcript projection for session cards.
package splice.sessions.transcript

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

// why: a card shows one activity line, never a transcript body.
private const val ACTIVITY_TEXT_CHARS = 160

internal object SessionActivity {
    private val whitespace = Regex("[\\s\\p{Z}]+")

    // Markdown the card has no place to draw (splice-lead, Oct 11): a heading, bullet or quote mark at a line's start,
    // a link's target, and the marks around bold, italic and code. The words between the marks stay. A fenced code
    // block is code, never prose: the fence and all inside it leave the line, an unclosed one to the end (splice-lead,
    // Oct 11: "the new daemon is healthy. open…" read a block's first word as the sentence's last), and so does the
    // sentence that led into it with a colon, which would point at nothing ("From the repo root, type: It needs…").
    private val fenced = Regex("(?ms)^[ \\t]*(```|~~~).*?(?:^[ \\t]*\\1[^\\n]*$|\\z)")
    private val sentenceEnd = Regex("[.!?](?=\\s)|\\n")
    private val lineMarks = Regex("(?m)^[ \\t]*(?:#{1,6}[ \\t]+|[-*+][ \\t]+|>[ \\t]+)+")
    private val itemMark = Regex("^[ \\t]*(?:#{1,6}|[-*+])[ \\t]+\\S")
    private val tableRule = Regex("\\|[ \\t:|-]*-[ \\t:|-]*")
    private val link = Regex("\\[([^\\]\\n]+)]\\([^)\\s]+\\)")
    private val strong = Regex("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1")
    private val starred = Regex("(?<![*\\w])\\*(?=\\S)(.+?)(?<=\\S)\\*(?![*\\w])")
    private val underscored = Regex("(?<![_\\w])_(?=\\S)(.+?)(?<=\\S)_(?![_\\w])")

    // what a line cut short never ends on before its ellipsis, so it reads "fin…", never "fin.…" or "fin,…"
    private fun trailing(c: Char): Boolean =
        c.isWhitespace() || c in ".,;:!?" || Character.getType(c) == Character.DASH_PUNCTUATION.toInt()

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

    /** The model that wrote the session's newest assistant message, or the one a move since then put it on; JSON
     *  null when the view is off or no model is recorded. */
    fun model(
        sessionId: String?,
        cwd: String?,
        roots: List<Path>,
        source: SessionTranscripts,
        viewEnabled: SessionTranscriptViewEnabled,
    ): JsonElement {
        if (!viewEnabled()) return JsonNull
        val tail = sessionId?.let { source.tail(it, roots, cwd) }
        // a move with no reply since names the model the session goes on with: the newest reply is still the old one.
        // Judged against the reply that NAMED the model, never the newest message: a line Claude Code wrote itself
        // after a move, such as an API error, carries no model and must not date the move away (Marlin, move check 5)
        val moved = sessionId?.let { source.moves(it).lastOrNull() }
            ?.takeIf { it.model.isNotBlank() && it.movedAt > (tail?.modelAt ?: tail?.answered?.ts ?: 0L) }
        val model = moved?.model ?: tail?.model ?: return JsonNull
        return JsonPrimitive(model)
    }

    /** What an idle session last said: its newest message that is not the person's, so a message of his that was taken
     *  back, or never answered, does not stand in for the reply before it. Falls back to [last] when there is none. */
    fun answered(
        sessionId: String?,
        cwd: String?,
        roots: List<Path>,
        source: SessionTranscripts,
        viewEnabled: SessionTranscriptViewEnabled,
    ): JsonElement {
        if (!viewEnabled()) return JsonNull
        val reply = sessionId?.let { source.tail(it, roots, cwd)?.answered ?: source.last(it, roots, cwd) }
        return json(reply)
    }

    /** A call is shown as what it was for, read from its whole input before any clip, so a long command in front of its
     *  description cannot push the description out; what was said is shown as it was said. */
    private fun shown(message: TranscriptMessage): String {
        val tool = message.toolUse.name
        if (message.role != TranscriptRole.ASSISTANT || tool == null) return plain(message.text)
        return CallSummary.of(tool, message.text)
    }

    /** What was said, with its markdown read rather than shown: a call's summary keeps its marks, where a star can be a
     *  glob. */
    private fun plain(said: String): String {
        val unmarked = unfenced(said).lines().mapNotNull(::untabled)
            .joinToString("\n", transform = ::stopped)
            .replace(lineMarks, "").replace(link, "$1").replace("`", "")
        return unmarked.replace(strong, "$2").replace(starred, "$1").replace(underscored, "$1")
    }

    /** What was said with its fenced code blocks gone, each with the sentence that introduced it with a colon. */
    private fun unfenced(said: String): String = buildString {
        var from = 0
        for (block in fenced.findAll(said)) {
            append(unled(said.substring(from, block.range.first)))
            from = block.range.last + 1
        }
        append(said.substring(from))
    }

    /** The prose before a dropped block, less its last sentence when that sentence ends in a colon. */
    private fun unled(before: String): String {
        val text = before.trimEnd()
        if (!text.endsWith(":")) return before
        val start = sentenceEnd.findAll(text).lastOrNull()?.let { it.range.last + 1 } ?: 0
        return text.substring(0, start) + "\n"
    }

    /** A table row reads as its cells, comma-joined, as one sentence; the row under a table's header that only draws
     *  its rule is dropped. Any other line is kept as it is. */
    private fun untabled(line: String): String? {
        val text = line.trim()
        if (!text.startsWith("|")) return line
        if (tableRule.matches(text)) return null
        val cells = text.split("|").map(String::trim).filter(String::isNotEmpty).joinToString(", ")
        return if (cells.isEmpty() || cells.last() in ".!?") cells else "$cells."
    }

    /** A heading or bullet is its own sentence once its marks are gone: with no stop of its own it gets one, so it
     *  never runs into the next line ("Done The fix.", splice-lead, Oct 11). */
    private fun stopped(line: String): String {
        val text = line.trimEnd()
        if (!itemMark.containsMatchIn(text)) return line
        val last = text.trimEnd('*', '_', '`').last()
        return if (last in ".!?:;,\u2026") line else "$text."
    }

    /** A line past the cap ends on its last whole word and an ellipsis, so a card never shows a word cut in half
     *  (splice-lead, Oct 11: "If fin give"), and on no stop or comma before it; one word longer than the cap is cut,
     *  and says so with the ellipsis. */
    private fun clipped(line: String): String {
        if (line.length <= ACTIVITY_TEXT_CHARS) return line
        val room = line.take(ACTIVITY_TEXT_CHARS - 1)
        val word = if (line[room.length] == ' ') room else room.substringBeforeLast(' ', "")
        return (word.trimEnd(::trailing).ifEmpty { room }) + "\u2026"
    }

    /** The port supplies redacted text; collapse and clip only after that redaction. */
    private fun json(message: TranscriptMessage?): JsonElement {
        if (message == null) return JsonNull
        val text = clipped(shown(message).replace(whitespace, " ").trim())
        // a message with nothing left to show, whether it was only code or empty, leaves no line, as a transcript
        // of only notifications does
        if (text.isEmpty() && message.toolUse.name == null) return JsonNull
        return buildJsonObject {
            put("tool", message.toolUse.name)
            put("text", text)
            val asked = message.role == TranscriptRole.ASSISTANT
            val asks = if (asked) AskedQuestions.of(message.toolUse.name, message.text) else null
            if (asks != null) put("asks", asks)
        }
    }
}
