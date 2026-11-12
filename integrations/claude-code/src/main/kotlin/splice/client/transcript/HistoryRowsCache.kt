// History rows stream through a bounded cache that keeps the few rows a replay needs from each session, so a refresh
// reads only the lines appended since the last one; cache overflow never truncates the scan.
package splice.client.transcript

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.memory.HeapCategory
import splice.core.memory.HeapObservation
import splice.core.memory.HeapOwnerState
import splice.core.memory.HeapOwners
import splice.core.memory.HeapRelease
import splice.core.memory.HeapReservations
import splice.core.util.FileIdentity
import splice.core.util.JsonScalars
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

// why: The 65,536-row cap bounds retained history across files and matches the scan's own session cap. On Oct 25, 2026
// the 14 history files held 49,569 rows that fold to 3,390 rows for 3,295 sessions, and gain about 1,000 sessions a
// week; 4,096 kept none of the main file's 41,700 rows. Overflow still delivers every row.
private const val MAX_HISTORY_ROWS = 65_536

// why: The 64-file cap bounds per-file metadata even when histories contain few rows.
private const val MAX_HISTORY_FILES = 64

// why: 32 MiB bounds cached row estimates across files and matches the scan cache's own byte bound; the 3,390 folded
// rows estimated 1.3 MiB on Oct 25, 2026. Larger histories still stream in full.
private const val MAX_HISTORY_CACHE_BYTES = 32L shl 20

// why: Estimate 96 bytes for fixed row and list-slot overhead; string characters are counted separately.
private const val HISTORY_ROW_BYTES = 96L

// why: Estimate 160 bytes for fixed per-file cache metadata; rows and path characters are added separately.
private const val HISTORY_FILE_BYTES = 160L
private val HISTORY_ID = Regex("[A-Za-z0-9_-]{1,128}")

/** One history line that names a session, parsed once. [title] is already cut and redacted. */
internal data class HistoryRow(val id: String, val timestamp: Long?, val title: String?, val project: String?) {
    fun retainedBytes(): Long = HISTORY_ROW_BYTES + 2L * (id.length + (title?.length ?: 0) + (project?.length ?: 0))
}

internal fun interface HistoryRowReceiver {
    operator fun invoke(row: HistoryRow)
}

/** What a replay needs of one session in one history file. HistoryScanBuilder.prompt keeps the newest accepted
 *  timestamp, and a row with no title or no project never clears one the session already has. So replaying the newest
 *  accepted row, the newest accepted row with a title and the newest with a project, in file order, ends the session's
 *  facts where replaying every row ends, after any earlier root. The acceptance rule is HistoryFacts' own, and a change
 *  to HistoryScanBuilder.prompt is a change here too. */
private class HistorySessionRows(id: String) {
    private val facts = HistoryFacts(id)
    private var titled: HistoryRow? = null
    private var placed: HistoryRow? = null

    /** At most three rows, in file order. */
    var rows: List<HistoryRow> = emptyList()
        private set

    fun add(row: HistoryRow) {
        if (!facts.accepts(row.timestamp)) return
        facts.historyAt = row.timestamp
        if (row.title != null) titled = row
        if (row.project != null) placed = row
        rows = rows.filter { it === titled || it === placed } + row
    }
}

private class HistoryRowRetention(
    private val slots: Int,
    private val budget: Long,
    private val receive: HistoryRowReceiver,
    var cacheable: Boolean,
) {
    private val sessions = linkedMapOf<String, HistorySessionRows>()
    private var count = 0
    var bytes = 0L
        private set

    /** Grouped by session in the order the sessions first appear in the file, each session's rows in file order. */
    val rows: List<HistoryRow> get() = sessions.values.flatMap(HistorySessionRows::rows)

    /** Every row goes to [receive]; a whole one is also folded into its session's kept rows while the file is
     *  cacheable. A file that no longer fits stops being kept and goes on delivering. */
    fun accept(row: HistoryRow, complete: Boolean) {
        receive(row)
        if (!complete || !cacheable) return
        val session = sessions.getOrPut(row.id) { HistorySessionRows(row.id) }
        val before = session.rows
        session.add(row)
        count += session.rows.size - before.size
        bytes += session.rows.sumOf(HistoryRow::retainedBytes) - before.sumOf(HistoryRow::retainedBytes)
        if (count > slots || bytes > budget) {
            cacheable = false
            sessions.clear()
        }
    }
}

internal class HistoryRowsCache(private val heap: HeapReservations? = null) {
    internal data class Held(
        val key: FileIdentity?,
        val size: Long,
        val modifiedMs: Long,
        val committed: Long,
        val rows: List<HistoryRow>,
        val bytes: Long,
    ) {
        var observation: HeapObservation? = null

        fun accepts(stat: EntryStat): Boolean {
            if (key != stat.identity || committed > stat.size) return false
            val unchanged = stat.size == size && stat.modifiedMs == modifiedMs
            return stat.size > size || unchanged
        }
    }

    private val json = Json
    private var held: Map<Path, Held> = emptyMap()

    fun pass(): Pass = Pass(held)

    inner class Pass(private val before: Map<Path, Held>) {
        private val met = HashMap<Path, Held>()
        private var bytes = 0L
        private var count = 0

        /** Delivers the rows kept from the last pass, then every row read now, even when the file is too large to
         *  retain for the next pass. */
        fun rows(file: Path, receive: HistoryRowReceiver) {
            val stat = EntryStat(file)
            val prior = before[file]?.takeIf { it.accepts(stat) }
            val kept = HistoryRowRetention(
                MAX_HISTORY_ROWS - count,
                MAX_HISTORY_CACHE_BYTES - bytes,
                receive,
                met.size < MAX_HISTORY_FILES,
            )
            prior?.rows?.forEach { kept.accept(it, true) }
            val committed = read(file, prior?.committed ?: 0L, kept)
            if (kept.cacheable) {
                val keptRows = kept.rows
                met[file] = Held(stat.identity, stat.size, stat.modifiedMs, committed, keptRows, kept.bytes)
                count += keptRows.size
                bytes += kept.bytes
            }
        }

        private fun read(file: Path, from: Long, kept: HistoryRowRetention): Long {
            var committed = from
            Files.newByteChannel(file, StandardOpenOption.READ).use { channel ->
                val until = channel.size()
                channel.position(committed)
                val lines = HistoryLineReader(Channels.newInputStream(channel), committed, until)
                while (true) {
                    val line = lines.next() ?: break
                    row(line.text)?.let { kept.accept(it, line.complete) }
                    if (line.complete) committed = line.next
                }
            }
            return committed
        }

        fun commit() {
            held.forEach { (file, item) ->
                item.observation?.close(if (file in met) HeapRelease.CLOSED else HeapRelease.EVICTED)
            }
            heap?.let { source ->
                met.forEach { (file, item) ->
                    val size = item.bytes + HISTORY_FILE_BYTES + 2L * file.toString().length
                    val observation = HeapOwners.observe(item, source, size, HeapCategory.TRANSCRIPT_CACHE)
                    // Old passes and returned lists retain independent ownership after cache replacement.
                    HeapOwners.keep(item.rows, observation.share(HeapOwnerState.AWAITING_COLLECTION))
                    item.observation = observation
                }
            }
            held = met
        }
    }

    private fun row(line: String): HistoryRow? {
        val record = try {
            json.parseToJsonElement(line) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return null
        val id = JsonScalars.str(record, "sessionId")
        if (id == null || !HISTORY_ID.matches(id)) return null
        return HistoryRow(
            id,
            JsonScalars.long(record, "timestamp"),
            TranscriptMetadata.title(JsonScalars.str(record, "display")),
            JsonScalars.str(record, "project")?.takeIf(String::isNotBlank),
        )
    }
}
