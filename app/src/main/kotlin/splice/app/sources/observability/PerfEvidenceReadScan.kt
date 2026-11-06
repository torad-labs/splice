package splice.app.sources.observability

import splice.app.sources.PerfLineReader
import splice.core.perf.observation.PerfIssue
import splice.core.perf.observation.PerfObservation
import splice.head.perf.RequestEvidenceFormat
import splice.usage.perf.history.PerfEvidenceCoverageProof
import splice.usage.perf.history.PerfEvidenceQuery
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Bounded exact facts for summaries; callers use the event cursor when the returned work limit is reached. */
internal class PerfEvidenceReadScan(
    private val query: PerfEvidenceQuery,
    private val inventory: PerfEvidenceInventory,
    private val proof: PerfEvidenceCoverageProof? = null,
) {
    val observations = LinkedHashMap<String, PerfObservation>()
    val issues = ArrayList<PerfIssue>()
    var retainedBytes = EVIDENCE_READ_OVERHEAD
        private set
    var scannedRows = 0L
        private set
    val work = PerfCursorWork()

    fun scan() {
        val fixed = proof?.let { inventory.certified(it, work) }
            ?: inventory.candidates(query.head).map { it to inventory.fingerprints.capture(it, work) }
        for ((path, segment) in fixed) {
            read(path, segment)
            if (inventory.fingerprints.digest(path, segment.end, work) != segment.digest) {
                throw IOException("evidence_segment_changed")
            }
        }
    }

    private fun read(path: Path, segment: PerfCursorSegment) {
        val head = RequestEvidenceFormat.headOf(path.fileName.toString())
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            val reader = PerfLineReader(channel, 0L, segment.end, PERF_CURSOR_MAX_LINE_BYTES)
            var raw = reader.next()
            while (raw != null) {
                work.charge(raw.toByteArray(Charsets.UTF_8).size.toLong())
                if (reader.terminated) record(raw, head) else issue("evidence_uncommitted_tail")
                raw = reader.next()
            }
        }
    }

    private fun record(raw: String, head: String?) {
        scannedRows++
        val value = PerfEvidenceCodec.decode(raw)
        if (value == null || value.scope.head != head) {
            issue("evidence_malformed")
        } else if (PerfEvidenceCodec.matches(value, query)) {
            retain(value, raw.length.toLong())
        }
    }

    private fun retain(value: PerfObservation, bytes: Long) {
        val id = PerfEvidenceCodec.identity(value)
        val prior = observations[id]
        if (prior == null) {
            val charge = PerfEvidenceCodec.charge(bytes)
            if (retainedBytes + charge > EVIDENCE_READ_OUTPUT) throw IOException("evidence_memory_limit")
            retainedBytes += charge
            observations[id] = value
        } else if (prior != value) {
            issue("evidence_duplicate_conflict")
        }
    }

    private fun issue(code: String) {
        val index = issues.indexOfFirst { it.code == code }
        if (index >= 0) {
            issues[index] = issues[index].copy(count = issues[index].count + 1)
        } else if (issues.size < MAX_EVIDENCE_ISSUES) {
            issues += PerfIssue(code, EVIDENCE_SOURCE)
        }
    }
}

internal const val EVIDENCE_READ_OUTPUT = 4L * 1_024 * 1_024 - EVIDENCE_COVERAGE_BYTES
internal const val EVIDENCE_READ_OVERHEAD = 64L * 1_024
private const val MAX_EVIDENCE_ISSUES = 64

internal const val EVIDENCE_COVERAGE_BYTES = 256L * 1_024
