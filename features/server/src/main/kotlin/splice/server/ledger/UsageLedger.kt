// NEW: server mode (docs/specs/server-mode.md, F5, ledger-rows). The server's usage ledger: one JSON line per request
// in `<state>/server/ledger.jsonl`, appended as each request ends and never rotated, so a restart, a report and an
// export all read every row there ever was. The file is read once at boot and its rows kept in memory beside it; a
// line a crash left half-written is skipped, never taken for a row. What counts rows as they come (each person's spend,
// for budgets) is told of each one, those read at boot first, so a restart forgets no spend.
//
// A row the file would not take (a full disk, a folder made read-only) is kept and written ahead of the next one, and
// until the file takes rows again the listener serves no turn (F5: splice's own failure is answered 500).
package splice.server.ledger

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import splice.core.util.JsonlSink
import splice.core.util.SafeFailureText
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue

/** What hears of each row, in the order they were written. */
internal fun interface RowWatch {
    fun counted(row: LedgerRow)
}

internal class UsageLedger(
    /** `<state>/server/ledger.jsonl`. */
    private val file: Path,
    private val io: CoroutineDispatcher,
    /** What is told of every row: those read at boot here, then each one as it is written. */
    private val watch: RowWatch = RowWatch {},
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val appending = Mutex()
    private val rows = ConcurrentLinkedQueue(read().onEach(watch::counted))

    // Rows the file would not take yet, oldest first: each is written ahead of any row after it.
    private val unwritten = ArrayDeque<LedgerRow>()

    /** Writes [row] after every row before it; throws when the file will not take it, and keeps it to write later. */
    suspend fun add(row: LedgerRow) {
        appending.withLock {
            unwritten.addLast(row)
            try {
                flush()
            } finally {
                // A ledger broken past the bound keeps its newest rows.
                while (unwritten.size > UNWRITTEN_KEPT) unwritten.removeFirst()
            }
        }
    }

    /** Why the ledger cannot take rows now, or null when it can: the rows it kept are written first, then the file is
     *  checked writable (its folder, before the first row). */
    suspend fun unwritable(): String? = appending.withLock {
        try {
            flush()
            withContext(io) { probe() }
            null
        } catch (e: IOException) {
            SafeFailureText.render(e)
        }
    }

    /** Writes the rows kept, in order; each one written is counted, and the first the file refuses stops it. */
    private suspend fun flush() {
        while (unwritten.isNotEmpty()) {
            val row = unwritten.first()
            // why: no rotation: a ledger is a record of what was used, and a row past a size cap would be one lost.
            withContext(io) { JsonlSink.appendLine(file, line(row), UNBOUNDED) }
            unwritten.removeFirst()
            rows.add(row)
            watch.counted(row)
        }
    }

    /** Throws when the file, or before its first row its folder, refuses to be written; writes nothing. */
    private fun probe() {
        val target = if (Files.exists(file)) file else file.toAbsolutePath().parent
        if (!Files.isWritable(target)) throw AccessDeniedException(target.toString(), null, "not writable")
    }

    /** Every row that arrived in [from, to), in the order written, only [person]'s when one is named. */
    fun between(from: Instant, to: Instant, person: String? = null): List<LedgerRow> =
        rows.filter { row -> Instant.parse(row.at) in from..<to }
            .filter { row -> person == null || row.person == person }

    /** [row] as one line of the ledger, and of an export: the same JSON either way. */
    fun line(row: LedgerRow): String = json.encodeToString(LedgerRow.serializer(), row)

    /** Every row, in the order written. */
    internal fun all(): List<LedgerRow> = rows.toList()

    private fun read(): List<LedgerRow> {
        if (!Files.exists(file)) return emptyList()
        return Files.readAllLines(file).filter(String::isNotBlank).mapNotNull { line ->
            try {
                json.decodeFromString(LedgerRow.serializer(), line)
            } catch (_: IllegalArgumentException) {
                // A line a crash cut short: no row, and the rows after it are read as they are.
                null
            }
        }
    }
}

// The size a ledger file may grow to before it would rotate: never.
private const val UNBOUNDED = Long.MAX_VALUE

// why: the rows kept while the file refuses them are those of turns already under way when it stopped taking rows
// (none is served after); this many is far past that, and bounds what a ledger broken for days holds in memory.
private const val UNWRITTEN_KEPT = 10_000
