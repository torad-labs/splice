// NEW: one conversation checkpoint plus durable per-cell deltas, without rewriting earlier cell payloads.
// CREDENTIAL-WRITE-EXEMPT[2026-09-30]: private code-mode state, never credentials; compaction keeps every live cell.
package splice.provider.codex.state

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import splice.core.util.JsonlSink
import splice.core.util.SecureFile
import splice.provider.codex.CodeModeExpiredSnapshot
import splice.provider.codex.CodeModePersistedState
import splice.provider.codex.CodeModeRecordSnapshot
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Each committed entry replaces only changed records and the conversation's bounded expiry markers. */
@Serializable
internal data class CodeModeStateDelta(
    val key: String,
    val records: List<CodeModeRecordSnapshot>,
    val removed: Set<String>,
    val expired: List<CodeModeExpiredSnapshot>,
)

/** Checkpoints remain byte-compatible with the old single JSON object. Later lines are cell deltas. */
internal object CodeModeStateJournal {
    private val codec = Json { encodeDefaults = true }
    fun delta(
        key: String,
        prior: CodeModePersistedState,
        next: CodeModePersistedState,
    ): CodeModeStateDelta {
        val before = prior.records.associateBy(CodeModeRecordSnapshot::id)
        val after = next.records.map(CodeModeRecordSnapshot::id).toSet()
        return CodeModeStateDelta(
            key,
            next.records.filter { record -> !same(record, before[record.id]) },
            before.keys - after,
            next.expired,
        )
    }

    fun same(left: CodeModeRecordSnapshot?, right: CodeModeRecordSnapshot?): Boolean =
        left == right && left?.issued == right?.issued && left?.sessionId == right?.sessionId &&
            left?.nativeBaseId == right?.nativeBaseId && left?.replayAnchors == right?.replayAnchors &&
            left?.sourceState == right?.sourceState

    /** Called under the conversation lock. First write creates a 0600 checkpoint; appends are forced. */
    fun write(path: Path, text: String) {
        if (!text.startsWith("{\"key\":")) {
            SecureFile.writeAtomic0600(path, text + "\n")
        } else {
            // A delta is not a checkpoint. Refuse this race so the caller recreates its full durable cache.
            if (Files.notExists(path)) throw NoSuchFileException(path.toString())
            when (val tightening = SecureFile.ownerOnlyFile(path)) {
                is splice.core.util.FileTightening.Open -> throw IOException(tightening.why)
                else -> Unit
            }
            trimTornTail(path)
            JsonlSink.appendLine(path, text, maxBytes = Long.MAX_VALUE)
        }
    }

    /** The append primitive heals telemetry tears by separating lines. Durable state must remove
     *  an uncommitted fragment before retrying, not turn it into a corrupt interior entry. */
    private fun trimTornTail(path: Path) {
        val complete = FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            if (channel.size() == 0L) return@use false
            val byte = ByteBuffer.allocate(1)
            channel.position(channel.size() - 1)
            channel.read(byte)
            byte.array()[0] == '\n'.code.toByte()
        }
        if (!complete) {
            // The only full-file rewrite on the request path is recovery or a legacy checkpoint
            // without a terminating newline. Ordinary appends inspect only the final byte.
            SecureFile.writeAtomic0600(path, codec.encodeToString(read(path, codec)) + "\n")
        }
    }

    fun read(path: Path, json: Json): CodeModePersistedState = decode(Files.readString(path), json)

    fun decode(text: String, json: Json): CodeModePersistedState {
        // Older daemons and operators may have formatted the checkpoint over several lines.
        try {
            return json.decodeFromString<CodeModePersistedState>(text)
        } catch (_: IllegalArgumentException) {
            // A journal has more than one root object. Its first complete line is the checkpoint.
        }
        // A tear may precede even the delta's key field. Only newline-terminated entries commit;
        // discard any final fragment before looking for a delta, including a torn first append.
        val committed = if (text.endsWith("\n")) text else text.substring(0, text.lastIndexOf('\n') + 1)
        try {
            return json.decodeFromString<CodeModePersistedState>(committed)
        } catch (_: IllegalArgumentException) {
            // Committed deltas remain to apply below.
        }
        val lines = committed.lineSequence().filter(String::isNotBlank).toList()
        val start = lines.indexOfFirst { it.startsWith("{\"key\":") }
        require(start > 0) { "code-mode journal has no checkpoint" }
        var state = json.decodeFromString<CodeModePersistedState>(lines.take(start).joinToString("\n"))
        val key = (state.records.map { it.key } + state.expired.map { it.key }).distinct().single()
        val changes = lines.drop(start)
        changes.forEach { line ->
            // The torn tail is already excluded. Every remaining line committed and must decode.
            val change = json.decodeFromString<CodeModeStateDelta>(line)
            require(change.key == key && change.records.all { it.key == key } && change.expired.all { it.key == key }) {
                "code-mode journal crosses conversation keys"
            }
            val records = state.records.associateByTo(linkedMapOf(), CodeModeRecordSnapshot::id)
            change.removed.forEach(records::remove)
            change.records.forEach { records[it.id] = it }
            state = CodeModePersistedState(records = records.values.toList(), expired = change.expired)
        }
        return state
    }

    fun encode(key: String, prior: CodeModePersistedState?, next: CodeModePersistedState, json: Json): String {
        next.records.filter { it.retainedBytes == null }.forEach { record ->
            record.retainedBytes = json.encodeToString(record).encodeToByteArray().size.toLong()
        }
        if (prior == null) return json.encodeToString(next)
        val change = delta(key, prior, next)
        // Retention deletes payload bytes, not merely their index. Compact deletions so old scripts
        // and private results do not remain recoverable in earlier journal entries past their TTL.
        return if (change.removed.isNotEmpty()) json.encodeToString(next) else json.encodeToString(change)
    }
}
