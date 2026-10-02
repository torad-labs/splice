// NEW: one conversation checkpoint plus durable per-cell deltas, without rewriting earlier cell payloads.
// CREDENTIAL-WRITE-EXEMPT[2026-09-30]: private code-mode state, never credentials; compaction keeps every live cell.
package splice.provider.codex.state

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
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
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Each committed entry replaces only changed records and the conversation's bounded expiry markers. */
@Serializable
internal data class CodeModeStateDelta(
    val key: String,
    val records: List<CodeModeRecordSnapshot>,
    val removed: Set<String>,
    val expired: List<CodeModeExpiredSnapshot>,
)

@Serializable
internal data class CodeModeCellPatch(val id: String, val fields: JsonObject)

@Serializable
internal data class CodeModeStatePatch(
    val key: String,
    val patches: List<CodeModeCellPatch>,
    val expired: List<CodeModeExpiredSnapshot>,
)

/** Flushes the file or directory that makes a checkpoint durable, with its path visible to tests. */
internal fun interface CodeModeCheckpointForce {
    operator fun invoke(path: Path, channel: FileChannel)
}

/**
 * Full checkpoints and old full-cell deltas still load. Patch lines deliberately omit legacy deltas'
 * mandatory records field: an older decoder rejects them rather than restoring incomplete cells.
 * The store writes these journals as .jsonl, outside older jars' .json discovery, so a downgrade
 * preserves them instead of treating the rejected patch as corruption and deleting the conversation.
 */
internal object CodeModeStateJournal {
    private const val DELTA_START = "{\"key\":"

    // why: under 8 MiB a full rewrite costs less than the bookkeeping to avoid it, so small journals
    // never compact; Oct 1's 2 GB file held 4 MB of live cells.
    private const val COMPACT_FLOOR_BYTES = 8L * 1024 * 1024

    // why: a journal is rewritten once it holds four times its live cells, so the rewrite's cost is
    // amortized over at least three journals' worth of appends.
    private const val COMPACT_RATIO = 4
    private val codec = Json { encodeDefaults = true }
    fun same(left: CodeModeRecordSnapshot?, right: CodeModeRecordSnapshot?): Boolean =
        left == right && left?.issued == right?.issued && left?.sessionId == right?.sessionId &&
            left?.nativeBaseId == right?.nativeBaseId && left?.replayAnchors == right?.replayAnchors &&
            left?.sourceState == right?.sourceState

    /** Called under the conversation lock. First write creates a 0600 checkpoint; appends are forced. */
    fun write(
        path: Path,
        text: String,
        force: CodeModeCheckpointForce = CodeModeCheckpointForce { _, channel -> channel.force(true) },
    ) {
        if (!text.startsWith(DELTA_START)) {
            // A client may send an unpaired surrogate in a tool result. JsonlSink's append writes it as
            // `?`, and the strict encoder behind writeAtomic0600 refused it, so once a journal outgrew its
            // cells every save of that conversation failed. A checkpoint carries the bytes an append would.
            Checkpoint(force).write(path, text)
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

    private class Checkpoint(private val force: CodeModeCheckpointForce) {
        fun write(path: Path, text: String) {
            val parent = path.toAbsolutePath().parent
            Files.createDirectories(parent)
            val temporary = Files.createTempFile(parent, ".code-mode", ".tmp")
            try {
                // Reuse the secure writer without changing credential writes' existing contract.
                SecureFile.writeAtomic0600(temporary, String((text + "\n").toByteArray(Charsets.UTF_8), Charsets.UTF_8))
                FileChannel.open(temporary, StandardOpenOption.WRITE).use { force(temporary, it) }
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                FileChannel.open(parent, StandardOpenOption.READ).use { force(parent, it) }
            } finally {
                Cancellables.discard(
                    runCatching { Files.deleteIfExists(temporary) },
                    "checkpoint temp cleanup is best-effort",
                )
            }
        }
    }

    /** The append primitive heals telemetry tears by separating lines. Durable state must remove
     *  an uncommitted fragment before retrying, not turn it into a corrupt interior entry. */
    private fun trimTornTail(path: Path) {
        if (!endsWithNewline(path)) {
            // The only full-file rewrite on the request path is recovery or a legacy checkpoint
            // without a terminating newline. Ordinary appends inspect only the final byte.
            val recovered = try {
                read(path, codec)
            } catch (failure: IllegalArgumentException) {
                throw IOException("code-mode journal has corrupt committed state", failure)
            }
            write(path, codec.encodeToString(recovered))
        }
    }

    /** Streams the journal a line at a time, so loading holds the live state and one entry, never the
     *  whole file: a 2 GB journal read whole into one string failed a 2 GB daemon at boot (Oct 1). The
     *  reader replaces malformed bytes as the whole-file decode did: a write cut inside a multi-byte
     *  character leaves a torn last line that is dropped, never a journal that cannot load. */
    fun read(path: Path, json: Json): CodeModePersistedState =
        Files.newInputStream(path).bufferedReader(Charsets.UTF_8).use { reader ->
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
        return replayed(json.parseToJsonElement(checkpoint).jsonObject, line, lines, committed, json)
    }

    /** [checkpoint] with [first] and every later delta applied, except a torn final line. */
    private fun replayed(
        checkpoint: JsonObject,
        first: String,
        lines: Iterator<String>,
        committed: Boolean,
        json: Json,
    ): CodeModePersistedState {
        val replay = Replay(checkpoint, json)
        var line: String? = first
        while (line != null) {
            val following = if (lines.hasNext()) lines.next() else null
            if (following == null && !committed) break
            if (line.isNotBlank()) replay.apply(json.parseToJsonElement(line).jsonObject)
            line = following
        }
        return replay.finish()
    }

    private fun checkpointOnly(head: List<String>, committed: Boolean, json: Json): CodeModePersistedState =
        try {
            json.decodeFromString<CodeModePersistedState>(head.joinToString("\n"))
        } catch (whole: IllegalArgumentException) {
            if (committed) throw whole
            json.decodeFromString<CodeModePersistedState>(head.dropLast(1).joinToString("\n"))
        }

    /** Keeps raw field trees through replay, so patches never re-encode an unchanged history while loading. */
    @OptIn(ExperimentalSerializationApi::class)
    private class Replay(private val checkpoint: JsonObject, private val json: Json) {
        // Validate before raw indexing, but do not retain a second decoded copy through replay.
        private val records = json.decodeFromJsonElement<CodeModePersistedState>(checkpoint).let { validated ->
            val indexed = checkpoint["records"]?.jsonArray.orEmpty()
                .associateByTo(linkedMapOf()) { checkNotNull(JsonScalars.str(it.jsonObject["id"])) }
            require(validated.records.size == indexed.size) { "code-mode checkpoint repeats cell identities" }
            indexed
        }
        private var expired = checkpoint["expired"]?.jsonArray ?: JsonArray(emptyList())
        private val key = requireNotNull(
            (records.values + expired)
                .map { checkNotNull(JsonScalars.str(it.jsonObject["key"])) }.distinct().singleOrNull(),
        ) { "code-mode checkpoint has no unique conversation key" }
        private val descriptor = CodeModeRecordSnapshot.serializer().descriptor
        private val fields = (0 until descriptor.elementsCount).map(descriptor::getElementName).toSet()

        fun apply(change: JsonObject) {
            if ("patches" in change) {
                val patch = json.decodeFromJsonElement<CodeModeStatePatch>(change)
                require(patch.key == key && patch.expired.all { it.key == key }) {
                    "code-mode journal crosses conversation keys"
                }
                patch.patches.forEach { cell ->
                    val before = records[cell.id]?.jsonObject
                    require(before != null || cell.fields.keys.containsAll(fields)) {
                        "code-mode journal patch has no complete base cell"
                    }
                    val next = JsonObject(before.orEmpty() + cell.fields)
                    val restored = json.decodeFromJsonElement<CodeModeRecordSnapshot>(next)
                    require(restored.id == cell.id && restored.key == key) {
                        "code-mode journal crosses cell identities"
                    }
                    records[cell.id] = next
                }
            } else {
                val delta = json.decodeFromJsonElement<CodeModeStateDelta>(change)
                val sameRecords = delta.key == key && delta.records.all { it.key == key }
                require(sameRecords && delta.expired.all { it.key == key }) {
                    "code-mode journal crosses conversation keys"
                }
                delta.removed.forEach(records::remove)
                change.getValue("records").jsonArray.forEach { cell ->
                    records[checkNotNull(JsonScalars.str(cell.jsonObject["id"]))] = cell
                }
            }
            expired = change.getValue("expired").jsonArray
        }

        fun finish(): CodeModePersistedState = json.decodeFromJsonElement(
            JsonObject(checkpoint + mapOf("records" to JsonArray(records.values.toList()), "expired" to expired)),
        )
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

    /** A delta may append to [path]: it exists, and it has not outgrown [liveBytes] of live cells. */
    fun appendable(path: Path, liveBytes: Long): Boolean = Files.exists(path) && !outgrown(path, liveBytes)

    /** Measures a loaded cell once; subsequent saves update only the encoded field sizes that changed. */
    fun liveBytes(state: CodeModePersistedState, json: Json): Long = state.records.sumOf { record ->
        if (record.encodedFieldBytes == null) CodeModeKeptState.CellEncoding(record, null, json)
        checkNotNull(record.retainedBytes)
    }

    fun cellText(
        key: String,
        cells: List<CodeModeRecordSnapshot>,
        prior: CodeModeKeptState,
        json: Json,
        path: Path,
    ): String {
        val encoded = cells.map { CodeModeKeptState.CellEncoding(it, prior.records[it.id], json) }
        val encoding = CodeModeKeptState.Encoding(json)
        return if (appendable(path, prior.liveBytesWith(cells))) {
            encoding.patch(key, encoded, prior.expired)
        } else {
            encoding.checkpoint(prior.withCells(cells), encoded)
        }
    }

    fun encode(
        key: String,
        prior: CodeModePersistedState?,
        next: CodeModePersistedState,
        json: Json,
        path: Path? = null,
    ): String {
        val before = prior?.records.orEmpty().associateBy(CodeModeRecordSnapshot::id)
        val changed = next.records.filterNot { same(it, before[it.id]) && before[it.id]?.encodedFieldBytes != null }
        val encoded = changed.map { CodeModeKeptState.CellEncoding(it, before[it.id], json) }
        next.records.filter { same(it, before[it.id]) && before[it.id]?.encodedFieldBytes != null }.forEach {
            it.retainedBytes = before[it.id]?.retainedBytes
            it.encodedFieldBytes = before[it.id]?.encodedFieldBytes
        }
        val removed = before.keys - next.records.map(CodeModeRecordSnapshot::id).toSet()
        val live = next.records.sumOf { checkNotNull(it.retainedBytes) }
        // Deletions compact so the removed payload cannot be recovered from an older journal line.
        val fullState = prior == null || removed.isNotEmpty()
        val cannotAppend = path?.let { !appendable(it, live) } == true
        val encoding = CodeModeKeptState.Encoding(json)
        return if (fullState || cannotAppend) {
            encoding.checkpoint(next, encoded)
        } else {
            encoding.patch(key, encoded, next.expired)
        }
    }
}
