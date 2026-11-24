package splice.provider.antigravity.skills

import splice.core.util.LogSafe
import splice.core.util.LogSink
import java.nio.file.Path

internal class SkillFrontmatter(private val log: LogSink) {
    private val markdown = SkillMarkdown()

    /** The command or skill in [file], found at [place]: a `user-invocable: false` one stays (only the model may start
     *  it), and so does a `disable-model-invocation: true` one (only the person may). */
    fun readItem(place: SkillPlace, fallbackName: String): LocalSkillItem {
        val file = place.file
        val document = markdown.read(file)
        val front = document.frontmatter
        return LocalSkillItem(
            name = SkillName.valid(scalar(front, "name")) ?: fallbackName,
            description = scalar(front, "description").ifBlank { bodyDescription(document.body) },
            hint = scalar(front, "argument-hint"),
            body = body(document, file),
            place = place,
            use = SkillUse(
                byUser = !scalar(front, "user-invocable").equals("false", ignoreCase = true),
                byModel = !scalar(front, "disable-model-invocation").equals("true", ignoreCase = true),
            ),
        )
    }

    private fun body(document: SkillDocument, file: Path): String {
        if (!document.truncated) return document.body
        log("[antigravity] truncated skill document at 1 MiB: ${LogSafe.str(file.toString())}\n")
        return "${document.body}\n\n[truncated: ${file.fileName} exceeds 1 MiB; read the full file at $file]"
    }

    private fun bodyDescription(body: String): String {
        val line = body.lineSequence().map { HEADING_PREFIX.replaceFirst(it.trim(), "") }
            .firstOrNull { it.isNotBlank() }.orEmpty()
        if (line.codePointCount(0, line.length) <= SKILL_FALLBACK_DESCRIPTION_CHARS) return line
        val end = line.offsetByCodePoints(0, SKILL_FALLBACK_DESCRIPTION_CHARS - 1)
        return line.substring(0, end).trimEnd() + "…"
    }

    /** [key]'s value. A quoted scalar keeps a `#` inside its quotes and drops what follows the closing quote; a plain
     *  one ends at a YAML comment (see [plain]); a block scalar (`>`, `|`, a comment allowed after the indicator)
     *  keeps its lines whole. An empty `key:` takes its value from the first indented line below it, read the same
     *  way. */
    private fun scalar(front: List<String>, key: String): String {
        val keyAt = front.indexOfFirst { it.startsWith("$key:") }
        if (keyAt < 0) return ""
        val index = valueStart(front, keyAt)
        val inline = if (index == keyAt) front[keyAt].substringAfter(':').trim() else front[index].trim()
        val quote = inline.firstOrNull()?.takeIf { it == '\'' || it == '"' }
        return when {
            quote != null -> folded(inline, front, index).let { unquote(it, quote) ?: it }
            uncommented(inline) in BLOCK_MARKERS -> folded("", front, index)
            else -> plain(sequenceOf(inline) + continuation(front, index).map { it.value })
        }
    }

    /** The line the value of the key at [keyAt] starts on: that line, or for an empty `key:` (a comment aside) the
     *  first non-blank indented line below it that is no comment line, when there is one. */
    private fun valueStart(front: List<String>, keyAt: Int): Int {
        if (uncommented(front[keyAt].substringAfter(':')).isNotEmpty()) return keyAt
        return continuation(front, keyAt).firstOrNull { it.value.isNotBlank() }?.index ?: keyAt
    }

    /** A plain scalar's [lines] folded with spaces, each cut at its comment; a line that carried one ends it. */
    private fun plain(lines: Sequence<String>): String {
        val parts = mutableListOf<String>()
        for (line in lines) {
            val text = uncommented(line)
            if (text.isNotEmpty()) parts += text
            if (text != line.trim() && parts.isNotEmpty()) break
        }
        return parts.joinToString(" ")
    }

    /** [line] without its YAML comment: a `#` at its start or after a space or tab begins one, while a `#` inside a
     *  word (`C#`, `a#b`) or after another space character (a no-break space) is text. Trimmed. */
    private fun uncommented(line: String): String {
        val at = line.indices.firstOrNull { line[it] == '#' && (it == 0 || line[it - 1] in COMMENT_SPACE) }
        return (if (at == null) line else line.substring(0, at)).trim()
    }

    private fun unquote(text: String, quote: Char): String? {
        val value = StringBuilder()
        var at = 1
        while (at < text.length) {
            val ch = text[at++]
            val escaped = escape(quote, ch, text.getOrNull(at))
            when {
                escaped != null -> {
                    value.append(escaped)
                    at += 1
                }
                ch == quote -> return value.toString()
                else -> value.append(ch)
            }
        }
        return null
    }

    private fun escape(quote: Char, ch: Char, next: Char?): Char? = when (quote) {
        '\'' -> next?.takeIf { ch == quote && it == quote }
        else -> next?.takeIf { ch == '\\' && (it == quote || it == '\\') }
    }

    /** The lines after [index] (with their indices) that continue its value: indented or blank ones. A comment line,
     *  one whose text starts with `#` at any indent, is YAML's and is dropped before the run is measured, so it
     *  neither starts a value nor ends one; inside a block or quoted scalar ([asContent]) it is text and stays. */
    private fun continuation(
        front: List<String>,
        index: Int,
        asContent: Boolean = false,
    ): Sequence<IndexedValue<String>> =
        front.asSequence().withIndex().drop(index + 1)
            .filter { asContent || !it.value.trimStart().startsWith('#') }
            .takeWhile { it.value.isBlank() || it.value.first().isWhitespace() }

    private fun folded(inline: String, front: List<String>, index: Int): String =
        (sequenceOf(inline) + continuation(front, index, asContent = true).map { it.value })
            .map(String::trim).filter(String::isNotEmpty).joinToString(" ")
}

private val BLOCK_MARKERS = setOf(">", ">-", ">+", "|", "|-", "|+")

/** YAML's white space before a comment's `#`: a space or a tab, never a no-break space. */
private val COMMENT_SPACE = setOf(' ', '\t')
private val HEADING_PREFIX = Regex("^#{1,6}(?:\\s+|$)")

// why: autocomplete needs a short summary even when the first paragraph is one very long line.
private const val SKILL_FALLBACK_DESCRIPTION_CHARS = 250
