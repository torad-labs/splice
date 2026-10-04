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

/** Only a bounded tail is inspected for a title and cwd, never the full transcript. */
internal object TranscriptMetadata {
    private val json = Json
    private val redaction = TranscriptRedaction()

    fun read(file: Path): Pair<String?, String?>? {
        val chunks = boundedText(file)
        if (chunks.isEmpty()) return null
        var name: String? = null
        var cwd: String? = null
        chunks.asSequence().flatMap { it.lineSequence() }.forEach { line ->
            val record = try {
                json.parseToJsonElement(line) as? JsonObject
            } catch (_: SerializationException) {
                null
            } ?: return@forEach
            if (JsonScalars.str(record, "type") == "custom-title") {
                name = title(JsonScalars.str(record, "customTitle")) ?: name
            }
            cwd = JsonScalars.str(record, "cwd")?.takeIf(String::isNotBlank) ?: cwd
        }
        return name to cwd
    }

    private fun boundedText(file: Path): List<String> {
        val size = Files.size(file)
        if (size == 0L) return emptyList()
        val beginning = readWindow(file, 0L, minOf(size, META_BYTES.toLong()).toInt())
        if (size <= META_BYTES) return listOf(beginning)
        val end = readWindow(file, size - META_BYTES, META_BYTES).substringAfter('\n', "")
        return listOf(beginning.substringBeforeLast('\n', ""), end)
    }

    private fun readWindow(file: Path, offset: Long, length: Int): String {
        val bytes = ByteBuffer.allocate(length)
        Files.newByteChannel(file, StandardOpenOption.READ).use { channel ->
            channel.position(offset)
            while (bytes.hasRemaining() && channel.read(bytes) > 0) Unit
        }
        return String(bytes.array(), 0, bytes.position(), StandardCharsets.UTF_8)
    }

    fun title(text: String?): String? = text?.let(redaction::shown)?.lineSequence()?.firstOrNull()
        ?.trim()?.take(TITLE_CHARS)?.takeIf(String::isNotEmpty)
}
