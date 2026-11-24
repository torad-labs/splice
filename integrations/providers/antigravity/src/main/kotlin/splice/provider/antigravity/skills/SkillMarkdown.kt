package splice.provider.antigravity.skills

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path

internal data class SkillDocument(val frontmatter: List<String>, val body: String)

internal class SkillMarkdown {
    fun read(file: Path): SkillDocument {
        val text = try {
            decodePrefix(Files.newInputStream(file).use { it.readNBytes(MAX_MARKDOWN_BYTES) })
        } catch (_: IOException) {
            ""
        }
        val head = text.lineSequence().take(MAX_FRONTMATTER_LINES).toList()
        if (head.firstOrNull()?.trim() != FENCE) return SkillDocument(emptyList(), text)
        val front = head.drop(1).takeWhile { it.trim() != FENCE }
        val fenceIndex = 1 + front.size
        val body = if (fenceIndex < head.size) afterLine(text, fenceIndex) else ""
        return SkillDocument(front, body)
    }

    /** Preserve the body as written, including its line endings. */
    private fun afterLine(text: String, line: Int): String =
        LINE_END.findAll(text).drop(line).firstOrNull()?.range?.last?.let { text.substring(it + 1) }.orEmpty()

    /** Decode without replacing an incomplete UTF-8 sequence cut by the byte limit. */
    private fun decodePrefix(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
        val text = CharBuffer.allocate(bytes.size)
        val status = decoder.decode(ByteBuffer.wrap(bytes), text, false)
        check(status.isUnderflow)
        return text.flip().toString()
    }
}

private const val FENCE = "---"
private const val MAX_FRONTMATTER_LINES = 200
private const val MAX_MARKDOWN_BYTES = 64 * 1024
private val LINE_END = Regex("\r\n|\r|\n")
