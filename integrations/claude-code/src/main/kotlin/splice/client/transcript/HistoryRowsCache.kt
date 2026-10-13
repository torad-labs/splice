// NEW: Oct 12, 2026 (splice-lead) — history.jsonl read once, then only what was appended. The file only grows, so a
// refresh reads from where the last one stopped; a file that shrank or was replaced is read from the start.
package splice.client.transcript

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.util.FileIdentity
import splice.core.util.JsonScalars
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private const val HISTORY_LINE_END = '\n'
private const val HISTORY_LINE_END_BYTE = HISTORY_LINE_END.code.toByte()

private val HISTORY_ID = Regex("[A-Za-z0-9_-]{1,128}")

/** One history line that names a session, parsed once. [title] is the display text already cut and redacted. */
internal data class HistoryRow(val id: String, val timestamp: Long?, val title: String?, val project: String?)

internal class HistoryRowsCache {
    internal data class Held(val key: FileIdentity?, var committed: Long, val rows: MutableList<HistoryRow>)

    private val json = Json
    private var held: Map<Path, Held> = emptyMap()

    /** One refresh: the rows of each history file it reads, and the files it met kept for the next. */
    fun pass(): Pass = Pass(held)

    inner class Pass(private val before: Map<Path, Held>) {
        private val met = HashMap<Path, Held>()

        /** Every row of [file], read from where the last refresh stopped. Throws what reading the file throws. */
        fun rows(file: Path): List<HistoryRow> {
            val stat = EntryStat(file)
            val prior = before[file]?.takeIf { it.key == stat.identity && it.committed <= stat.size }
            val kept = prior ?: Held(stat.identity, 0L, ArrayList())
            val bytes = readFrom(file, kept.committed)
            val end = bytes.lastIndexOf(HISTORY_LINE_END_BYTE) + 1
            kept.rows += parse(String(bytes, 0, end, StandardCharsets.UTF_8))
            kept.committed += end
            met[file] = kept
            // an unterminated last line is read now and read again once its newline arrives
            return kept.rows + parse(String(bytes, end, bytes.size - end, StandardCharsets.UTF_8))
        }

        fun commit() {
            held = met
        }
    }

    private fun readFrom(file: Path, offset: Long): ByteArray {
        Files.newByteChannel(file, StandardOpenOption.READ).use { channel ->
            val size = channel.size()
            channel.position(offset)
            val buffer = ByteBuffer.allocate(maxOf(0L, size - offset).toInt())
            while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
            return buffer.array().copyOf(buffer.position())
        }
    }

    private fun parse(text: String): List<HistoryRow> =
        if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split(HISTORY_LINE_END).mapNotNull(::row)

    /** A line that is not JSON, or names no session, is no row. */
    private fun row(line: String): HistoryRow? {
        val record = try {
            json.parseToJsonElement(line) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return null
        val id = JsonScalars.str(record, "sessionId")
        if (id == null || !HISTORY_ID.matches(id)) return null
        return HistoryRow(
            id = id,
            timestamp = JsonScalars.long(record, "timestamp"),
            title = TranscriptMetadata.title(JsonScalars.str(record, "display")),
            project = JsonScalars.str(record, "project")?.takeIf(String::isNotBlank),
        )
    }
}
