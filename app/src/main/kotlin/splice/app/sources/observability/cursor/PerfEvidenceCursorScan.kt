package splice.app.sources.observability.cursor

import splice.app.sources.PerfLineReader
import splice.app.sources.observability.PerfEvidenceCodec
import splice.app.sources.observability.PerfEvidenceDuplicate
import splice.app.sources.observability.PerfEvidenceInventory
import splice.app.sources.observability.PerfEvidenceSeen
import splice.core.perf.observation.PerfObservation
import splice.head.perf.RequestEvidenceFormat
import splice.usage.perf.history.PerfEvidenceCursorRequest
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal class PerfEvidenceCursorScan(
    private val request: PerfEvidenceCursorRequest,
    private val inventory: PerfEvidenceInventory,
    private val work: PerfCursorWork,
    private val seen: PerfEvidenceSeen,
    private val indexLimit: Long,
) {
    val segments = ArrayList<PerfCursorSegment>()
    val issues = ArrayList<String>()
    var matching = 0L
        private set
    var malformed = 0L
        private set
    var scanned = 0L
        private set

    fun scan(index: FileChannel) {
        val fixed = inventory.candidates(request.query.head).map { it to inventory.fingerprints.capture(it, work) }
        for ((path, segment) in fixed) {
            segments += segment
            try {
                scanSegment(path, segment, index)
                if (inventory.fingerprints.digest(path, segment.end, work) != segment.digest) {
                    throw IOException("cursor_segment_changed")
                }
            } catch (failure: IOException) {
                issues += failure.message?.takeIf { it.startsWith("cursor_") } ?: "cursor_read_error"
                break
            }
        }
        index.force(true)
    }

    private fun scanSegment(path: Path, segment: PerfCursorSegment, index: FileChannel) {
        val head = RequestEvidenceFormat.headOf(path.fileName.toString())
        FileChannel.open(path, StandardOpenOption.READ).use { input ->
            val reader = PerfLineReader(input, 0L, segment.end, PERF_CURSOR_MAX_LINE_BYTES)
            var reading = true
            while (reading) {
                val start = reader.position
                val raw = reader.next()
                work.charge(reader.position - start)
                reading = raw != null && reader.terminated
                if (reading) {
                    record(
                        requireNotNull(raw),
                        head,
                        PerfCursorPosition(segments.lastIndex, start, reader.position),
                        index,
                    )
                }
                if (raw != null && !reader.terminated) issues += "uncommitted_tail"
            }
        }
    }

    private fun record(raw: String, head: String?, position: PerfCursorPosition, index: FileChannel) {
        scanned++
        val value = PerfEvidenceCodec.decode(raw)
        if (value == null || value.scope.head != head) {
            malformed++
        } else {
            accept(value, position, index)
        }
    }

    private fun selected(value: PerfObservation): Boolean =
        PerfEvidenceCodec.matches(value, request.query) && request.predicate?.matches(value) != false

    private fun accept(value: PerfObservation, position: PerfCursorPosition, index: FileChannel) {
        val duplicate = seen.add(PerfEvidenceCodec.identity(value), PerfEvidenceCodec.fingerprint(value))
        if (duplicate == PerfEvidenceDuplicate.CONFLICT && CONFLICT !in issues) issues += CONFLICT
        if (duplicate == PerfEvidenceDuplicate.NEW && selected(value)) {
            if ((matching + 1) * PERF_CURSOR_ENTRY_BYTES > indexLimit) throw IOException("cursor_index_limit")
            val bytes = ByteBuffer.allocate(PERF_CURSOR_ENTRY_BYTES)
                .putInt(position.segment).putLong(position.start).putLong(position.end)
            bytes.flip()
            while (bytes.hasRemaining()) index.write(bytes)
            matching++
        }
    }
}

private const val CONFLICT = "evidence_duplicate_conflict"
