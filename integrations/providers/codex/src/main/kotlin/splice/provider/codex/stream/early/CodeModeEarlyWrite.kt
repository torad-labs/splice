// NEW: the Write a code-mode script opens with, read while the script still streams.
//
// Nov 3, 2026 (Marcos, choosing option 1, "like native claude code"): a script that writes one large file showed the
// client nothing until GPT finished it, five and a half minutes on the Mac session's 52 KB stylesheet, because the
// runtime only reads a script once it is whole. This reader finds the path and the file's text in the script as it
// streams, so the client's Write block can open at the path and fill as GPT writes, as a native Write does.
package splice.provider.codex.stream.early

import java.util.regex.Pattern

/** Reads a streamed script for the Write it starts with: the path once the script has written it, then the file's
 *  text as it arrives. Only a script whose first step is that Write, with a plain string literal for the path and the
 *  content, opens one, since only then is it the first call the runtime makes. The text it gives is the content's
 *  literal decoded as JavaScript does, up to the first part it cannot be sure of: an interpolation, or an escape
 *  whose meaning depends on the script's mode. */
internal class CodeModeEarlyWrite {
    sealed class Event {
        data class Opened(val path: String) : Event()
        data class Content(val text: String) : Event()
    }

    private enum class Phase { SEEKING, STREAMING, DONE }

    private var phase = Phase.SEEKING
    private val opening = StringBuilder()
    private var content: CodeModeLiteralDecoder? = null

    fun feed(chunk: String): List<Event> = when (phase) {
        Phase.SEEKING -> seek(chunk)
        Phase.STREAMING -> stream(chunk)
        Phase.DONE -> emptyList()
    }

    private fun seek(chunk: String): List<Event> {
        opening.append(chunk)
        val matcher = OPENING.matcher(opening)
        if (!matcher.lookingAt()) {
            if (!matcher.hitEnd() || opening.length > MAX_OPENING) give()
            return emptyList()
        }
        val path = whole(matcher.group(1)) ?: return give()
        content = CodeModeLiteralDecoder(matcher.group(2).single())
        phase = Phase.STREAMING
        val rest = opening.substring(matcher.end())
        opening.clear()
        return listOf(Event.Opened(path)) + stream(rest)
    }

    private fun stream(chunk: String): List<Event> {
        val decoder = checkNotNull(content)
        val text = decoder.feed(chunk)
        if (decoder.state != CodeModeLiteralDecoder.State.OPEN) phase = Phase.DONE
        return if (text.isEmpty()) emptyList() else listOf(Event.Content(text))
    }

    private fun give(): List<Event> {
        phase = Phase.DONE
        opening.clear()
        return emptyList()
    }

    /** A whole literal's value, or null when part of it cannot be read with certainty. */
    private fun whole(literal: String): String? {
        val decoder = CodeModeLiteralDecoder(literal.first())
        val value = decoder.feed(literal.substring(1))
        return value.takeIf { decoder.state == CodeModeLiteralDecoder.State.CLOSED }
    }
}

/** The opening a streamed Write must have: an optional text( wrapper, then the call, with the path's literal whole and
 *  the content's opening quote. */
private val OPENING: Pattern = Pattern.compile(
    "\\s*(?:text\\s*\\(\\s*)?await\\s+tools\\s*\\.\\s*Write\\s*\\(\\s*\\{\\s*" +
        "(?:file_path|\"file_path\"|'file_path')\\s*:\\s*" +
        "(\"(?:[^\"\\\\\\r\\n]|\\\\[\\s\\S])*\"|" +
        "'(?:[^'\\\\\\r\\n]|\\\\[\\s\\S])*'|" +
        "`(?:[^`\\\\$]|\\\\[\\s\\S]|\\$(?!\\{))*`)" +
        "\\s*,\\s*(?:content|\"content\"|'content')\\s*:\\s*([`\"'])",
)

/** Past this many characters without the content's opening, the script is not one this reader streams. */
private const val MAX_OPENING = 4096
