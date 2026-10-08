// NEW: persists bounded code-mode records and expiry markers through atomic 0600 writes, one file per
// conversation (V4-340).
// CREDENTIAL-WRITE-EXEMPT[2026-09-21]: code-mode batch state, never a credential. This file
// persists the code-mode store's own record of admissions, completions and expiry history; it
// holds no token, and a from-scratch write loses nothing another process wrote beside it.
package splice.provider.codex

import kotlinx.serialization.json.Json
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapReservations
import splice.core.memory.HeapText
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import splice.provider.codex.state.CodeModeKeptState
import splice.provider.codex.state.CodeModeKeyLocks
import splice.provider.codex.state.CodeModeStateDirectory
import splice.provider.codex.state.CodeModeStateJournal
import splice.provider.codex.state.CodeModeStateText
import splice.provider.codex.state.save.CodeModePreparedWrite
import splice.provider.codex.state.save.CodeModeSaveHeap
import splice.provider.codex.state.save.CodeModeSavePreparation
import splice.provider.codex.state.save.CodeModeSaveSnapshots
import splice.provider.codex.state.save.CodeModeSaveSnapshots.Prepared
import splice.provider.codex.state.save.StateDiskSpace
import splice.upstream.memory.JvmHeap
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import splice.provider.codex.state.save.CodeModeSaveSnapshots.Cell as PreparedCell

/** One conversation's atomic owner-only write, injectable for a blocked-key concurrency proof. */
internal fun interface CodeModeStateWrite {
    fun write(path: Path, text: String)
}

/**
 * V4-340: a head's code-mode state is one owner-only file per conversation in [dir], so a save writes the
 * conversations that changed and never the others. The single file every older daemon wrote,
 * the legacy file, held every record of the head (8.8 MB for 128 records on 2026-09-26) and was rewritten
 * whole on each save.
 *
 * A conversation's file holds that conversation's records and its expiry markers in the shape the single
 * file had, and is named by the hash of the conversation's key, which is only ever a name: the file's
 * content says which conversation it is, and a file whose content is not exactly the one conversation its
 * name says is corrupt. Checkpoints are swapped atomically and cell deltas are forced JSONL appends;
 * recovery ignores only the uncommitted final fragment. New journals use .jsonl so an older .json-only
 * daemon cannot discover and delete patch-bearing state. If a downgrade creates a fresh .json beside it,
 * the next upgrade selects the latest record or expiry timestamp. Divergent clock ties use file time;
 * identical states prefer .jsonl.
 * Migration forces the replacement before removing the older copy. A multi-conversation save is not
 * atomic across files. A failed write marks disk uncertain, and the next save replaces the full state,
 * even if every live field rolled back to [kept]'s last successfully committed snapshot.
 *
 * FIRST LOAD ON AN UPGRADE. A file that reads back whole is the copy [load] trusts. The legacy file fills only
 * the conversations that have no such file, each is written to its own file, and it is deleted once every
 * one is written. So a crash between the writes and the delete loads each conversation once, from its own
 * file where it has one and from the legacy copy where it has not; nothing is loaded twice or lost. A
 * conversation whose file does not read back is dropped alone, with a log line, and never fails the head.
 */
internal class CodexCodeModeStore(
    private val location: CodeModeStateLocation,
    private val json: Json,
    private val log: LogSink,
    private val freeBytes: StateDiskSpace = StateDiskSpace.Usable,
    private val writer: CodeModeStateWrite = CodeModeStateWrite { path, text ->
        CodeModeStateJournal.write(path, text)
    },
    private val registryLock: ReentrantLock? = null,
    private val keyLocks: CodeModeKeyLocks = CodeModeKeyLocks(),
    private val heap: HeapReservations = JvmHeap.budget,
) {
    private val dir = location.dir
    private val files = CodeModeStateDirectory(dir, json, log, heap)

    @Volatile private var needsSave = false
    private val pendingWrites = AtomicInteger()
    private val failedKeys = ConcurrentHashMap.newKeySet<String>()

    // A failed force can leave complete bytes on disk without publishing them to the durable index.
    private val uncertainKeys = ConcurrentHashMap.newKeySet<String>()

    /** Each conversation as its file held it when last read or written, by conversation key. */
    private val kept = ConcurrentHashMap<String, CodeModeKeptState>()

    private val capacity = CodeModeSaveHeap(heap)
    private val snapshots = CodeModeSaveSnapshots(kept, uncertainKeys, capacity)
    private val savePreparation = CodeModeSavePreparation(
        snapshots,
        keyLocks,
        registryLock,
        kept,
        uncertainKeys,
        failedKeys,
        CodeModePreparedWrite(::persist),
    )

    /** Failed keys outlive their last record, so TTL purges keep retrying until disk agrees. */
    val pendingKeys: Set<String> get() = failedKeys.toSet()

    /** Terminal disposal may drop hot snapshots only after every write and failed purge settles. */
    val settled: Boolean
        get() = !needsSave && pendingWrites.get() == 0 && failedKeys.isEmpty() && uncertainKeys.isEmpty()

    /** Drops only in-memory owners. Durable files and escaped graph owners are left intact. */
    fun release(): Boolean {
        if (!settled) return false
        kept.clear()
        return true
    }

    /** A conversation as its file will hold it. */
    private data class Encoded(val key: String, val indexed: CodeModeKeptState, val text: String)

    /** Every conversation the directory holds, then the ones only the legacy file holds, written to their files. */
    fun load(): CodeModePersistedState {
        readDirectory()
        val legacy = readLegacy()
        val carried = legacy.orEmpty().filterKeys { !kept.containsKey(it) }
        val stays = "${location.legacyFile.fileName} stays"
        val written = carried.count { (key, conversation) -> checkpoint(key, conversation, stays) }
        if (legacy != null && written == carried.size) {
            removeLegacy()
        }
        return merged(kept.mapValues { it.value.snapshot() } + carried)
    }

    /** Writes each conversation of the given state that differs from what the disk holds, and removes the
     *  file of each that has neither a record nor a marker left. Failing part way leaves [kept] naming what
     *  was written, and the next save (a [retryOnly] one included) writes the rest. */
    fun save(
        records: List<CodeModeRecord>,
        expired: List<CodeModeExpiredSnapshot>,
        retryOnly: Boolean = false,
        dirtyKeys: Set<String>? = null,
        changedRecord: CodeModeRecord? = null,
    ) {
        if (retryOnly && !needsSave) return
        needsSave = true
        // The registry lock protects mutable records. Snapshot only changed conversations there;
        // serialization and disk I/O take place outside that head-wide lock.
        val keys = dirtyKeys ?: buildSet {
            addAll(records.map(CodeModeRecord::key))
            addAll(expired.map(CodeModeExpiredSnapshot::key))
            addAll(kept.keys)
        }
        val selected = if (retryOnly) keys.intersect(failedKeys) else keys
        val retryKeys = selected.intersect(failedKeys)
        pendingWrites.incrementAndGet()
        failedKeys.addAll(selected)
        registryLock?.unlock()
        try {
            // Reserve each key only when it is about to write. A blocked A never reserves B.
            selected.forEach { key ->
                val cell = changedRecord.takeUnless { key in retryKeys }
                savePreparation.saveKey(key, records, expired, cell)
            }
        } catch (capacity: HeapCapacityException) {
            // A pre-snapshot refusal is the same failed force contract, before any generation advances.
            throw CodeModePersistenceException(capacity, diskFull = false)
        } finally {
            registryLock?.lock()
            needsSave = pendingWrites.decrementAndGet() > 0 || failedKeys.isNotEmpty()
        }
    }

    private fun persist(item: Prepared) {
        var bytes = 0L
        try {
            val conversation = item.conversation
            val cells = item.cells.map(PreparedCell::snapshot)
            val stage: CodeModeSaveHeap.Encoding = if (conversation == null) {
                capacity.encoding(cells, kept[item.key]?.expired.orEmpty())
            } else {
                capacity.full(conversation)
            }
            stage.use { peak ->
                if (cells.isNotEmpty()) {
                    val text = CodeModeStateJournal.cellText(
                        item.key,
                        cells,
                        checkNotNull(kept[item.key]),
                        json,
                        fileOf(item.key),
                        peak,
                    )
                    peak.retain(text)
                    bytes = CodeModeStateText(text).bytes
                    val indexed = checkNotNull(kept[item.key])
                    indexed.prepare(cells)
                    secureDirectory()
                    writer.write(fileOf(item.key), text)
                    indexed.put(cells)
                    indexed.dirty.clear()
                } else if (conversation == null) {
                    remove(item.key)
                } else {
                    val prior = kept[item.key]?.snapshot().takeUnless { item.key in uncertainKeys }
                    val text = CodeModeStateJournal.encode(item.key, prior, conversation, json, fileOf(item.key), peak)
                    peak.retain(text)
                    bytes = CodeModeStateText(text).bytes
                    val indexed = CodeModeKeptState(conversation.version, conversation, heap)
                    secureDirectory()
                    write(Encoded(item.key, indexed, text))
                }
            }
        } catch (error: IOException) {
            uncertainKeys.add(item.key)
            failedKeys.add(item.key)
            val free = freeBytes(dir)
            throw CodeModePersistenceException(error, diskFull = free != null && free < bytes)
        }
    }

    private fun readDirectory() {
        files.load().forEach { (key, selected) ->
            kept[key] = CodeModeKeptState(selected.state.version, selected.state, heap)
            if (selected.checkpoint) checkpoint(key, selected.state, "the selected conversation stays")
        }
    }

    /** Every conversation the legacy file holds, or null when there is none or it could not be read. */
    private fun readLegacy(): Map<String, CodeModePersistedState>? {
        if (Files.notExists(location.legacyFile)) return null
        return try {
            HeapText.Reader.read(location.legacyFile, heap).use { staged ->
                grouped(json.decodeFromString(staged.text)).also { states -> states.values.forEach(staged::retain) }
            }
        } catch (failure: IOException) {
            log("[code-mode] ${location.legacyFile.fileName} not read (${SafeFailureText.render(failure)}): it stays")
            null
        } catch (_: IllegalArgumentException) {
            log(
                "[code-mode] ${location.legacyFile.fileName} is not code-mode state: " +
                    "removed, so its scripts are dropped",
            )
            removeLegacy()
            null
        }
    }

    /** Writes [conversation] to its file as one checkpoint. On failure what [stays] is logged, and the
     *  conversation's next save writes it. */
    private fun checkpoint(key: String, conversation: CodeModePersistedState, stays: String): Boolean = try {
        capacity.full(conversation).use { peak ->
            val text = CodeModeStateJournal.encode(key, null, conversation, json, capacity = peak)
            peak.retain(text)
            val indexed = CodeModeKeptState(conversation.version, conversation, heap)
            secureDirectory()
            write(Encoded(key, indexed, text))
        }
        true
    } catch (failure: IOException) {
        uncertainKeys.add(key)
        log(
            "[code-mode] conversation ${key.take(CONVERSATION_LOG_CHARS)} not written to $dir " +
                "(${SafeFailureText.render(failure)}): $stays, and the next save writes it",
        )
        false
    }

    private fun removeLegacy() {
        try {
            Files.deleteIfExists(location.legacyFile)
        } catch (failure: IOException) {
            log("[code-mode] ${location.legacyFile.fileName} not removed (${SafeFailureText.render(failure)})")
        }
    }

    private fun secureDirectory() {
        val open = SecureFile.ownerOnlyDirectory(dir)
        if (open != null) throw IOException("the code-mode state directory $dir is not owner-only: $open")
    }

    private fun write(file: Encoded) {
        writer.write(fileOf(file.key), file.text)
        // Remove the downgrade-visible copy only after the new checkpoint and rename are durable.
        if (Files.deleteIfExists(files.olderPath(file.key))) {
            FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
        }
        kept[file.key] = file.indexed
    }

    private fun remove(key: String) {
        Files.deleteIfExists(fileOf(key))
        Files.deleteIfExists(files.olderPath(key))
        if (Files.exists(dir)) FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
        kept.remove(key)
    }

    private fun fileOf(key: String): Path = files.path(key)

    /** [state] by conversation key: its records and its markers. */
    private fun grouped(state: CodeModePersistedState): Map<String, CodeModePersistedState> {
        val records = state.records.groupBy(CodeModeRecordSnapshot::key)
        val markers = state.expired.groupBy(CodeModeExpiredSnapshot::key)
        return (records.keys + markers.keys).associateWith { key ->
            CodeModePersistedState(records = records[key].orEmpty(), expired = markers[key].orEmpty())
        }
    }

    /** The conversations as one state: the least recently used first, each in its own record order, and
     *  the markers in the order they were made. */
    private fun merged(conversations: Map<String, CodeModePersistedState>): CodeModePersistedState {
        val oldestFirst = conversations.entries.sortedWith(
            compareBy<Map.Entry<String, CodeModePersistedState>>(
                { entry -> entry.value.records.maxOfOrNull(CodeModeRecordSnapshot::updatedAt) ?: 0L },
                { entry -> entry.key },
            ),
        )
        return CodeModePersistedState(
            records = oldestFirst.flatMap { it.value.records },
            expired = oldestFirst.flatMap { it.value.expired }.sortedBy(CodeModeExpiredSnapshot::expiredAt),
        )
    }
}

// why: the retention log names a conversation by the first 8 characters of its key (RECORD_ID_LOG_CHARS in
// CodexCodeModeSweeper.kt), enough to tell a head's conversations apart in daemon.log; this names it the same way.
private const val CONVERSATION_LOG_CHARS = 8
