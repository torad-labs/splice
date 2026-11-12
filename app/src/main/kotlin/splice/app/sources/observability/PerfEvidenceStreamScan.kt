package splice.app.sources.observability

import splice.app.sources.PerfLineReader
import splice.app.sources.observability.cursor.PERF_CURSOR_MAX_LINE_BYTES
import splice.app.sources.observability.cursor.PerfCursorSegment
import splice.app.sources.observability.cursor.PerfCursorWork
import splice.app.sources.observability.cursor.PerfReadIssue
import splice.app.sources.observability.cursor.PerfReadRefusal
import splice.core.perf.observation.PerfIssue
import splice.core.perf.observation.PerfObservation
import splice.head.perf.RequestEvidenceFormat
import splice.usage.perf.history.PerfEvidenceCoverageProof
import splice.usage.perf.history.PerfEvidenceQuery
import splice.usage.perf.history.PerfObservationConsumer
import splice.usage.perf.history.PerfPartitionedObservationConsumer
import splice.usage.perf.history.PerfRequestPartitions
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
    val partitions = if (consumer is PerfPartitionedObservationConsumer) PerfRequestPartitions.COUNT else 1
    private var partition = 0
    private var fixed: List<Pair<Path, PerfCursorSegment>>? = null

    fun scan(seen: PerfEvidenceDedup, selectedPartition: Int = 0) {
        partition = selectedPartition
        val prefixes = fixed ?: (
            proof?.let { inventory.certified(it, work) }
                ?: inventory.candidates(query.head).map { it to inventory.fingerprints.capture(it, work) }
            )
            .also { fixed = it }
        for ((path, segment) in prefixes) {
            read(path, segment, seen)
            if (inventory.fingerprints.digest(path, segment.end, work) != segment.digest) {
                throw PerfReadRefusal(PerfReadIssue.EVIDENCE_SEGMENT_CHANGED)
            }
        }
        (consumer as? PerfPartitionedObservationConsumer)?.partitionFinished()
    }

    private fun read(path: Path, segment: PerfCursorSegment, seen: PerfEvidenceDedup) {
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

    private fun record(raw: String, head: String?, seen: PerfEvidenceDedup) {
        scannedRows++
        val value = PerfEvidenceCodec.decode(raw)
        if (value == null || value.scope.head != head) {
            issue("evidence_malformed")
            return
        }
        when (seen.add(PerfEvidenceCodec.identity(value), PerfEvidenceCodec.fingerprint(value))) {
            PerfEvidenceDuplicate.CONFLICT -> issue("evidence_duplicate_conflict")
            PerfEvidenceDuplicate.SAME -> Unit
            PerfEvidenceDuplicate.NEW -> if (selected(value) && !consumer.accept(value)) {
                throw PerfReadRefusal(PerfReadIssue.EVIDENCE_CONSUMER_LIMIT)
            }
        }
    }

    private fun selected(value: PerfObservation): Boolean =
        (partitions == 1 || PerfRequestPartitions.matches(value, partition)) && PerfEvidenceCodec.matches(value, query)

    fun issue(code: String) {
        val at = issues.indexOfFirst { it.code == code }
        if (at >= 0 && partition == 0) {
            issues[at] = issues[at].copy(count = issues[at].count + 1)
        } else if (at < 0 && issues.size < MAX_STREAM_ISSUES) {
            issues += PerfIssue(code, EVIDENCE_SOURCE)
        }
    }
}

// Keep sixty-four distinct streaming issue codes while repeated failures increment their existing counters.
private const val MAX_STREAM_ISSUES = 64
