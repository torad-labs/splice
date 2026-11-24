package splice.provider.antigravity.skills

import splice.core.util.LogSafe
import splice.core.util.LogSink
import java.nio.file.Path

internal class SkillFrontmatter(private val log: LogSink) {
    private val markdown = SkillMarkdown()

    fun readItem(file: Path, fallbackName: String, folder: Path?, root: Path): LocalSkillItem? {
        val document = markdown.read(file)
        val front = document.frontmatter
        val body = body(document, file)
        if (scalar(front, "user-invocable").equals("false", ignoreCase = true)) return null
        return LocalSkillItem(
            name = SkillName.valid(scalar(front, "name")) ?: fallbackName,
            description = scalar(front, "description").ifBlank { bodyDescription(document.body) },
            hint = scalar(front, "argument-hint"),
            body = body,
            file = file,
            folder = folder,
            root = root,
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

    private fun scalar(front: List<String>, key: String): String {
        val index = front.indexOfFirst { it.startsWith("$key:") }
        if (index < 0) return ""
        val inline = front[index].substringAfter(':').trim()
        if (inline in BLOCK_MARKERS) return folded("", front, index)
        val value = folded(inline, front, index)
        return when (val quote = inline.firstOrNull()) {
            '\'', '"' -> unquote(value, quote) ?: value
            else -> value
        }
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

    private fun folded(inline: String, front: List<String>, index: Int): String {
        val continuation = front.asSequence().drop(index + 1).takeWhile { it.isBlank() || it.first().isWhitespace() }
        return (sequenceOf(inline) + continuation).map(String::trim).filter(String::isNotEmpty).joinToString(" ")
    }
}

private val BLOCK_MARKERS = setOf(">", ">-", ">+", "|", "|-", "|+")
private val HEADING_PREFIX = Regex("^#{1,6}(?:\\s+|$)")

// why: autocomplete needs a short summary even when the first paragraph is one very long line.
private const val SKILL_FALLBACK_DESCRIPTION_CHARS = 250
