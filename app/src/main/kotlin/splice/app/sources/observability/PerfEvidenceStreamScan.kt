package splice.app.sources.observability

import splice.app.sources.PerfLineReader
import splice.app.sources.observability.cursor.PERF_CURSOR_MAX_LINE_BYTES
import splice.app.sources.observability.cursor.PerfCursorSegment
import splice.app.sources.observability.cursor.PerfCursorWork
import splice.core.perf.observation.PerfIssue
import splice.head.perf.RequestEvidenceFormat
import splice.usage.perf.history.PerfEvidenceCoverageProof
import splice.usage.perf.history.PerfEvidenceQuery
import splice.usage.perf.history.PerfObservationConsumer
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Certified physical prefixes are frozen before any callback. Dedup/conflicts precede event-time filtering. */
internal class PerfEvidenceStreamScan(
    private val query: PerfEvidenceQuery,
    private val inventory: PerfEvidenceInventory,
    private val proof: PerfEvidenceCoverageProof?,
    private val consumer: PerfObservationConsumer,
) {
    val work = PerfCursorWork()
    val issues = ArrayList<PerfIssue>()
    var scannedRows = 0L
        private set

    fun scan(seen: PerfEvidenceSeen) {
        val fixed = proof?.let { inventory.certified(it, work) }
            ?: inventory.candidates(query.head).map { it to inventory.fingerprints.capture(it, work) }
        for ((path, segment) in fixed) {
            read(path, segment, seen)
            if (inventory.fingerprints.digest(path, segment.end, work) != segment.digest) {
                throw IOException("evidence_segment_changed")
            }
        }
    }

    private fun read(path: Path, segment: PerfCursorSegment, seen: PerfEvidenceSeen) {
        val head = RequestEvidenceFormat.headOf(path.fileName.toString())
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            val reader = PerfLineReader(channel, 0L, segment.end, PERF_CURSOR_MAX_LINE_BYTES)
            var raw = reader.next()
            while (raw != null) {
                work.charge(raw.toByteArray(Charsets.UTF_8).size.toLong())
                if (reader.terminated) record(raw, head, seen) else issue("evidence_uncommitted_tail")
                raw = reader.next()
            }
        }
    }

    private fun record(raw: String, head: String?, seen: PerfEvidenceSeen) {
        scannedRows++
        val value = PerfEvidenceCodec.decode(raw)
        if (value == null || value.scope.head != head) {
            issue("evidence_malformed")
            return
        }
        when (seen.add(PerfEvidenceCodec.identity(value), PerfEvidenceCodec.fingerprint(value))) {
            PerfEvidenceDuplicate.CONFLICT -> issue("evidence_duplicate_conflict")
            PerfEvidenceDuplicate.SAME -> Unit
            PerfEvidenceDuplicate.NEW -> if (PerfEvidenceCodec.matches(value, query) && !consumer.accept(value)) {
                throw IOException("evidence_consumer_limit")
            }
        }
    }

    fun issue(code: String) {
        val at = issues.indexOfFirst { it.code == code }
        if (at >= 0) {
            issues[at] = issues[at].copy(count = issues[at].count + 1)
        } else if (issues.size < MAX_STREAM_ISSUES) {
            issues += PerfIssue(code, EVIDENCE_SOURCE)
        }
    }
}

// Keep sixty-four distinct streaming issue codes while repeated failures increment their existing counters.
private const val MAX_STREAM_ISSUES = 64
