package splice.provider.codex.history.durable

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import splice.core.memory.HeapReservations
import splice.core.memory.HeapText
import splice.core.perf.InputDigest
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodeModeStorageException
import splice.provider.codex.CodeModeStorageOperation
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

/** What one conversation's journal held when this storage last read or wrote it. Replaced whole, never edited, so a
 *  writer that took it works from one consistent view even when [CodeModeHistoryStorage.forget] drops it meanwhile. */
private data class CodeModeJournaled(val revision: Long, val bytes: Long, val digest: String?)

/**
 * Exact history is a journal of shared-prefix deltas, independent of execution-record count and TTL.
 *
 * A journal is bounded without failing its conversation (Nov 9, 2026: one conversation's journal crossed 256 MiB and
 * every later turn of it failed, with no way back). Past three quarters of the bound, or when an append would cross
 * it, the journal is rewritten as its newest posts within a quarter of it ([CodeModeHistoryCheckpoint]), forced to a
 * staging file before its name becomes live. The same rewrite repairs a journal that lost its committed bytes under
 * the head, and carries a conversation that grew while journaling was off: in both the disk is behind the memory,
 * and appending the next delta alone would leave a journal whose first revision is not 1.
 */
internal class CodeModeHistoryStorage(
    private val location: CodeModeStateLocation,
    private val json: Json,
    private val maxBytes: Long,
    private val heap: HeapReservations = JvmHeap.budget,
    private val log: LogSink = LogSink {},
) {
    private val paths = CodeModeHistoryPaths(location.dir)
    private val dir = paths.directory
    private val journal = CodeModeHistoryJournal(json, heap, maxBytes, log)
    private val sweeper = CodeModeHistorySweeper(dir, log)
    private val journaled = ConcurrentHashMap<String, CodeModeJournaled>()
    val durableEnabled: Boolean get() = location.journaling.now()

    fun read(key: String): CodeModeHistoryState? = load(key).first

    private fun load(key: String): Pair<CodeModeHistoryState?, CodeModeJournaled> =
        CodeModeHistoryValidation.guarded(CodeModeStorageOperation.RESTORE) {
            val file = path(key)
            val loaded = when {
                Files.exists(file) -> {
                    val replay = journal.read(file, key)
                    val state = replay.finish()
                    state to CodeModeJournaled(state.revision, replay.committedBytes, replay.lastDigest)
                }
                Files.exists(legacy(key)) -> migrate(key)
                else -> null to CodeModeJournaled(0L, 0L, null)
            }
            journaled[key] = loaded.second
            loaded
        }

    /** Keeps [prepared], the delta that made [state], and returns the state the ledger holds from now on: [state]
     *  itself, or its newest posts once the journal was rewritten. With journaling off nothing reaches the disk; the
     *  first save after it comes back rewrites the journal from the memory. */
    fun write(prepared: CodeModeHistoryPrepared, state: CodeModeHistoryState): CodeModeHistoryState =
        CodeModeHistoryValidation.guarded(CodeModeStorageOperation.SAVE) {
            CodeModeHistoryDeltas.own(prepared, heap)
            if (durableEnabled) append(prepared, state) else state
        }

    /** Drops what this storage knew of [key]'s journal; the next write reads it again. */
    fun forget(key: String) {
        journaled.remove(key)
    }

    /** The files of [keys]' conversations, as a Settings clear must leave them while this head holds them. */
    fun files(keys: Set<String>): Set<Path> = keys.flatMapTo(HashSet()) { key ->
        listOf(path(key), legacy(key)).flatMap { listOf(it, lockOf(it)) }.map { it.toAbsolutePath().normalize() }
    }

    /** Removes the conversation files last written before [beforeMs], except [hot] conversations' (the sweeper says
     *  why), and forgets what it knew of the journals that went. */
    fun sweep(beforeMs: Long, hot: Set<String>): CodeModeHistorySwept =
        sweeper.sweep(beforeMs, hot.mapTo(HashSet()) { InputDigest.hex(it) }).also { swept ->
            journaled.keys.removeIf { InputDigest.hex(it) in swept.gone }
        }

    private fun append(prepared: CodeModeHistoryPrepared, state: CodeModeHistoryState): CodeModeHistoryState {
        val delta = prepared.delta
        val known = journaled[delta.key] ?: load(delta.key).second
        if (delta.revision == known.revision) {
            require(known.digest == journal.digest(delta)) {
                "An uncertain history revision conflicts with durable authority"
            }
        }
        val behind = delta.revision > known.revision + 1L
        return when {
            delta.revision <= known.revision -> state
            behind || known.bytes > maxBytes / CHECKPOINT_QUARTERS * CHECKPOINT_AT -> checkpoint(state, delta.post.id)
            appended(delta, known.bytes) -> state
            else -> checkpoint(state, delta.post.id)
        }
    }

    /** False, with nothing written, when the append would cross the bound or the file lost what was committed. */
    private fun appended(delta: CodeModeHistoryDelta, committedBytes: Long): Boolean {
        val committed = try {
            journal.append(path(delta.key), delta, committedBytes)
        } catch (_: CodeModeStorageException.Limit) {
            null
        } catch (failure: IOException) {
            journaled.remove(delta.key)
            throw failure
        } ?: return false
        journaled[delta.key] = CodeModeJournaled(delta.revision, committed.bytes, committed.digest)
        return true
    }

    /** [state]'s newest posts, [keep]'s among them, rewritten as the whole journal. A post set that cannot fit the
     *  bound even so is refused as before, and the journal on disk stays as it was. */
    private fun checkpoint(state: CodeModeHistoryState, keep: Long): CodeModeHistoryState {
        val key = state.key
        val selection = CodeModeHistoryCheckpoint.select(state, keep, maxBytes / CHECKPOINT_QUARTERS)
        val rebuilt = CodeModeHistoryCheckpoint.rebuild(key, state.nextId, selection.posts)
        val file = path(key)
        val staging = file.resolveSibling("${file.fileName}$CHECKPOINT_SUFFIX")
        var bytes = 0L
        var digest: String? = null
        var live = false
        // Until the rewrite is live the disk is uncertain for this key: the next write reads it again.
        journaled.remove(key)
        try {
            Files.deleteIfExists(staging)
            rebuilt.forEach { prepared ->
                CodeModeHistoryDeltas.own(prepared, heap)
                val committed = checkNotNull(journal.append(staging, prepared.delta, bytes)) {
                    "the history checkpoint's staging file was removed while it was written"
                }
                bytes = committed.bytes
                digest = committed.digest
            }
            Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            live = true
        } finally {
            if (!live) Files.deleteIfExists(staging)
            Files.deleteIfExists(lockOf(staging))
        }
        // The rewrite is live: the memory must take its revisions now, so a failed directory force is said, not thrown.
        try {
            FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
        } catch (failure: IOException) {
            log("[code-mode] history checkpoint not forced to disk (${SafeFailureText.render(failure)})")
        }
        journaled[key] = CodeModeJournaled(rebuilt.size.toLong(), bytes, digest)
        log(
            "[code-mode] authoritative history checkpointed key=${key.take(CHECKPOINT_LOG_KEY_CHARS)} " +
                "posts=${rebuilt.size}/${state.posts.size} bytes=$bytes",
        )
        return CodeModeHistoryState(
            key,
            rebuilt.map { it.entry }.asReversed(),
            state.nextId,
            revision = rebuilt.size.toLong(),
            memoryBytes = rebuilt.sumOf { CodeModeHistoryDeltas.weight(it.delta) },
        )
    }

    /** An intermediate full-snapshot authority is forced to a staging journal before its name becomes live. */
    private fun migrate(key: String): Pair<CodeModeHistoryState, CodeModeJournaled> {
        val old = legacy(key)
        val staging = path(key).resolveSibling("${path(key).fileName}.migrating")
        val migrated = HeapText.Reader.read(old, heap).use { stage ->
            val legacy = json.decodeFromString<CodeModeHistoryState>(stage.text)
            CodeModeHistoryValidation.legacy(legacy, key)
            migrateEntries(legacy, staging)
        }
        Files.move(staging, path(key), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
        Files.deleteIfExists(old)
        Files.deleteIfExists(lockOf(staging))
        log("[code-mode] authoritative history migrated posts=${migrated.first.posts.size}")
        return migrated
    }

    private fun migrateEntries(
        old: CodeModeHistoryState,
        staging: Path,
    ): Pair<CodeModeHistoryState, CodeModeJournaled> {
        Files.deleteIfExists(staging)
        var state = CodeModeHistoryState(old.key, emptyList(), old.nextId)
        var bytes = 0L
        var digest: String? = null
        old.posts.sortedBy { it.id }.forEach { entry ->
            val next = state.copy(revision = state.revision + 1L)
            val prepared = CodeModeHistoryDeltas.prepare(next, entry, state.posts.firstOrNull())
            CodeModeHistoryDeltas.own(prepared, heap)
            val committed = checkNotNull(journal.append(staging, prepared.delta, bytes)) {
                "the history migration's staging file was removed while it was written"
            }
            bytes = committed.bytes
            digest = committed.digest
            state = next.copy(
                posts = listOf(prepared.entry) + state.posts,
                memoryBytes = state.memoryBytes + CodeModeHistoryDeltas.weight(prepared.delta),
            )
        }
        return state to CodeModeJournaled(state.revision, bytes, digest)
    }

    private fun lockOf(file: Path): Path = file.resolveSibling("${file.fileName}$LOCK_SUFFIX")
    private fun path(key: String): Path = paths.journal(key)
    private fun legacy(key: String): Path = paths.legacy(key)
}

// The journal is rewritten past three quarters of its bound, down to its newest posts within one quarter.
private const val CHECKPOINT_QUARTERS = 4L
private const val CHECKPOINT_AT = 3L

// The staging name a checkpoint is forced to before it replaces the live journal.
private const val CHECKPOINT_SUFFIX = ".checkpoint"

// JsonlSink.appendLine's sibling lock file.
private const val LOCK_SUFFIX = ".lock"

// Limit diagnostic keys to a short non-content correlation prefix.
private const val CHECKPOINT_LOG_KEY_CHARS = 8
