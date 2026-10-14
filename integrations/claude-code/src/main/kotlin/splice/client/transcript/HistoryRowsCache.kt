// History rows stream through a bounded cache; cache overflow never truncates the scan.
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

private const val MAX_HISTORY_ROWS = 4_096
private const val MAX_HISTORY_FILES = 64
private const val MAX_HISTORY_CACHE_BYTES = 4L shl 20
private const val HISTORY_ROW_BYTES = 96L
private const val HISTORY_FILE_BYTES = 160L
private val HISTORY_ID = Regex("[A-Za-z0-9_-]{1,128}")

/** One history line that names a session, parsed once. [title] is already cut and redacted. */
internal data class HistoryRow(val id: String, val timestamp: Long?, val title: String?, val project: String?) {
    fun retainedBytes(): Long = HISTORY_ROW_BYTES + 2L * (id.length + (title?.length ?: 0) + (project?.length ?: 0))
}

internal fun interface HistoryRowReceiver {
    operator fun invoke(row: HistoryRow)
}

private class HistoryRowRetention(
    private val slots: Int,
    private val budget: Long,
    private val receive: HistoryRowReceiver,
    var cacheable: Boolean,
) {
    val rows = ArrayList<HistoryRow>()
    var bytes = 0L
        private set

    fun accept(row: HistoryRow, complete: Boolean) {
        receive(row)
        if (!complete || !cacheable) return
        bytes += row.retainedBytes()
        if (rows.size >= slots || bytes > budget) {
            cacheable = false
            rows.clear()
        } else {
            rows += row
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

        /** Delivers every row, even when the file is too large to retain for the next pass. */
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
                met[file] = Held(stat.identity, stat.size, stat.modifiedMs, committed, kept.rows, kept.bytes)
                count += kept.rows.size
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
