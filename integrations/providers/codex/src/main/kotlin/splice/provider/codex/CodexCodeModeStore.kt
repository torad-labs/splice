// NEW: persists bounded code-mode records and expiry markers through atomic 0600 writes, one file per
// conversation (V4-340).
// CREDENTIAL-WRITE-EXEMPT[2026-09-21]: code-mode batch state, never a credential. This file
// persists the code-mode store's own record of admissions, completions and expiry history; it
// holds no token, and a from-scratch write loses nothing another process wrote beside it.
package splice.provider.codex

import kotlinx.serialization.json.Json
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import splice.provider.codex.state.CodeModeKeptState
import splice.provider.codex.state.CodeModeKeyLocks
import splice.provider.codex.state.CodeModeNativeChain
import splice.provider.codex.state.CodeModeStateDirectory
import splice.provider.codex.state.CodeModeStateJournal
import splice.provider.codex.state.CodeModeStateText
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/** The free space on the disk that holds a path, null when it cannot be read. A failed save asks it
 *  so the turn's message can say the disk is full (V4-397). */
internal fun interface StateDiskSpace {
    operator fun invoke(file: Path): Long?

    /** The usable bytes on the file store of the nearest existing ancestor; null when there is none
     *  or the store cannot be read, which never reads as a full disk. */
    object Usable : StateDiskSpace {
        override fun invoke(file: Path): Long? {
            val existing = generateSequence(file.toAbsolutePath()) { it.parent }.firstOrNull { Files.exists(it) }
            return try {
                existing?.let { Files.getFileStore(it).usableSpace }
            } catch (_: IOException) {
                null
            }
        }
    }
}

/** One conversation's atomic owner-only write, injectable for a blocked-key concurrency proof. */
internal fun interface CodeModeStateWrite {
    fun write(path: Path, text: String)
}

/**
 * V4-340: a head's code-mode state is one owner-only file per conversation in [dir], so a save writes the
 * conversations that changed and never the others. The single file every older daemon wrote,
 * [legacyFile], held every record of the head (8.8 MB for 128 records on 2026-09-26) and was rewritten
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
 * FIRST LOAD ON AN UPGRADE. A file that reads back whole is the copy [load] trusts. [legacyFile] fills only
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
) {
    private val dir = location.dir
    private val legacyFile = location.legacyFile
    private val files = CodeModeStateDirectory(dir, json, log)

    @Volatile private var needsSave = false
    private val pendingWrites = AtomicInteger()
    private val failedKeys = ConcurrentHashMap.newKeySet<String>()

    // A failed force can leave complete bytes on disk without publishing them to the durable index.
    private val uncertainKeys = ConcurrentHashMap.newKeySet<String>()

    /** Each conversation as its file held it when last read or written, by conversation key. */
    private val kept = ConcurrentHashMap<String, CodeModeKeptState>()

    private val savePreparation = SavePreparation()

    /** Failed keys outlive their last record, so TTL purges keep retrying until disk agrees. */
    val pendingKeys: Set<String> get() = failedKeys.toSet()

    private data class Prepared(
        val key: String,
        val conversation: CodeModePersistedState?,
        val cells: List<PreparedCell> = emptyList(),
        val nativeRoots: List<PreparedCell> = emptyList(),
    )

    private data class PreparedCell(val live: CodeModeRecord, val snapshot: CodeModeRecordSnapshot)

    /** A conversation as its file will hold it. */
    private data class Encoded(val key: String, val conversation: CodeModePersistedState, val text: String)

    /** Every conversation the directory holds, then the ones only [legacyFile] holds, written to their files. */
    fun load(): CodeModePersistedState {
        readDirectory()
        val legacy = readLegacy()
        val carried = legacy.orEmpty().filterKeys { !kept.containsKey(it) }
        val stays = "${legacyFile.fileName} stays"
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
        } finally {
            registryLock?.lock()
            needsSave = pendingWrites.decrementAndGet() > 0 || failedKeys.isNotEmpty()
        }
    }

    private inner class SavePreparation {
        fun saveKey(
            key: String,
            records: List<CodeModeRecord>,
            expired: List<CodeModeExpiredSnapshot>,
            changedRecord: CodeModeRecord?,
        ) {
            val entry = keyLocks.acquire(key)
            try {
                registryLock?.lock()
                val prepared = try {
                    prepare(key, records, expired, changedRecord)
                } finally {
                    registryLock?.unlock()
                }
                prepared?.let(::persist)
                registryLock?.lock()
                try {
                    publishSizes(key, records, changedRecord, prepared)
                } finally {
                    registryLock?.unlock()
                }
                uncertainKeys.remove(key)
                failedKeys.remove(key)
            } finally {
                keyLocks.release(key, entry)
            }
        }

        private fun publishSizes(
            key: String,
            records: List<CodeModeRecord>,
            changedRecord: CodeModeRecord?,
            prepared: Prepared?,
        ) {
            prepared?.nativeRoots.orEmpty().forEach { CodeModeNativeChain.publishRoot(it.live, it.snapshot) }
            val changed = prepared?.cells.orEmpty()
            if (changed.isNotEmpty()) {
                changed.forEach { it.live.retainedBytes = it.snapshot.retainedBytes }
                return
            }
            val sizes = kept[key]?.records.orEmpty()
            if (changedRecord != null) {
                changedRecord.retainedBytes = sizes[changedRecord.id]?.retainedBytes
            } else {
                records.filter { it.key == key }.forEach { record ->
                    record.retainedBytes = sizes[record.id]?.retainedBytes
                }
            }
        }

        fun prepare(
            key: String,
            records: List<CodeModeRecord>,
            expired: List<CodeModeExpiredSnapshot>,
            changedRecord: CodeModeRecord?,
        ): Prepared? {
            val indexed = kept[key]
            if (canPrepareCells(changedRecord, records, indexed)) {
                return prepareCells(key, checkNotNull(indexed), checkNotNull(changedRecord))
            }
            val prior = indexed?.snapshot()
            val live = records.filter { it.key == key }
            val retained = live.map { it.id }.toSet()
            val snapshots = live.map { record ->
                record.saveGeneration++
                PreparedCell(record, CodeModeNativeChain.snapshot(record, retained))
            }
            val nextRecords = snapshots.map { it.snapshot }
            val roots = snapshots.filter { it.live.nativeBaseId != it.snapshot.nativeBaseId }
            val before = prior?.records.orEmpty().associateBy(CodeModeRecordSnapshot::id)
            nextRecords.forEach { snapshot ->
                val old = before[snapshot.id]
                if (CodeModeStateJournal.same(snapshot, old)) snapshot.retainedBytes = old?.retainedBytes
            }
            val markers = expired.filter { it.key == key }
            val next = CodeModePersistedState(records = nextRecords, expired = markers)
                .takeUnless { it.records.isEmpty() && it.expired.isEmpty() }
            val changed = key in uncertainKeys || next != prior || next?.records?.zip(prior?.records.orEmpty())
                ?.any { (left, right) -> !CodeModeStateJournal.same(left, right) } == true
            return if (changed) Prepared(key, next, nativeRoots = roots) else null
        }

        private fun canPrepareCells(
            record: CodeModeRecord?,
            records: List<CodeModeRecord>,
            indexed: CodeModeKeptState?,
        ): Boolean {
            if (record == null || indexed == null) return false
            if (record.key in uncertainKeys) return false
            return record in records && indexed.dirty.values.all { it in records }
        }

        private fun prepareCells(key: String, indexed: CodeModeKeptState, record: CodeModeRecord): Prepared? {
            indexed.dirty[record.id] = record
            val cells = indexed.dirty.values.map { live ->
                live.saveGeneration++
                PreparedCell(live, live.snapshot())
            }.filterNot { CodeModeStateJournal.same(it.snapshot, indexed.records[it.snapshot.id]) }
            if (cells.isEmpty()) {
                indexed.dirty.clear()
                return null
            }
            return Prepared(key, null, cells)
        }

        fun cellText(key: String, cells: List<CodeModeRecordSnapshot>): String =
            CodeModeStateJournal.cellText(key, cells, checkNotNull(kept[key]), json, fileOf(key))
    }

    private fun persist(item: Prepared) {
        var bytes = 0L
        try {
            val conversation = item.conversation
            val cells = item.cells.map(PreparedCell::snapshot)
            if (cells.isNotEmpty()) {
                val text = savePreparation.cellText(item.key, cells)
                bytes = CodeModeStateText(text).bytes
                secureDirectory()
                writer.write(fileOf(item.key), text)
                val indexed = checkNotNull(kept[item.key])
                indexed.put(cells)
                indexed.dirty.clear()
            } else if (conversation == null) {
                remove(item.key)
            } else {
                val prior = kept[item.key]?.snapshot().takeUnless { item.key in uncertainKeys }
                val text = CodeModeStateJournal.encode(item.key, prior, conversation, json, fileOf(item.key))
                bytes = CodeModeStateText(text).bytes
                secureDirectory()
                write(Encoded(item.key, conversation, text))
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
            kept[key] = CodeModeKeptState(selected.state)
            if (selected.checkpoint) checkpoint(key, selected.state, "the selected conversation stays")
        }
    }

    /** Every conversation [legacyFile] holds, or null when there is none or it could not be read. */
    private fun readLegacy(): Map<String, CodeModePersistedState>? {
        if (Files.notExists(legacyFile)) return null
        return try {
            grouped(json.decodeFromString(String(Files.readAllBytes(legacyFile), Charsets.UTF_8)))
        } catch (failure: IOException) {
            log("[code-mode] ${legacyFile.fileName} not read (${SafeFailureText.render(failure)}): it stays")
            null
        } catch (_: IllegalArgumentException) {
            log("[code-mode] ${legacyFile.fileName} is not code-mode state: removed, so its scripts are dropped")
            removeLegacy()
            null
        }
    }

    /** Writes [conversation] to its file as one checkpoint. On failure what [stays] is logged, and the
     *  conversation's next save writes it. */
    private fun checkpoint(key: String, conversation: CodeModePersistedState, stays: String): Boolean = try {
        secureDirectory()
        write(Encoded(key, conversation, CodeModeStateJournal.encode(key, null, conversation, json)))
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
            Files.deleteIfExists(legacyFile)
        } catch (failure: IOException) {
            log("[code-mode] ${legacyFile.fileName} not removed (${SafeFailureText.render(failure)})")
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
        kept[file.key] = CodeModeKeptState(file.conversation)
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
