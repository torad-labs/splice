package splice.provider.codex.history

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapCategory
import splice.core.memory.HeapOwners
import splice.core.memory.HeapReservations
import splice.core.memory.HeapText
import splice.core.perf.InputDigest
import splice.core.util.SecureFile
import splice.provider.codex.CodeModePersistenceException
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.state.CodeModeStateText
import splice.provider.codex.state.CodeModeWeight
import splice.upstream.memory.JvmHeap
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

@Serializable
internal data class CodeModeHistoryState(
    val key: String,
    val posts: List<CodeModeLedgerEntry>,
    val nextId: Long,
    val version: Int = 1,
)

/** A missing file is a legacy conversation. An unreadable authority is an error, never permission to reconstruct. */
internal class CodeModeHistoryStorage(
    private val location: CodeModeStateLocation,
    private val json: Json,
    private val maxBytes: Long,
    private val heap: HeapReservations = JvmHeap.budget,
) {
    private val dir = location.dir.resolve("history")

    fun read(key: String): CodeModeHistoryState? = guarded {
        val file = path(key)
        try {
            if (Files.size(file) > maxBytes) throw IOException("authoritative code-mode history exceeds its byte bound")
            HeapText.Reader.read(file, heap).use { stage ->
                val state = json.decodeFromString<CodeModeHistoryState>(stage.text)
                validate(state, key)
                stage.retain(state)
                state.posts.forEach(stage::retain)
                state
            }
        } catch (_: NoSuchFileException) {
            null
        }
    }

    private fun validate(state: CodeModeHistoryState, key: String) {
        val identity = state.key == key && state.version == 1
        val ids = state.posts.map { it.id }
        val ordered = ids.all { it > 0L } && state.nextId > (ids.maxOrNull() ?: 0L)
        val unique = ids.distinct().size == ids.size
        val valid = identity && ordered && unique
        if (!valid) {
            throw IOException("authoritative code-mode history has invalid identity")
        }
    }

    /** Force the replacement before publishing it to the ledger. The journal toggle is an explicit memory mode. */
    fun write(state: CodeModeHistoryState) = guarded {
        val weight = state.posts.sumOf { entry ->
            entry.covered.sumOf(CodeModeWeight.STORED::json) + entry.sent.sumOf(CodeModeWeight.STORED::json) +
                entry.echoes.sumOf(CodeModeWeight.STORED::json)
        } + HISTORY_METADATA_BYTES
        val lease = HeapOwners.charge(state, heap, weight, HeapCategory.CODE_MODE)
        state.posts.forEach { HeapOwners.keep(it, lease.share()) }
        if (location.journaling.now()) {
            val stage = heap.reserve(weight * ENCODING_EXPANSION) ?: throw HeapCapacityException()
            stage.use {
                val text = json.encodeToString(CodeModeHistoryState.serializer(), state)
                if (CodeModeStateText(text).bytes > maxBytes) {
                    throw IOException("authoritative code-mode history exceeds its byte bound")
                }
                val open = SecureFile.ownerOnlyDirectory(dir)
                if (open != null) throw IOException("authoritative code-mode history directory is not owner-only")
                SecureFile.writeAtomic0600(path(state.key), text)
                FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
            }
        }
        // The owner retains this reservation; the reference makes the intended ownership explicit.
        check(lease.bytes >= weight)
    }

    private fun path(key: String): Path = dir.resolve(InputDigest.hex(key) + ".json")

    private inline fun <T> guarded(operation: () -> T): T = try {
        operation()
    } catch (error: SerializationException) {
        throw CodeModePersistenceException(
            IOException("authoritative code-mode history cannot be decoded", error),
            false,
        )
    } catch (error: IOException) {
        throw CodeModePersistenceException(error, false)
    }
}

private const val HISTORY_METADATA_BYTES = 256L
private const val ENCODING_EXPANSION = 4L
