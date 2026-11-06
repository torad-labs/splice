package splice.app.sources.observability

import kotlinx.serialization.json.Json
import splice.core.perf.observation.PerfIssue
import splice.head.perf.RequestEvidenceFormat
import splice.head.perf.RequestEvidenceRetention
import splice.usage.perf.history.PerfEvidenceCoverageProof
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** Only the writer's format is authoritative for the head and the retained segment inventory. */
internal class PerfEvidenceInventory(private val directory: Path) {
    val fingerprints = PerfCursorInventory(directory.resolve("evidence"), null)

    fun candidates(head: String?): List<Path> = try {
        val files = Files.newDirectoryStream(directory).use { entries ->
            entries.asSequence().filter { eligible(it, head) }.take(MAX_EVIDENCE_SEGMENTS + 1).toList()
        }
        if (files.size > MAX_EVIDENCE_SEGMENTS) throw IOException("cursor_segment_limit")
        files.sortedBy { it.fileName.toString() }
    } catch (_: NoSuchFileException) {
        emptyList()
    }

    fun certified(proof: PerfEvidenceCoverageProof, work: PerfCursorWork): List<Pair<Path, PerfCursorSegment>> =
        proof.prefixes.map { prefix ->
            if (RequestEvidenceFormat.headOf(
                    prefix.fileName,
                ) == null
            ) {
                throw IOException("evidence_proof_segment_invalid")
            }
            val path = directory.resolve(prefix.fileName)
            if (!Files.isRegularFile(
                    path,
                    LinkOption.NOFOLLOW_LINKS,
                )
            ) {
                throw IOException("evidence_proof_segment_missing")
            }
            if (fingerprints.digest(
                    path,
                    prefix.end,
                    work,
                ) != prefix.digest
            ) {
                throw IOException("evidence_proof_segment_changed")
            }
            path to PerfCursorSegment(prefix.digest, prefix.fileName, null, prefix.end, prefix.digest)
        }

    fun issues(): List<PerfIssue> = try {
        val path = directory.resolve(RequestEvidenceFormat.RETENTION_FILE)
        val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_RETENTION_BYTES + 1) }
        if (bytes.size > MAX_RETENTION_BYTES) throw IOException("evidence retention metadata limit")
        val retention = Json.decodeFromString<RequestEvidenceRetention>(bytes.toString(Charsets.UTF_8))
        buildList {
            if (!retention.lossAccountingComplete) {
                add(PerfIssue("evidence_prior_loss_unknown", EVIDENCE_SOURCE))
            }
            if (retention.expiredSegments > 0L) {
                add(PerfIssue("evidence_retention_expired", EVIDENCE_SOURCE, retention.expiredSegments))
            }
            if (retention.failedMaintenance > 0L) {
                add(PerfIssue("evidence_maintenance_failed", EVIDENCE_SOURCE, retention.failedMaintenance))
            }
        }
    } catch (_: NoSuchFileException) {
        listOf(PerfIssue("evidence_retention_unknown", EVIDENCE_SOURCE))
    } catch (_: IOException) {
        listOf(PerfIssue("evidence_retention_read_error", EVIDENCE_SOURCE))
    } catch (_: IllegalArgumentException) {
        listOf(PerfIssue("evidence_retention_malformed", EVIDENCE_SOURCE))
    }

    private fun eligible(path: Path, head: String?): Boolean {
        val owner = RequestEvidenceFormat.headOf(path.fileName.toString()) ?: return false
        if (head != null && head != owner) return false
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw IOException("invalid evidence segment")
        return true
    }
}

internal const val EVIDENCE_SOURCE = "request_evidence"
private const val MAX_EVIDENCE_SEGMENTS = 128
private const val MAX_RETENTION_BYTES = 4_096
