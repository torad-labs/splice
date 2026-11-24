package splice.provider.antigravity.skills

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path

internal data class SkillDocument(val frontmatter: List<String>, val body: String, val truncated: Boolean)

internal class SkillMarkdown {
    fun read(file: Path): SkillDocument {
        val bytes = try {
            Files.newInputStream(file).use { it.readNBytes(MAX_MARKDOWN_BYTES + 1) }
        } catch (_: IOException) {
            ByteArray(0)
        }
        val text = decodePrefix(bytes).removePrefix("﻿")
        val truncated = bytes.size > MAX_MARKDOWN_BYTES
        val head = text.lineSequence().take(MAX_FRONTMATTER_LINES).toList()
        if (head.firstOrNull()?.trim() != FENCE) return SkillDocument(emptyList(), text, truncated)
        val front = head.drop(1).takeWhile { it.trim() != FENCE }
        val fenceIndex = 1 + front.size
        if (fenceIndex == head.size) return SkillDocument(emptyList(), text, truncated)
        return SkillDocument(front, afterLine(text, fenceIndex), truncated)
    }

    /** Preserve the body as written, including its line endings. */
    private fun afterLine(text: String, line: Int): String =
        LINE_END.findAll(text).drop(line).firstOrNull()?.range?.last?.let { text.substring(it + 1) }.orEmpty()

    /** Decode without replacing an incomplete UTF-8 sequence cut by the byte limit. */
    private fun decodePrefix(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
        val count = minOf(bytes.size, MAX_MARKDOWN_BYTES)
        val text = CharBuffer.allocate(count)
        val status = decoder.decode(ByteBuffer.wrap(bytes, 0, count), text, false)
        check(status.isUnderflow)
        return text.flip().toString()
    }
}

private const val FENCE = "---"

// why: metadata belongs at the top; a missing closing fence must not turn a long body into metadata.
private const val MAX_FRONTMATTER_LINES = 200

// why: match Claude Code's plugin skill limit while bounding the memory and I/O spent on each file.
private const val MAX_MARKDOWN_BYTES = 1024 * 1024
private val LINE_END = Regex("\r\n|\r|\n")
