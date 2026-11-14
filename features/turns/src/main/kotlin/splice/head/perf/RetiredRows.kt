// NEW: #523. A restart used to cut every running turn, and a turn cut after its answer began stops its session in
// Claude Code until someone types into it (8 of the 14 such cuts on the machine this was found on, Nov 10 to 12, 2026).
// A retiring head now finishes the turns its upstream has begun answering while the next daemon serves everything
// else, so for a few minutes two processes hold one head's books. This class is how they stay one set of books: from
// the moment a head retires its process counts nothing, and each row it still records is left here for the next daemon
// to count.
package splice.head.perf

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.FileIoTask
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.withLock
import kotlin.concurrent.write
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** One turn a retired head finished, as the next daemon counts it: the facts its request row carries. */
internal data class RetiredRow(
    val ts: Long,
    val model: String?,
    val outcome: String,
    /** The session's tag, the first 8 characters of its id, which the running totals are kept under. */
    val session: String?,
    val sessionId: String?,
    val account: String?,
    /** Every whole number on the row: its token counts among its timings, read by name. */
    val counters: Map<String, Long>,
)

/** What a serving head does with a row it has just recorded: its session's total and whatever else it counts. */
internal fun interface LiveCount {
    fun count()
}

/** What the next daemon does with one row a retired head left. */
internal fun interface RetiredRowCount {
    fun count(row: RetiredRow)
}

/** One head's rows between a retiring process and the next daemon, in [dir]: a file per row, written whole under its
 *  final name, so the reader never meets half of one and the two processes need no lock between them. */
internal class RetiredRows(private val dir: Path, private val log: LogSink = LogSink(DaemonLog::write)) {
    private val gate = ReentrantReadWriteLock()
    private val folding = ReentrantLock()
    private var retired = false
    private val left = AtomicLong(0)
    private val json = Json { ignoreUnknownKeys = true }

    /** Whether this process has stopped counting this head's rows. */
    val isRetired: Boolean get() = gate.read { retired }

    /** From here on this process counts no row of this head. It returns once every row a turn was in the middle of
     *  counting has been counted, so the totals flushed after it are whole and nothing changes them again. */
    fun retire() {
        gate.write { retired = true }
    }

    /** One recorded row, decided once: a serving head counts it through [live] and keeps [whole], the work its row's
     *  append runs; a retired head leaves the row for the next daemon and runs nothing more for it. */
    fun settle(row: String, whole: FileIoTask, live: LiveCount): FileIoTask = gate.read {
        if (retired) {
            leave(row)
            FileIoTask { }
        } else {
            live.count()
            whole
        }
    }

    private fun leave(row: String) {
        Cancellables.runCatchingCancellable {
            val _ = SecureFile.ownerOnlyDirectory(dir)
            SecureFile.writeAtomic0600(dir.resolve("${System.currentTimeMillis()}-${left.incrementAndGet()}$ROW"), row)
        }.onFailure { failure ->
            log(
                "[retire] a finished turn's row was not left in $dir (${SafeFailureText.render(failure)}); its " +
                    "request log line is written, and its session's cost and its hour read low by that turn\n",
            )
        }
    }

    /** The next daemon's side: each row a retired head left goes to [count] once and its file is removed. A row that
     *  cannot be read is removed too and said in the log, since it would otherwise be met again at every turn. A
     *  daemon stopped between a row's count and its removal counts that row again at its next start. A process that
     *  has itself retired counts nothing: the rows here are then its own.
     *
     *  One fold runs at a time, and inside the gate [retire] waits on. Every turn that ends folds, so two turns ending
     *  together would otherwise both read a row before either removed it and count its cost twice; and a process
     *  retiring in the middle of a fold would hand over totals the rest of that fold still adds to, while the next
     *  daemon folds the same rows. The gate is taken before [folding], the order [settle] keeps too. */
    fun fold(count: RetiredRowCount): Unit = gate.read {
        if (retired || !Files.isDirectory(dir)) return@read
        folding.withLock { foldLocked(count) }
    }

    private fun foldLocked(count: RetiredRowCount) {
        Cancellables.runCatchingCancellable {
            dir.listDirectoryEntries().filter { it.name.endsWith(ROW) }.sortedBy { it.name }.forEach { file ->
                read(file)?.let(count::count)
                Files.deleteIfExists(file)
            }
        }.onFailure { failure ->
            log("[retire] the rows in $dir were not all counted (${SafeFailureText.render(failure)})\n")
        }
    }

    private fun read(file: Path): RetiredRow? {
        val row = JsonScalars.objectOrNull(json, Files.readString(file))?.let(::rowOf)
        if (row == null) log("[retire] ${file.name} in $dir is not a request row; it is removed uncounted\n")
        return row
    }

    /** Null for an object with no time or no outcome: every request row carries both. */
    private fun rowOf(row: JsonObject): RetiredRow? {
        val ts = JsonScalars.long(row, "ts")
        val outcome = JsonScalars.str(row, "outcome")
        if (ts == null || outcome == null) return null
        return RetiredRow(
            ts = ts,
            model = JsonScalars.str(row, "model"),
            outcome = outcome,
            session = JsonScalars.str(row, "session"),
            sessionId = JsonScalars.str(row, "session_id"),
            account = JsonScalars.str(row, "account"),
            counters = whole(row),
        )
    }

    private fun whole(row: JsonObject): Map<String, Long> = buildMap {
        row.forEach { (name, value) ->
            val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            if (number != null) put(name, number)
        }
    }
}

private const val ROW = ".row.json"
