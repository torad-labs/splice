package splice.app.sources.observability.cursor

import splice.app.sources.PerfLineReader
import splice.app.sources.observability.EVIDENCE_READ_OVERHEAD
import splice.app.sources.observability.PerfEvidenceCodec
import splice.app.sources.observability.PerfEvidenceInventory
import splice.core.perf.observation.PerfObservation
import splice.usage.perf.history.PerfEvidenceCursorRequest
import splice.usage.perf.history.PerfEvidencePage
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal class PerfEvidencePageRead(
    private val manifest: PerfCursorManifest,
    private val request: PerfEvidenceCursorRequest,
    private val inventory: PerfEvidenceInventory,
    private val work: PerfCursorWork,
) {
    private val observations = ArrayList<PerfObservation>()
    private val ids = ArrayList<String>()
    var retainedBytes = EVIDENCE_READ_OVERHEAD
        private set

    fun read(files: PerfCursorFiles, offset: Long): PerfEvidencePage {
        val candidates = inventory.candidates(request.query.head).associateBy { it.fileName.toString() }
        val paths = manifest.segments.map { candidates[it.fileName] ?: throw IOException("cursor_segment_expired") }
        validate(paths)
        var at = offset
        files.index(manifest.id).use { index ->
            if (index.size() != manifest.matching * PERF_CURSOR_ENTRY_BYTES) throw IOException("cursor_index_changed")
            while (at < manifest.matching && observations.size < request.pageSize) {
                if (!record(files.position(index, at), paths)) break
                at++
            }
        }
        validate(paths)
        val next = if (at < manifest.matching) "${manifest.id}:$at" else null
        return PerfEvidencePage(observations, ids, next, PerfEvidencePageMetadata.of(manifest))
    }

    private fun record(position: PerfCursorPosition, paths: List<Path>): Boolean {
        val segment = manifest.segments.getOrNull(position.segment) ?: throw IOException("cursor_index_invalid")
        require(position.start in 0 until position.end && position.end <= segment.end) { "cursor_index_invalid" }
        val charge = PerfEvidenceCodec.charge(position.end - position.start)
        if (retainedBytes + charge > PERF_CURSOR_OUTPUT_BYTES) return false
        val value = decode(paths[position.segment], position)
        require(PerfEvidenceCodec.matches(value, request.query)) { "cursor_record_changed" }
        retainedBytes += charge
        observations += value
        ids += value.stamp.observationId
        return true
    }

    private fun decode(path: Path, position: PerfCursorPosition): PerfObservation =
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            val reader = PerfLineReader(channel, position.start, position.end, PERF_CURSOR_MAX_LINE_BYTES)
            val raw = reader.next() ?: throw IOException("cursor_record_missing")
            work.charge(position.end - position.start)
            require(reader.terminated && reader.position == position.end) { "cursor_record_changed" }
            PerfEvidenceCodec.decode(raw) ?: throw IOException("cursor_record_malformed")
        }

    private fun validate(paths: List<Path>) {
        paths.forEachIndexed { index, path ->
            val segment = manifest.segments[index]
            if (inventory.fingerprints.digest(path, segment.end, work) != segment.digest) {
                throw IOException("cursor_segment_changed")
            }
        }
    }
}
