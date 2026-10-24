package splice.provider.codex.history.durable

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import splice.core.memory.HeapReservations
import splice.core.memory.HeapText
import splice.core.perf.InputDigest
import splice.core.util.LogSink
import splice.provider.codex.CodeModePersistenceException
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.history.CodeModeLedgerEntry
import splice.upstream.memory.JvmHeap
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

@Serializable
internal data class CodeModeHistoryState(
    val key: String,
    val posts: List<CodeModeLedgerEntry>,
    val nextId: Long,
    val version: Int = 1,
    val revision: Long = 0,
    @Transient val memoryBytes: Long = 0,
)

/** Exact history is a journal of shared-prefix deltas, independent of execution-record count and TTL. */
internal class CodeModeHistoryStorage(
    private val location: CodeModeStateLocation,
    private val json: Json,
    maxBytes: Long,
    private val heap: HeapReservations = JvmHeap.budget,
    private val log: LogSink = LogSink {},
) {
    private val dir = location.dir.resolve("history")
    private val journal = CodeModeHistoryJournal(json, heap, maxBytes, log)
    private val written = ConcurrentHashMap<String, Long>()
    private val offsets = ConcurrentHashMap<String, Long>()
    private val latest = ConcurrentHashMap<String, String>()
    private val pending = ConcurrentHashMap<String, MutableList<CodeModeHistoryPrepared>>()
    val durableEnabled: Boolean get() = location.journaling.now()

    fun read(key: String): CodeModeHistoryState? = guarded {
        val file = path(key)
        val state = when {
            Files.exists(file) -> {
                val replay = journal.read(file, key)
                offsets[key] = replay.committedBytes
                replay.lastDigest?.let { latest[key] = it }
                replay.finish()
            }
            Files.exists(legacy(key)) -> migrate(key)
            else -> null
        }
        written[key] = state?.revision ?: 0L
        state
    }

    fun write(prepared: CodeModeHistoryPrepared) = guarded {
        CodeModeHistoryDeltas.own(prepared, heap)
        val key = prepared.delta.key
        if (!durableEnabled) {
            pending.getOrPut(key) { mutableListOf() }.add(prepared)
        } else {
            val queued = pending[key].orEmpty() + prepared
            queued.forEach(::append)
            pending.remove(key)
        }
    }

    private fun append(prepared: CodeModeHistoryPrepared) {
        val delta = prepared.delta
        val prior = written[delta.key] ?: read(delta.key)?.revision ?: 0L
        if (delta.revision == prior) {
            require(
                latest[delta.key] == journal.digest(delta),
            ) { "An uncertain history revision conflicts with durable authority" }
            return
        }
        if (delta.revision < prior) return
        require(delta.revision == prior + 1L) { "Authoritative history append is missing a parent revision" }
        try {
            val committed = journal.append(path(delta.key), delta, offsets[delta.key] ?: 0L)
            offsets[delta.key] = committed.bytes
            latest[delta.key] = committed.digest
            written[delta.key] = delta.revision
        } catch (failure: IOException) {
            written.remove(delta.key)
            offsets.remove(delta.key)
            throw failure
        }
    }

    /** An intermediate full-snapshot authority is forced to a staging journal before its name becomes live. */
    private fun migrate(key: String): CodeModeHistoryState {
        val old = legacy(key)
        val staging = path(key).resolveSibling("${path(key).fileName}.migrating")
        val state = HeapText.Reader.read(old, heap).use { stage ->
            val legacy = json.decodeFromString<CodeModeHistoryState>(stage.text)
            require(legacy.key == key && legacy.version == 1) { "Legacy authoritative history identity is invalid" }
            migrateEntries(legacy, staging)
        }
        Files.move(staging, path(key), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
        Files.deleteIfExists(old)
        Files.deleteIfExists(staging.resolveSibling("${staging.fileName}.lock"))
        log("[code-mode] authoritative history migrated posts=${state.posts.size}")
        return state
    }

    private fun migrateEntries(old: CodeModeHistoryState, staging: Path): CodeModeHistoryState {
        Files.deleteIfExists(staging)
        var state = CodeModeHistoryState(old.key, emptyList(), old.nextId)
        var bytes = 0L
        old.posts.sortedBy { it.id }.forEach { entry ->
            val next = state.copy(revision = state.revision + 1L)
            val prepared = CodeModeHistoryDeltas.prepare(next, entry, state.posts.firstOrNull())
            CodeModeHistoryDeltas.own(prepared, heap)
            val committed = journal.append(staging, prepared.delta, bytes)
            bytes = committed.bytes
            latest[old.key] = committed.digest
            state = next.copy(
                posts = listOf(prepared.entry) + state.posts,
                memoryBytes = state.memoryBytes + CodeModeHistoryDeltas.weight(prepared.delta),
            )
        }
        offsets[old.key] = bytes
        return state
    }

    private fun path(key: String): Path = dir.resolve(InputDigest.hex(key) + ".jsonl")
    private fun legacy(key: String): Path = dir.resolve(InputDigest.hex(key) + ".json")

    private inline fun <T> guarded(operation: () -> T): T = try {
        operation()
    } catch (error: SerializationException) {
        throw CodeModePersistenceException(IOException("authoritative history cannot be decoded", error), false)
    } catch (error: IllegalArgumentException) {
        throw CodeModePersistenceException(IOException("authoritative history revision is invalid", error), false)
    } catch (error: IOException) {
        throw CodeModePersistenceException(error, false)
    }
}
