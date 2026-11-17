// NEW: the harness (docs/specs/harness.md, decisions 2 and 5). What splice knows of each thread for its capabilities:
// whether its first turn has been decided, the hash of its first user message as the client sent it, and each
// capability's state there with the text it placed. Every state is on disk before the turn it is decided for goes
// upstream, so a restarted daemon sends every live thread what it sent before.
package splice.head.harness

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.parse.lenientJson
import splice.core.util.Cancellables
import splice.core.util.JsonWire
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

/** Where a thread's first turn stands. */
@Serializable
internal enum class FirstTurn {
    /** No request of the thread that streams and carries tools has been seen yet, or its first turn was refused. */
    UNDECIDED,

    /** Its first turn has come: the capabilities its rule named were called for it. */
    TAKEN,

    /** It was first seen mid-conversation, so it never has a first turn. */
    MIDWAY,
}

/** One capability's state in a thread: called and waiting, placed (with its text), failed, or never called. */
@Serializable
internal data class CapabilityState(val name: String, val state: String, val text: String? = null) {
    val placed: Boolean get() = state == PLACED
}

/** What one thread holds. [firstHash] is the hash of its first user message at the turn the capabilities were called
 *  for; a request whose first message hashes otherwise carries nothing (a compaction replaced it). */
@Serializable
internal data class ThreadRecord(
    val first: FirstTurn = FirstTurn.UNDECIDED,
    val firstHash: String? = null,
    val capabilities: List<CapabilityState> = emptyList(),
) {
    /** The texts placed in this thread, in the order the capabilities ran, for a first message that hashes [hash]. */
    fun placedFor(hash: String?): List<String> =
        if (hash == null || hash != firstHash) emptyList() else capabilities.filter { it.placed }.mapNotNull { it.text }

    /** This record with [state]'s capability in that state, in its place, or after the others when it is new. */
    fun with(state: CapabilityState): ThreadRecord {
        val at = capabilities.indexOfFirst { it.name == state.name }
        val next = if (at < 0) capabilities + state else capabilities.toMutableList().also { it[at] = state }
        return copy(capabilities = next)
    }
}

/** What an update makes of a thread's record. */
internal fun interface RecordChange {
    operator fun invoke(record: ThreadRecord): ThreadRecord
}

/** Every thread's record, kept in memory and written under [dir] (one file a thread) when the daemon has one. */
internal class ThreadRecords(private val dir: Path?, private val log: LogSink) {
    private val records = ConcurrentHashMap<String, ThreadRecord>()

    fun of(thread: String): ThreadRecord = records.computeIfAbsent(thread) { read(it) ?: ThreadRecord() }

    /** Replaces [thread]'s record with what [change] makes of it, on disk before it returns. */
    fun update(thread: String, change: RecordChange): ThreadRecord {
        val next = checkNotNull(records.compute(thread) { key, prior -> change(prior ?: read(key) ?: ThreadRecord()) })
        write(thread, next)
        return next
    }

    private fun file(thread: String): Path? = dir?.resolve("${digest(thread)}.json")

    private fun read(thread: String): ThreadRecord? {
        val path = file(thread)?.takeIf(Files::isRegularFile) ?: return null
        return Cancellables.runCatchingCancellable {
            lenientJson.decodeFromString(ThreadRecord.serializer(), Files.readString(path))
        }.onFailure {
            log(
                "[harness] a thread's record could not be read: ${SafeFailureText.render(it)}\n",
            )
        }.getOrNull()
    }

    private fun write(thread: String, record: ThreadRecord) {
        val path = file(thread) ?: return
        SecureFile.writeAtomic0600(path, lenientJson.encodeToString(ThreadRecord.serializer(), record))
    }

    /** The hash a thread's first user message is known by: its content as the client sent it, with the
     *  `cache_control` marks left out, since Claude Code moves them from turn to turn. */
    fun hashOf(content: JsonElement?): String? = content?.let { digest(JsonWire.string(unmarked(it))) }

    private fun unmarked(content: JsonElement): JsonElement =
        if (content is JsonArray) {
            JsonArray(content.map { block -> (block as? JsonObject)?.let { JsonObject(it - MARK) } ?: block })
        } else {
            content
        }

    private fun digest(text: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
}

internal const val WAITING = "waiting"
internal const val PLACED = "placed"
internal const val FAILED = "failed"
internal const val NEVER = "never"

private const val MARK = "cache_control"
