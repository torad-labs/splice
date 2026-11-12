// NEW: V4-344, split out of TranscriptHistoryIndex.kt (V4-444): a transcript's custom title and working directory, read
// from a bounded beginning and tail so a history scan never reads a whole conversation.
package splice.client.transcript

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

// why: read only each transcript's beginning and tail, never its unbounded conversation.
private const val META_BYTES = 64 * 1024

// why: one session title stays legible in a row and never dumps a pasted prompt.
private const val TITLE_CHARS = 120

/** Only a bounded beginning and tail are inspected for a title and cwd, never the full transcript. */
internal object TranscriptMetadata {
    private val json = Json
    private val redaction = TranscriptRedaction()

    // A history scan reads every transcript's two windows on a cold start (27,422 files on Oct 20). Each read decoded
    // both windows to text and parsed every line in them to a JSON tree, about a quarter of the 08:32 CT boot's
    // allocation. The windows now go into one buffer per thread, and the lines are walked from the end, so only the
    // last line that can name a title and the last that can name a cwd are decoded and parsed: the answer the forward
    // walk kept.
    private val scratch = ThreadLocal.withInitial { ByteArray(2 * META_BYTES) }
    private val cwdKey = "\"cwd\"".toByteArray(StandardCharsets.UTF_8)
    private val titleType = "custom-title".toByteArray(StandardCharsets.UTF_8)

    fun read(file: Path): Pair<String?, String?>? {
        val bytes = scratch.get()
        val lines = windowLines(file, bytes) ?: return null
        val found = Found()
        for (line in lines.asReversed()) {
            found.take(bytes, line)
            if (found.done()) break
        }
        return found.name to found.cwd
    }

    /** The whole lines of the beginning window, then of the tail window, as ranges of [bytes]; null for an empty
     *  file. */
    private fun windowLines(file: Path, bytes: ByteArray): List<LineRange>? {
        val size = Files.size(file)
        if (size == 0L) return null
        val first = readWindow(file, 0L, minOf(size, META_BYTES.toLong()).toInt(), bytes, 0)
        if (size <= META_BYTES) return lines(bytes, 0, first, keepLast = true)
        val tail = readWindow(file, size - META_BYTES, META_BYTES, bytes, META_BYTES)
        val tailStart = (META_BYTES until META_BYTES + tail).firstOrNull { bytes[it] == TRANSCRIPT_NEWLINE }?.plus(1)
        return lines(bytes, 0, first, keepLast = false) +
            (tailStart?.let { lines(bytes, it, META_BYTES + tail - it, keepLast = true) } ?: emptyList())
    }

    /** The lines in [length] bytes from [from]; a last line with no newline after it only when [keepLast]. */
    private fun lines(bytes: ByteArray, from: Int, length: Int, keepLast: Boolean): List<LineRange> {
        val found = ArrayList<LineRange>()
        var start = from
        for (at in from until from + length) {
            if (bytes[at] == TRANSCRIPT_NEWLINE) {
                found += LineRange(start, at)
                start = at + 1
            }
        }
        if (keepLast && start < from + length) found += LineRange(start, from + length)
        return found
    }

    private fun readWindow(file: Path, offset: Long, length: Int, into: ByteArray, at: Int): Int {
        val buffer = ByteBuffer.wrap(into, at, length)
        Files.newByteChannel(file, StandardOpenOption.READ).use { channel ->
            channel.position(offset)
            while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
        }
        return buffer.position() - at
    }

    private fun record(bytes: ByteArray, line: LineRange): JsonObject? = try {
        json.parseToJsonElement(String(bytes, line.start, line.end - line.start, StandardCharsets.UTF_8)) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

    private fun titleOf(record: JsonObject): String? =
        if (JsonScalars.str(record, "type") == "custom-title") title(JsonScalars.str(record, "customTitle")) else null

    fun title(text: String?): String? = text?.let(redaction::shown)?.lineSequence()?.firstOrNull()
        ?.trim()?.take(TITLE_CHARS)?.takeIf(String::isNotEmpty)

    /** The last title and the last cwd, filled from the last line backwards. */
    private class Found {
        var name: String? = null
        var cwd: String? = null

        fun take(bytes: ByteArray, line: LineRange) {
            if (name == null && line.holds(bytes, titleType)) name = record(bytes, line)?.let(::titleOf)
            if (cwd == null && line.holds(bytes, cwdKey)) {
                cwd = record(bytes, line)?.let { JsonScalars.str(it, "cwd") }?.takeIf(String::isNotBlank)
            }
        }

        fun done(): Boolean = name != null && cwd != null
    }

    /** One line of a window: bytes [start] up to [end], the newline left out. */
    private class LineRange(val start: Int, val end: Int) {
        fun holds(bytes: ByteArray, pattern: ByteArray): Boolean {
            for (at in start..end - pattern.size) {
                if (matchesAt(bytes, pattern, at)) return true
            }
            return false
        }

        private fun matchesAt(bytes: ByteArray, pattern: ByteArray, at: Int): Boolean {
            for (k in pattern.indices) {
                if (bytes[at + k] != pattern[k]) return false
            }
            return true
        }
    }
}
