package splice.app.sources.observability.cursor

import kotlinx.serialization.json.Json
import splice.app.sources.CACHE_OVERHEAD_BYTES
import splice.app.sources.ParseTally
import splice.app.sources.PerfLineDecoder
import splice.app.sources.PerfLineReader
import splice.app.sources.PerfRowsCache
import splice.usage.perf.history.PerfCursorRequest
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * Scans every eligible committed row before counting; retains only a fixed-width disk index.
 *
 * A generation is read once, and its digest is taken from the bytes the scan decoded, so a segment is exactly what
 * was read: its rows, up to the last whole line. Until Nov 11, 2026 each generation was read three times, once to
 * digest it, once to scan it and once more to digest it again, all against the cursor's 512 MiB of work.
 */
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
        for (path in inventory.candidates(request.query.fromMs)) {
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
        val whole = inventory.outline(path)
        if (copiedRolled(path, whole)) {
            // Legacy copied archives carry no writer-issued logical segment id. Equal complete bytes
            // prevent duplicate export, but cannot prove that two independent generations were not equal.
            issues += "rotation_alias_identity_unproven"
            return
        }
        segments += whole
        val read = SegmentDigest()
        try {
            scanSegment(path, whole.end, read, index)
        } finally {
            // Whatever stopped the scan, the segment is the lines it read and no byte past them.
            segments[segments.lastIndex] = whole.copy(end = read.end, digest = read.hex())
        }
    }

    /** Only the rolled generation can repeat an archive, and only one of its length: proving it costs a read. */
    private fun copiedRolled(path: Path, whole: PerfCursorSegment): Boolean =
        inventory.isRolled(path) && segments.any { it.end == whole.end } &&
            inventory.aliasesRolled(path, inventory.capture(path, work), segments)

    private fun scanSegment(path: Path, limit: Long, read: SegmentDigest, index: FileChannel) {
        FileChannel.open(path, StandardOpenOption.READ).use { input ->
            val reader = PerfLineReader(input, 0L, limit, PERF_CURSOR_MAX_LINE_BYTES)
            var scanning = true
            while (scanning) {
                val start = reader.position
                val raw = reader.next()
                work.charge(reader.position - start)
                scanning = raw != null && reader.terminated
                if (raw != null && !reader.terminated) {
                    issues += "uncommitted_tail"
                }
                if (scanning) {
                    record(requireNotNull(raw), start, reader.position, index)
                    read.line(reader)
                }
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

/** The digest of a generation's first [end] bytes, taken line by line from what the scan decoded. */
private class SegmentDigest {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val sink = DigestOutputStream(OutputStream.nullOutputStream(), digest)
    var end = 0L
        private set

    fun line(reader: PerfLineReader) {
        reader.hashTo(sink)
        end = reader.position
    }

    fun hex(): String = digest.digest().joinToString("") { "%02x".format(it) }
}

/** Only locally constructed cursor reason codes cross the evidence boundary; arbitrary I/O text never does. */
private class PerfCursorScanRefusal(val issue: String) : IOException()
