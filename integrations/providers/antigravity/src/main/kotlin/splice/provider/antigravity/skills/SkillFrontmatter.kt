package splice.provider.antigravity.skills

import java.nio.file.Path

internal class SkillFrontmatter {
    private val markdown = SkillMarkdown()

    fun readItem(file: Path, fallbackName: String, folder: Path?): LocalSkillItem? {
        val document = markdown.read(file)
        val front = document.frontmatter
        if (scalar(front, "user-invocable").equals("false", ignoreCase = true)) return null
        return LocalSkillItem(
            name = SkillName.valid(scalar(front, "name")) ?: fallbackName,
            description = scalar(front, "description").ifBlank { bodyDescription(document.body) },
            hint = scalar(front, "argument-hint"),
            isSkill = folder != null,
            body = document.body,
            file = file,
            folder = folder,
        )
    }

    private fun bodyDescription(body: String): String =
        body.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.trimStart('#')?.trimStart().orEmpty()

    private fun scalar(front: List<String>, key: String): String {
        val index = front.indexOfFirst { it.startsWith("$key:") }
        if (index < 0) return ""
        val inline = front[index].substringAfter(':').trim()
        return when {
            inline in BLOCK_MARKERS -> folded("", front, index)
            enclosed(inline, '"') -> DOUBLE_QUOTE_ESCAPE.replace(inline.removeSurrounding("\"")) { it.groupValues[1] }
            enclosed(inline, '\'') -> inline.removeSurrounding("'").replace("''", "'")
            else -> folded(inline, front, index)
        }
    }

    private fun enclosed(text: String, quote: Char): Boolean =
        text.length >= 2 && text.first() == quote && text.last() == quote

    private fun folded(inline: String, front: List<String>, index: Int): String {
        val continuation = front.asSequence().drop(index + 1).takeWhile { it.isBlank() || it.first().isWhitespace() }
        return (sequenceOf(inline) + continuation).map(String::trim).filter(String::isNotEmpty).joinToString(" ")
    }
}

private val BLOCK_MARKERS = setOf(">", ">-", ">+", "|", "|-", "|+")
private val DOUBLE_QUOTE_ESCAPE = Regex("""\\(["\\])""")
