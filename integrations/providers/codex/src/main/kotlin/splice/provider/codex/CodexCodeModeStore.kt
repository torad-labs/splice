// NEW: persists bounded code-mode records and expiry markers through atomic 0600 writes, one file per
// conversation (V4-340).
// CREDENTIAL-WRITE-EXEMPT[2026-09-21]: code-mode batch state, never a credential. This file
// persists the code-mode store's own record of admissions, completions and expiry history; it
// holds no token, and a from-scratch write loses nothing another process wrote beside it.
package splice.provider.codex

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import splice.provider.codex.state.CodeModeKeyLocks
import splice.provider.codex.state.CodeModeStateJournal
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
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
 * recovery ignores only the uncommitted final fragment. A multi-conversation save is not atomic across
 * files, which a failure repairs at the next save because [kept] names what disk holds, not the intent.
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

    @Volatile private var needsSave = false
    private val pendingWrites = AtomicInteger()
    private val failedKeys = ConcurrentHashMap.newKeySet<String>()

    /** Each conversation as its file held it when last read or written, by conversation key. */
    private val kept = ConcurrentHashMap<String, CodeModePersistedState>()

    private val savePreparation = SavePreparation()

    /** Failed keys outlive their last record, so TTL purges keep retrying until disk agrees. */
    val pendingKeys: Set<String> get() = failedKeys.toSet()

    private data class Prepared(
        val key: String,
        val conversation: CodeModePersistedState?,
    )

    /** A conversation as its file will hold it. */
    private data class Encoded(val key: String, val conversation: CodeModePersistedState, val text: String)

    /** Every conversation the directory holds, then the ones only [legacyFile] holds, written to their files. */
    fun load(): CodeModePersistedState {
        readDirectory()
        val legacy = readLegacy()
        val carried = legacy.orEmpty().filterKeys { !kept.containsKey(it) }
        if (legacy != null && carried.count { (key, conversation) -> carry(key, conversation) } == carried.size) {
            removeLegacy()
        }
        return merged(kept + carried)
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
        pendingWrites.incrementAndGet()
        failedKeys.addAll(selected)
        registryLock?.unlock()
        try {
            // Reserve each key only when it is about to write. A blocked A never reserves B.
            selected.forEach { key -> savePreparation.saveKey(key, records, expired, changedRecord) }
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
                val sizes = kept[key]?.records.orEmpty().associate { it.id to it.retainedBytes }
                registryLock?.lock()
                try {
                    records.filter { it.key == key }.forEach { record -> record.retainedBytes = sizes[record.id] }
                } finally {
                    registryLock?.unlock()
                }
                failedKeys.remove(key)
            } finally {
                keyLocks.release(key, entry)
            }
        }

        fun prepare(
            key: String,
            records: List<CodeModeRecord>,
            expired: List<CodeModeExpiredSnapshot>,
            changedRecord: CodeModeRecord?,
        ): Prepared? {
            val prior = kept[key]
            val nextRecords = if (changedRecord != null && prior != null) {
                changedRecord.saveGeneration++
                val snapshot = changedRecord.snapshot()
                val indexed = prior.records.associateByTo(linkedMapOf(), CodeModeRecordSnapshot::id)
                indexed[snapshot.id] = snapshot
                indexed.values.toList()
            } else {
                records.filter { it.key == key }.map { record ->
                    record.saveGeneration++
                    record.snapshot()
                }
            }
            val before = prior?.records.orEmpty().associateBy(CodeModeRecordSnapshot::id)
            nextRecords.forEach { snapshot ->
                val old = before[snapshot.id]
                if (CodeModeStateJournal.same(snapshot, old)) snapshot.retainedBytes = old?.retainedBytes
            }
            val markers = expired.filter { it.key == key }
            val next = CodeModePersistedState(records = nextRecords, expired = markers)
                .takeUnless { it.records.isEmpty() && it.expired.isEmpty() }
            val changed = next != prior || next?.records?.zip(prior?.records.orEmpty())
                ?.any { (left, right) -> !CodeModeStateJournal.same(left, right) } == true
            return if (changed) Prepared(key, next) else null
        }
    }

    private fun persist(item: Prepared) {
        var bytes = 0
        try {
            val conversation = item.conversation
            if (conversation == null) {
                remove(item.key)
            } else {
                val prior = kept[item.key].takeIf { Files.exists(fileOf(item.key)) }
                val text = CodeModeStateJournal.encode(item.key, prior, conversation, json)
                bytes = text.toByteArray().size
                secureDirectory()
                write(Encoded(item.key, conversation, text))
            }
        } catch (error: IOException) {
            failedKeys.add(item.key)
            val free = freeBytes(dir)
            throw CodeModePersistenceException(error, diskFull = free != null && free < bytes)
        }
    }

    private fun readDirectory() {
        if (!Files.isDirectory(dir)) return
        val files = try {
            Files.list(dir).use { entries -> entries.filter { FILE_NAME.matches(it.fileName.toString()) }.toList() }
        } catch (failure: IOException) {
            log("[code-mode] $dir not listed (${SafeFailureText.render(failure)}): its conversations are not restored")
            return
        }
        files.forEach { file -> read(file)?.let { (key, conversation) -> kept[key] = conversation } }
    }

    /** [file]'s conversation, or null when it does not read back whole: a file that is not code-mode state
     *  is removed, so that conversation's scripts go; one that could not be read stays for the next start. */
    private fun read(file: Path): Pair<String, CodeModePersistedState>? {
        val why = try {
            val text = String(Files.readAllBytes(file), Charsets.UTF_8)
            val conversation = CodeModeStateJournal.decode(text, json)
            conversation.records.forEach { record ->
                record.retainedBytes = json.encodeToString(record).encodeToByteArray().size.toLong()
            }
            val key = (conversation.records.map { it.key } + conversation.expired.map { it.key }).distinct()
                .singleOrNull()
            if (key != null && fileOf(key) == file) return key to conversation
            "it does not hold exactly the one conversation its name says"
        } catch (failure: IOException) {
            log(
                "[code-mode] conversation file ${file.fileName} not read " +
                    "(${SafeFailureText.render(failure)}): it stays",
            )
            return null
        } catch (_: IllegalArgumentException) {
            "it is not code-mode state"
        }
        log("[code-mode] conversation file ${file.fileName} unreadable ($why): removed, so its scripts are dropped")
        try {
            Files.deleteIfExists(file)
        } catch (failure: IOException) {
            log("[code-mode] ${file.fileName} not removed (${SafeFailureText.render(failure)})")
        }
        return null
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

    private fun carry(key: String, conversation: CodeModePersistedState): Boolean = try {
        secureDirectory()
        write(Encoded(key, conversation, CodeModeStateJournal.encode(key, null, conversation, json)))
        true
    } catch (failure: IOException) {
        log(
            "[code-mode] conversation ${key.take(CONVERSATION_LOG_CHARS)} not written to $dir " +
                "(${SafeFailureText.render(failure)}): ${legacyFile.fileName} stays, and the next save writes it",
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
        kept[file.key] = file.conversation
    }

    private fun remove(key: String) {
        Files.deleteIfExists(fileOf(key))
        kept.remove(key)
    }

    private fun fileOf(key: String): Path {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val name = HexFormat.of().formatHex(digest)
        return dir.resolve("$name$CONVERSATION_STATE_SUFFIX")
    }

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

private val FILE_NAME = Regex("[0-9a-f]{64}\\.json")
private const val CONVERSATION_STATE_SUFFIX = ".json"

// why: the retention log names a conversation by the first 8 characters of its key (RECORD_ID_LOG_CHARS in
// CodexCodeModeSweeper.kt), enough to tell a head's conversations apart in daemon.log; this names it the same way.
private const val CONVERSATION_LOG_CHARS = 8
