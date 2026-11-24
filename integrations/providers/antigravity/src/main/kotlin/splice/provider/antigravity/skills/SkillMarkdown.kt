package splice.provider.antigravity.skills

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal data class SkillDocument(val frontmatter: List<String>, val body: String, val truncated: Boolean)

internal class SkillMarkdown {
    fun metadata(file: Path): SkillDocument? = read(file, MAX_METADATA_BYTES)

    fun body(file: Path): String? {
        if (SkillPaths.file(file) != file) return null
        val document = read(file, MAX_MARKDOWN_BYTES) ?: return null
        return if (document.truncated) {
            "${document.body}\n\n[truncated: ${file.fileName} exceeds 1 MiB; read the full file at $file]"
        } else {
            document.body
        }
    }

    private fun read(file: Path, limit: Int): SkillDocument? {
        val bytes = try {
            Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(limit + 1) }
        } catch (_: IOException) {
            return null
        }
        val text = decodePrefix(bytes, limit).removePrefix("﻿")
        return split(text, bytes.size > limit)
    }

    private fun split(text: String, truncated: Boolean): SkillDocument? {
        val head = text.lineSequence().take(MAX_FRONTMATTER_LINES).toList()
        if (head.firstOrNull()?.trim() != FENCE) return SkillDocument(emptyList(), text, truncated)
        val fence = 1 + head.drop(1).indexOfFirst { it.trimEnd() in CLOSING_FENCES }
        if (fence == 0) return if (truncated) null else SkillDocument(emptyList(), text, truncated)
        // A truncated prefix cannot establish a delimiter unless its whole line was read.
        val end = LINE_END.findAll(text).drop(fence).firstOrNull()?.range?.last
        return if (truncated && end == null) {
            null
        } else {
            val body = end?.let { text.substring(it + 1) }.orEmpty()
            SkillDocument(head.subList(1, fence), body, truncated)
        }
    }

    /** Decode without replacing an incomplete UTF-8 sequence cut by the byte limit. */
    private fun decodePrefix(bytes: ByteArray, limit: Int): String {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
        val count = minOf(bytes.size, limit)
        val text = CharBuffer.allocate(count)
        val status = decoder.decode(ByteBuffer.wrap(bytes, 0, count), text, false)
        check(status.isUnderflow)
        return text.flip().toString()
    }
}

private const val FENCE = "---"
private val CLOSING_FENCES = setOf(FENCE, "...")

// why: discovery needs only the header and a short local menu summary, not the whole body.
private const val MAX_METADATA_BYTES = 16 * 1024

// why: a missing closing fence must not turn a long body into metadata.
private const val MAX_FRONTMATTER_LINES = 200

// why: match Claude Code's plugin skill limit while bounding the memory and I/O spent on invocation.
private const val MAX_MARKDOWN_BYTES = 1024 * 1024
private val LINE_END = Regex("\r\n|\r|\n")
