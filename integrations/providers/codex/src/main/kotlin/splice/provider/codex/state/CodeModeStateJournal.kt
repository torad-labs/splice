// NEW: one conversation checkpoint plus durable per-cell deltas, without rewriting earlier cell payloads.
// CREDENTIAL-WRITE-EXEMPT[2026-09-30]: private code-mode state, never credentials; compaction keeps every live cell.
package splice.provider.codex.state

import kotlinx.serialization.json.Json
import splice.core.memory.HeapBudget
import splice.core.util.Cancellables
import splice.core.util.JsonlForce
import splice.core.util.JsonlSink
import splice.core.util.SecureFile
import splice.provider.codex.CodeModePersistedState
import splice.provider.codex.CodeModeRecordSnapshot
import splice.provider.codex.state.save.CodeModeSaveHeap
import splice.upstream.memory.JvmHeap
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Full checkpoints and old full-cell deltas still load. Patch lines deliberately omit legacy deltas'
 * mandatory records field: an older decoder rejects them rather than restoring incomplete cells.
 * The store writes these journals as .jsonl, outside older jars' .json discovery, so a downgrade
 * preserves them instead of treating the rejected patch as corruption and deleting the conversation.
 */
internal object CodeModeStateJournal {
    internal const val DELTA_START = "{\"key\":"

    // why: under 8 MiB a full rewrite costs less than the bookkeeping to avoid it, so small journals
    // never compact; Oct 1's 2 GB file held 4 MB of live cells.
    private const val COMPACT_FLOOR_BYTES = 8L * 1024 * 1024

    // why: a journal is rewritten once it holds four times its live cells, so the rewrite's cost is
    // amortized over at least three journals' worth of appends.
    private const val COMPACT_RATIO = 4
    private val codec = Json { encodeDefaults = true }
    fun same(left: CodeModeRecordSnapshot?, right: CodeModeRecordSnapshot?): Boolean =
        CodeModeJournalEncoding.same(left, right)

    /** Called under the conversation lock. First write creates a 0600 checkpoint; appends are forced. */
    fun write(
        path: Path,
        text: String,
        force: JsonlForce = JsonlForce { _, channel -> channel.force(true) },
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
            JsonlSink.appendLine(
                path,
                text,
                maxBytes = Long.MAX_VALUE,
                archive = JsonlSink.NO_ARCHIVE,
                force = force,
            )
        }
    }

    private class Checkpoint(private val force: JsonlForce) {
        fun write(path: Path, text: String) {
            val parent = path.toAbsolutePath().parent
            Files.createDirectories(parent)
            val temporary = Files.createTempFile(parent, ".code-mode", ".tmp")
            try {
                // Reuse the secure writer without changing credential writes' existing contract.
                SecureFile.writeAtomic0600(temporary, String((text + "\n").toByteArray(Charsets.UTF_8), Charsets.UTF_8))
                FileChannel.open(temporary, StandardOpenOption.WRITE).use { force.force(temporary, it) }
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                FileChannel.open(parent, StandardOpenOption.READ).use { force.force(parent, it) }
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
            write(path, encode("", null, recovered, codec))
        }
    }

    /** Counts each source entry before allocating text. The complete pretty checkpoint is budgeted too.
     *  Only newline-terminated deltas commit; an uncommitted final fragment is never applied. */
    fun read(path: Path, json: Json, heap: HeapBudget = JvmHeap.budget): CodeModePersistedState =
        CodeModeStateRestore.read(path, json, heap)

    internal fun endsWithNewline(path: Path): Boolean =
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

    /** Measures a loaded cell once; subsequent saves update only changed field sizes. */
    fun liveBytes(state: CodeModePersistedState, json: Json, heap: HeapBudget = JvmHeap.budget): Long =
        CodeModeJournalEncoding.liveBytes(state, json, heap)

    fun cellText(
        key: String,
        cells: List<CodeModeRecordSnapshot>,
        prior: CodeModeKeptState,
        json: Json,
        path: Path,
        capacity: CodeModeSaveHeap.Encoding? = null,
    ): String = CodeModeJournalEncoding.cellText(key, cells, prior, json, path, capacity)

    fun encode(
        key: String,
        prior: CodeModePersistedState?,
        next: CodeModePersistedState,
        json: Json,
        path: Path? = null,
        capacity: CodeModeSaveHeap.Encoding? = null,
    ): String = CodeModeJournalEncoding.encode(key, prior, next, json, path, capacity)
}
