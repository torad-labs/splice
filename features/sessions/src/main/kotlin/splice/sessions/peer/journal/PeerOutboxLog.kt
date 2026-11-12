// NEW: Oct 19, 2026 (docs/specs/peer-messaging.md 2.3): the sends a peer made, one record each, with the size of
// the text and never the text. The daemon writes it as each send answers; `sent/{msgId}` reads it. JsonlSink keeps
// one rolled generation beside it (outbox.jsonl.1).
package splice.sessions.peer.journal

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import splice.core.util.JsonlSink
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

// why: the layout number each outbox record carries as "v", so a reader can tell a newer layout from this one.
private const val PEER_OUTBOX_LAYOUT = 1

private const val PEER_OUTBOX_FILE = "outbox.jsonl"

public class PeerOutboxLog(dir: Path) {
    /** Who a send went to; any of the three can be unknown when the send did not resolve a target. */
    @Serializable
    public data class Target(val name: String? = null, val sessionId: String? = null, val address: String? = null)

    /** One send. [outcome] is `submitted`, `refused` or `failed`; [id] is the msg_id the send carried. [digest] is the
     *  SHA-256 of the text (never the text), so a repeat of a caller's id can be told from another message under it. */
    @Serializable
    public data class Record(
        val v: Int = PEER_OUTBOX_LAYOUT,
        val at: Long,
        val id: String,
        val to: Target,
        val bytes: Int,
        val outcome: String,
        val detail: String? = null,
        val digest: String? = null,
    )

    private val files = PeerJournalFiles(dir)
    private val file: Path = dir.resolve(PEER_OUTBOX_FILE)
    private val rolled: Path = dir.resolve("$PEER_OUTBOX_FILE.1")

    public fun append(record: Record) {
        files.prepare()
        val line = JournalRecords.codec.encodeToString(Record.serializer(), record)
        files.append(file, line, JsonlSink.DEFAULT_MAX_BYTES)
    }

    /** The newest send that carried [msgId], or null when this peer sent no such id. A caller's id can be sent again
     *  after a send that never left, so the newest attempt is the one that counts. */
    public fun find(msgId: String): Record? {
        for (candidate in listOf(file, rolled)) {
            val hit = scan(candidate, msgId)
            if (hit != null) return hit
        }
        return null
    }

    private fun scan(path: Path, msgId: String): Record? {
        val input = try {
            Files.newInputStream(path)
        } catch (_: NoSuchFileException) {
            return null
        }
        return input.bufferedReader(Charsets.UTF_8).use { reader ->
            reader.lineSequence().mapNotNull { line -> decode(line) }.lastOrNull { it.id == msgId }
        }
    }

    private fun decode(line: String): Record? = try {
        JournalRecords.codec.decodeFromString(Record.serializer(), line)
    } catch (_: SerializationException) {
        null
    }
}
