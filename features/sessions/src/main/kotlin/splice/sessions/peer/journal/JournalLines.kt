// NEW: Oct 19, 2026 (docs/specs/peer-messaging.md 2.3): how a journal line is read. A line that does not parse is a
// torn tail a concurrent append has not finished, and it is skipped.
package splice.sessions.peer.journal

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.core.util.JsonlSink
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

// why: a tail read must reach the last whole record, and one record is at most about 200 KiB once escaped.
private const val PEER_JOURNAL_TAIL_BYTES = 1024 * 1024

internal const val PEER_KEY_SEQ: String = "seq"
internal const val PEER_KEY_KIND: String = "kind"
internal const val PEER_KEY_ID: String = "id"
private const val PEER_KEY_OUTCOME = "outcome"

internal object JournalLines {

    fun parse(line: String): JsonObject? = try {
        JournalRecords.codec.parseToJsonElement(line) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

    fun seqOf(record: JsonObject): Long? = JsonScalars.long(record, PEER_KEY_SEQ)

    fun isKind(record: JsonObject, kind: String): Boolean = JsonScalars.str(record, PEER_KEY_KIND) == kind

    fun seqIn(line: String): Long? = parse(line)?.let { seqOf(it) }

    /** The outcome a webhook record on [line] carries, or null when the line is any other record. */
    fun outcomeIn(line: String): String? = parse(line)
        ?.takeIf { isKind(it, PEER_KIND_WEBHOOK) }
        ?.let { JsonScalars.str(it, PEER_KEY_OUTCOME) }

    /** The whole lines in the last megabyte of [file], oldest first; none when the file is not there. */
    fun tail(file: Path): List<String> = try {
        JsonlSink.readTail(file, PEER_JOURNAL_TAIL_BYTES)
    } catch (_: NoSuchFileException) {
        emptyList()
    }

    /** [file]'s size and age as one string, or `-` when it is not there. */
    fun stat(file: Path): String = try {
        "${Files.size(file)}/${Files.getLastModifiedTime(file).toMillis()}"
    } catch (_: NoSuchFileException) {
        "-"
    }
}
