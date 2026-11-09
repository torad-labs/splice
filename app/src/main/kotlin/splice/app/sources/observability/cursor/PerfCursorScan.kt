package splice.app.sources.observability.cursor

import kotlinx.serialization.json.Json
import splice.app.sources.CACHE_OVERHEAD_BYTES
import splice.app.sources.ParseTally
import splice.app.sources.PerfLineDecoder
import splice.app.sources.PerfLineReader
import splice.app.sources.PerfRowsCache
import splice.usage.perf.history.PerfCursorRequest
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Scans every eligible committed row before counting; retains only a fixed-width disk index. */
internal class PerfCursorScan(
    private val inventory: PerfCursorInventory,
    private val request: PerfCursorRequest,
    private val work: PerfCursorWork,
    private val indexBytes: Long,
) {
    val segments = ArrayList<PerfCursorSegment>()
    val issues = ArrayList<String>()
    var matching = 0L
        private set
    var malformed = 0L
        private set
    var rows = 0L
        private set
    private val decoder = PerfLineDecoder(PerfRowsCache(CACHE_OVERHEAD_BYTES + DECODER_BYTES), Json, ParseTally { })

    fun scan(index: FileChannel) {
        for (path in inventory.candidates()) {
            val issue = try {
                candidate(path, index)
                null
            } catch (failure: PerfCursorScanRefusal) {
                failure.issue
            } catch (_: IOException) {
                "cursor_read_error"
            }
            if (issue != null) {
                issues += issue
                break
            }
        }
        index.force(true)
    }

    private fun candidate(path: Path, index: FileChannel) {
        val segment = inventory.capture(path, work)
        if (inventory.aliasesRolled(path, segment, segments)) {
            // Legacy copied archives carry no writer-issued logical segment id. Equal complete bytes
            // prevent duplicate export, but cannot prove that two independent generations were not equal.
            issues += "rotation_alias_identity_unproven"
            return
        }
        segments += segment
        scanSegment(path, segment, index)
        if (inventory.digest(path, segment.end, work) != segment.digest) {
            throw PerfCursorScanRefusal("cursor_segment_changed")
        }
    }

    private fun scanSegment(path: Path, segment: PerfCursorSegment, index: FileChannel) {
        FileChannel.open(path, StandardOpenOption.READ).use { input ->
            val reader = PerfLineReader(input, 0L, segment.end, PERF_CURSOR_MAX_LINE_BYTES)
            var scanning = true
            while (scanning) {
                val start = reader.position
                val raw = reader.next()
                work.charge(reader.position - start)
                scanning = raw != null && reader.terminated
                if (raw != null && !reader.terminated) {
                    issues += "uncommitted_tail"
                }
                if (scanning) record(requireNotNull(raw), start, reader.position, index)
            }
        }
    }

    private fun record(raw: String, start: Long, end: Long, index: FileChannel) {
        rows++
        val parsed = decoder.decode(raw)
        val row = parsed.row
        if (row == null) {
            malformed++
        } else if (!parsed.probe && eligible(row)) {
            if ((matching + 1) * PERF_CURSOR_ENTRY_BYTES > indexBytes) throw PerfCursorScanRefusal("cursor_index_limit")
            val position = ByteBuffer.allocate(PERF_CURSOR_ENTRY_BYTES)
                .putInt(segments.lastIndex).putLong(start).putLong(end)
            position.flip()
            while (position.hasRemaining()) index.write(position)
            matching++
        }
    }

    private fun eligible(row: splice.usage.perf.PerfRow): Boolean {
        val inWindow = row.ts >= request.query.fromMs && row.ts < request.query.untilMs
        return inWindow && request.predicate.matches(row)
    }
}

// why: Charge the single reusable legacy-row decoder while scanning the cursor's bounded disk index.
private const val DECODER_BYTES = 64L * 1_024

/** Only locally constructed cursor reason codes cross the evidence boundary; arbitrary I/O text never does. */
private class PerfCursorScanRefusal(val issue: String) : IOException()
