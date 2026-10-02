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
    private const val DELTA_START = "{\"key\":"
    private const val COMPACT_FLOOR_BYTES = 8L * 1024 * 1024
    private const val COMPACT_RATIO = 4
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
        if (!text.startsWith(DELTA_START)) {
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
        if (!endsWithNewline(path)) {
            // The only full-file rewrite on the request path is recovery or a legacy checkpoint
            // without a terminating newline. Ordinary appends inspect only the final byte.
            SecureFile.writeAtomic0600(path, codec.encodeToString(read(path, codec)) + "\n")
        }
    }

    /** Streams the journal a line at a time, so loading holds the live state and one entry, never the
     *  whole file: a 2 GB journal read whole into one string failed a 2 GB daemon at boot (Oct 1). */
    fun read(path: Path, json: Json): CodeModePersistedState =
        Files.newBufferedReader(path, Charsets.UTF_8).use { reader ->
            decodeLines(reader.lineSequence().iterator(), endsWithNewline(path), json)
        }

    /** Only newline-terminated entries commit: when [committed] is false the final line is a torn
     *  fragment, which may precede even a delta's key field, and it is never applied. */
    private fun decodeLines(lines: Iterator<String>, committed: Boolean, json: Json): CodeModePersistedState {
        // Older daemons and operators may have formatted the checkpoint over several lines.
        val head = ArrayList<String>()
        var line: String? = null
        while (line == null && lines.hasNext()) {
            val next = lines.next()
            if (next.startsWith(DELTA_START)) line = next else head += next
        }
        if (line == null) return checkpointOnly(head, committed, json)
        val checkpoint = head.joinToString("\n")
        require(checkpoint.isNotBlank()) { "code-mode journal has no checkpoint" }
        return replayed(json.decodeFromString(checkpoint), line, lines, committed, json)
    }

    /** [checkpoint] with [first] and every later delta applied, except a torn final line. */
    private fun replayed(
        checkpoint: CodeModePersistedState,
        first: String,
        lines: Iterator<String>,
        committed: Boolean,
        json: Json,
    ): CodeModePersistedState {
        var state = checkpoint
        val key = (state.records.map { it.key } + state.expired.map { it.key }).distinct().single()
        var line: String? = first
        while (line != null) {
            val following = if (lines.hasNext()) lines.next() else null
            if (following == null && !committed) break
            // Every line before the torn tail committed and must decode.
            if (line.isNotBlank()) state = applied(state, key, json.decodeFromString<CodeModeStateDelta>(line))
            line = following
        }
        return state
    }

    private fun checkpointOnly(head: List<String>, committed: Boolean, json: Json): CodeModePersistedState =
        try {
            json.decodeFromString<CodeModePersistedState>(head.joinToString("\n"))
        } catch (whole: IllegalArgumentException) {
            if (committed) throw whole
            json.decodeFromString<CodeModePersistedState>(head.dropLast(1).joinToString("\n"))
        }

    private fun applied(
        state: CodeModePersistedState,
        key: String,
        change: CodeModeStateDelta,
    ): CodeModePersistedState {
        require(change.key == key && change.records.all { it.key == key } && change.expired.all { it.key == key }) {
            "code-mode journal crosses conversation keys"
        }
        val records = state.records.associateByTo(linkedMapOf(), CodeModeRecordSnapshot::id)
        change.removed.forEach(records::remove)
        change.records.forEach { records[it.id] = it }
        return CodeModePersistedState(records = records.values.toList(), expired = change.expired)
    }

    private fun endsWithNewline(path: Path): Boolean =
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            if (channel.size() == 0L) return@use false
            val byte = ByteBuffer.allocate(1)
            channel.position(channel.size() - 1)
            channel.read(byte)
            byte.array()[0] == '\n'.code.toByte()
        }

    /** A journal past [COMPACT_RATIO] times its live state, once past [COMPACT_FLOOR_BYTES], is rewritten as
     *  one checkpoint. Each step re-appends its whole cell and only a removal compacted, so a long
     *  conversation's file grew without bound: 2 GB holding 4 MB of live cells on Oct 1. */
    fun outgrown(path: Path, liveBytes: Long): Boolean {
        val size = try {
            Files.size(path)
        } catch (_: NoSuchFileException) {
            return false
        }
        return size > COMPACT_FLOOR_BYTES && size > liveBytes * COMPACT_RATIO
    }

    /** The encoded bytes of [state]'s records, measuring and keeping each record's size the first time. */
    fun liveBytes(state: CodeModePersistedState, json: Json): Long = state.records.sumOf { record ->
        record.retainedBytes ?: json.encodeToString(record).encodeToByteArray().size.toLong()
            .also { record.retainedBytes = it }
    }

    fun encode(key: String, prior: CodeModePersistedState?, next: CodeModePersistedState, json: Json): String {
        liveBytes(next, json)
        if (prior == null) return json.encodeToString(next)
        val change = delta(key, prior, next)
        // Retention deletes payload bytes, not merely their index. Compact deletions so old scripts
        // and private results do not remain recoverable in earlier journal entries past their TTL.
        return if (change.removed.isNotEmpty()) json.encodeToString(next) else json.encodeToString(change)
    }
}
