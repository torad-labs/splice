// NEW: discovers versioned conversation journals and selects the current retained state.
// CREDENTIAL-WRITE-EXEMPT[2026-10-02]: corrupt code-mode conversation journals, never credentials.
package splice.provider.codex.state

import kotlinx.serialization.json.Json
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapReservations
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.provider.codex.CodeModeExpiredSnapshot
import splice.provider.codex.CodeModePersistedState
import splice.provider.codex.CodeModeRecordSnapshot
import splice.provider.codex.state.handover.CodeModeHeldJournals
import splice.provider.codex.state.handover.CodeModeWriteScope
import splice.upstream.memory.JvmHeap
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Discovers conversation files and selects the current state across an upgrade and downgrade. */
internal class CodeModeStateDirectory(
    private val dir: Path,
    private val json: Json,
    private val log: LogSink,
    private val heap: HeapReservations = JvmHeap.budget,
) {
    data class Loaded(val state: CodeModePersistedState, val checkpoint: Boolean)

    /** #525: the conversations a retiring daemon still held when this one listed the directory. */
    val held = CodeModeHeldJournals(dir, log)

    /** #525 (2026-11-12): the conversations this daemon may write. All of them until its head retires; after it,
     *  only those it still holds. Every write, rename and delete of a conversation's files passes here
     *  ([splice.provider.codex.CodexCodeModeStore]). */
    val writes = CodeModeWriteScope()

    private data class OnDisk(val path: Path, val key: String, val state: CodeModePersistedState, val liveBytes: Long) {
        val updatedAt: Long = maxOf(
            state.records.maxOfOrNull(CodeModeRecordSnapshot::updatedAt) ?: 0L,
            state.expired.maxOfOrNull(CodeModeExpiredSnapshot::expiredAt) ?: 0L,
        )
        val modifiedAt: Long = Files.getLastModifiedTime(path).toMillis()
    }

    fun load(): Sequence<Pair<String, Loaded>> {
        if (!Files.isDirectory(dir)) return emptySequence()
        val listed = try {
            Files.list(dir).use { entries -> entries.toList() }
        } catch (failure: IOException) {
            log("[code-mode] $dir not listed (${SafeFailureText.render(failure)}): its conversations are not restored")
            return emptySequence()
        }
        // #525: a conversation another daemon still holds is not read, rewritten or deleted, nor its lock dropped.
        val free = held.scan(listed)
        dropOrphanLocks(free)
        val files = free.filter { FILE_NAME.matches(it.fileName.toString()) }
        // Cold journals must not all become resident at boot, so they are handed on one conversation at a time. They
        // are read ahead of that, a few at once: read one after another they kept every head waiting on this folder
        // at each start.
        val conversations = files.groupBy { it.fileName.toString().substringBefore('.') }.values.toList()
        return Ahead(conversations).all().mapNotNull { versions ->
            if (versions.isEmpty()) return@mapNotNull null
            val key = versions.first().key
            // A downgraded jar can create newer .json state beside the .jsonl it cannot discover.
            val identical = versions.zipWithNext().all { (left, right) -> sameState(left.state, right.state) }
            val selected = versions.maxWith(
                compareBy<OnDisk> { it.updatedAt }
                    .thenBy { if (identical) 0L else it.modifiedAt }
                    .thenBy { it.path == path(key) },
            )
            val migration = versions.size > 1 || selected.path != path(key)
            key to Loaded(selected.state, migration || CodeModeStateJournal.outgrown(selected.path, selected.liveBytes))
        }
    }

    private data class Pending(val paths: List<Path>, val bytes: Long, val read: Future<List<OnDisk>>)

    /** Reads [conversations]' files ahead of the one being handed on and hands them on in their order. [DECODERS]
     *  reads run at a time, on threads that end by themselves once no read is left. The files read ahead or being
     *  read stay under a bound of bytes, so a large journal does not hold up the small ones behind it and a start
     *  holds a bounded amount it has not handed on yet. */
    private inner class Ahead(private val conversations: List<List<Path>>) {
        // Named, so a stack dump of a slow start says which lane reads; daemon threads, so a read never holds a dying
        // JVM; and none at all once the start is over.
        private val threads = Executors.defaultThreadFactory()
        private val pool =
            ThreadPoolExecutor(DECODERS, DECODERS, IDLE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
                threads.newThread(task).apply {
                    name = "splice-code-mode-read"
                    isDaemon = true
                }
            }.apply { allowCoreThreadTimeOut(true) }
        private val bound = minOf(AHEAD_BYTES, heap.limitBytes / AHEAD_HEAP_PARTS)
        private val waiting = ArrayDeque<Pending>()
        private var pendingBytes = 0L
        private var next = 0

        fun all(): Sequence<List<OnDisk>> = sequence {
            while (next < conversations.size || waiting.isNotEmpty()) {
                while (next < conversations.size && room()) submit(conversations[next++])
                yield(taken())
            }
        }

        /** One read at least is always under way; more are started while what waits stays under the bound. */
        private fun room(): Boolean = waiting.isEmpty() || pendingBytes < bound

        private fun submit(paths: List<Path>) {
            val bytes = paths.sumOf(::sizeOf)
            waiting.addLast(Pending(paths, bytes, pool.submit(Callable { paths.mapNotNull(::read) })))
            pendingBytes += bytes
        }

        private fun taken(): List<OnDisk> {
            val pending = waiting.removeFirst()
            pendingBytes -= pending.bytes
            return try {
                pending.read.get()
            } catch (failure: ExecutionException) {
                if (failure.cause !is HeapCapacityException) throw failure.cause ?: failure
                alone(pending.paths)
            }
        }

        /** Read at the same time, the journals asked the heap for more than it has. Once the reads ahead have ended
         *  this one is read again by itself, as every start read it before. A file its first read found invalid is
         *  already removed, and is not read twice. */
        private fun alone(paths: List<Path>): List<OnDisk> {
            waiting.forEach { pending ->
                try {
                    pending.read.get()
                } catch (_: ExecutionException) {
                    // Its own turn meets it again.
                }
            }
            return paths.filter(Files::exists).mapNotNull(::read)
        }

        private fun sizeOf(file: Path): Long = try {
            Files.size(file)
        } catch (_: IOException) {
            0L
        }
    }

    /** Reload a cold conversation honestly; a failed read must never turn a retry into new execution. */
    fun restore(key: String): CodeModePersistedState {
        val state = CodeModeStateJournal.read(path(key), json, heap)
        val keys = state.records.map { it.key } + state.expired.map { it.key }
        require(state.records.isNotEmpty() && keys.all { it == key }) {
            "cold code-mode journal belongs to another conversation"
        }
        CodeModeStateJournal.liveBytes(state, json, heap)
        return state
    }

    /** #525: a conversation that was held elsewhere at boot, read whole once its hold is free; null when it has no
     *  journal yet, which is a new conversation. A journal of another conversation is refused, as [restore] does. */
    fun reload(key: String): CodeModePersistedState? {
        if (Files.notExists(path(key))) return null
        val state = CodeModeStateJournal.read(path(key), json, heap)
        require((state.records.map { it.key } + state.expired.map { it.key }).all { it == key }) {
            "held code-mode journal belongs to another conversation"
        }
        CodeModeStateJournal.liveBytes(state, json, heap)
        return state
    }

    private fun sameState(left: CodeModePersistedState, right: CodeModePersistedState): Boolean =
        left == right && left.records.zip(right.records).all { (before, after) ->
            CodeModeStateJournal.same(before, after)
        }

    /** The loaded conversations oldest first, preserving each conversation's record order. */
    fun merge(conversations: Map<String, CodeModePersistedState>): CodeModePersistedState {
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

    fun path(key: String): Path = named(key, ".jsonl")

    fun olderPath(key: String): Path = named(key, ".json")

    /** The lock file JsonlSink.appendLine keeps beside [path]: the journal's name plus ".lock". */
    fun lockPath(key: String): Path = path(key).let { it.resolveSibling("${it.fileName}.lock") }

    /** The lock file a jar before .jsonl kept beside [olderPath]. Nothing takes it now, so it goes with that
     *  journal. */
    fun olderLockPath(key: String): Path = olderPath(key).let { it.resolveSibling("${it.fileName}.lock") }

    /** Until Oct 23 a removed conversation left its journal's lock file behind: 11,103 empty ones on one
     *  machine, back to Oct 1. A lock whose journal is gone guards nothing, and nothing appends while the
     *  registry loads, so each one goes here, as does a lock left beside a pre-.jsonl ".json" journal that is
     *  gone (199 from Oct 2 on the same machine). One that cannot be removed stays for the next start. */
    private fun dropOrphanLocks(listed: List<Path>) {
        val names = listed.mapTo(mutableSetOf()) { it.fileName.toString() }
        val orphans = listed.filter { file ->
            val name = file.fileName.toString()
            LOCK_NAME.matches(name) && name.removeSuffix(".lock") !in names
        }
        val stayed = orphans.count { lock ->
            try {
                Files.deleteIfExists(lock)
                false
            } catch (_: IOException) {
                true
            }
        }
        if (orphans.isNotEmpty()) {
            log("[code-mode] ${orphans.size - stayed} lock file(s) with no conversation removed, $stayed stayed")
        }
    }

    private fun named(key: String, suffix: String): Path {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return dir.resolve(HexFormat.of().formatHex(digest) + suffix)
    }

    /** Invalid state is dropped alone. An I/O failure preserves the file for the next start. */
    private fun read(file: Path): OnDisk? {
        val why = try {
            val conversation = CodeModeStateJournal.read(file, json, heap)
            val live = CodeModeStateJournal.liveBytes(conversation, json, heap)
            val key = (conversation.records.map { it.key } + conversation.expired.map { it.key }).distinct()
                .singleOrNull()
            val namedKey = key?.takeIf { path(it) == file || olderPath(it) == file }
            if (namedKey != null) return OnDisk(file, namedKey, conversation, live)
            "it does not hold exactly the one conversation its name says"
        } catch (capacity: HeapCapacityException) {
            throw capacity
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
}

// why: a start read every journal one after another on one core; four readers use four cores and leave the rest to
// the turns the retiring daemon is still answering.
private const val DECODERS = 4

// why: a few journals are tens of megabytes among many small ones; 256 MiB read ahead lets three readers go on through
// the small ones while the fourth is in the largest.
private const val AHEAD_BYTES = 256L * 1024 * 1024

// why: what is read ahead is decoded and not yet handed on; a thirty-second part of the heap keeps it small where the
// heap is.
private const val AHEAD_HEAP_PARTS = 32

// why: the readers are needed for the seconds of a start; five idle seconds end their threads.
private const val IDLE_SECONDS = 5L

private val FILE_NAME = Regex("[0-9a-f]{64}\\.jsonl?")
private val LOCK_NAME = Regex("[0-9a-f]{64}\\.jsonl?\\.lock")
