package splice.app.sources.observability

import splice.app.sources.observability.cursor.PERF_CURSOR_DISK_BYTES
import splice.app.sources.observability.cursor.PerfCursorFiles
import splice.app.sources.observability.cursor.PerfReadIssue
import splice.app.sources.observability.cursor.PerfReadRefusal
import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfIssue
import splice.core.util.AsyncFileIo
import splice.core.util.MonoClock
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock
import splice.usage.perf.history.PerfEvidenceCoverageProof
import splice.usage.perf.history.PerfEvidenceCoverageSource
import splice.usage.perf.history.PerfEvidenceQuery
import splice.usage.perf.history.PerfEvidenceReceipt
import splice.usage.perf.history.PerfEvidenceSinkSource
import splice.usage.perf.history.PerfObservationConsumer
import java.io.IOException
import java.nio.file.Path
import java.util.UUID

internal class PerfEvidenceStreamSource(
    private val directory: Path,
    private val clock: WallClock,
    private val sinks: PerfEvidenceSinkSource?,
    private val coverage: PerfEvidenceCoverageSource,
    private val costs: PerfSourceCosts,
) {
    private val inventory = PerfEvidenceInventory(directory)
    private val files = PerfCursorFiles(directory.resolveSibling("${directory.fileName}-cursors"))

    fun consume(query: PerfEvidenceQuery, consumer: PerfObservationConsumer): PerfEvidenceReceipt {
        val lease = PerfWorkBudget.acquire(
            EVIDENCE_STREAM_RESERVE_BYTES,
            PerfWorkBudget.Kind.EVIDENCE_READ,
        ) ?: return unavailable(query, "evidence_memory_busy")
        val started = MonoClock.nowMs()
        var scan: PerfEvidenceStreamScan? = null
        var retained = false
        costs.begin(true)
        return try {
            val settled = AsyncFileIo.awaitDirectory(directory)
            val read = proof(query)
            val proof = read.proof
            val scanned = PerfEvidenceStreamScan(query, inventory, proof, consumer)
            scan = scanned
            scanned.issues += proof?.coverage?.issues ?: inventory.issues()
            read.issue?.let(scanned::issue)
            if (!settled && proof == null) scanned.issue("evidence_pending_write")
            scan(scanned)
            val result = result(query, proof, scanned)
            lease.retain(result, PerfHistorySize.coverage(result.coverage) + RECEIPT_OVERHEAD)
            retained = true
            result
        } finally {
            if (!retained) lease.close()
            costs.end(scan?.scannedRows ?: 0L, scan?.work?.bytes ?: 0L, (MonoClock.nowMs() - started).coerceAtLeast(0L))
        }
    }

    private fun proof(query: PerfEvidenceQuery): StreamProofRead = try {
        val proof = coverage.proof(query)
        StreamProofRead(proof, if (proof == null) "evidence_coverage_unknown" else null)
    } catch (_: IOException) {
        StreamProofRead(null, "evidence_proof_read_error")
    } catch (_: IllegalArgumentException) {
        StreamProofRead(null, "evidence_proof_malformed")
    }

    private fun scan(scan: PerfEvidenceStreamScan) {
        try {
            if (!diskScan(scan)) {
                val lease = PerfWorkBudget.acquire(EVIDENCE_MEMORY_DEDUP_BYTES, PerfWorkBudget.Kind.EVIDENCE_READ)
                    ?: throw PerfReadRefusal(PerfReadIssue.EVIDENCE_MEMORY_LIMIT)
                lease.use {
                    repeat(scan.partitions) { scan.scan(PerfEvidenceMemorySeen(), it) }
                }
            }
        } catch (failure: PerfReadRefusal) {
            scan.issue(failure.issue.code)
        } catch (_: IOException) {
            scan.issue("evidence_read_error")
        }
    }

    private fun diskScan(scan: PerfEvidenceStreamScan): Boolean {
        val lock = try {
            files.lock()
        } catch (_: IOException) {
            return false
        }
        return lock.use {
            // Only scratch preparation may choose the bounded memory owner. No evidence callback ran yet.
            val seen = try {
                val available = PERF_CURSOR_DISK_BYTES - files.prune(clock(), reserveFiles = 1)
                if (available < EVIDENCE_DEDUP_BYTES + EVIDENCE_COVERAGE_BYTES) {
                    throw PerfReadRefusal(PerfReadIssue.CURSOR_DISK_LIMIT)
                }
                files.evidenceIds(UUID.randomUUID().toString())
            } catch (_: IOException) {
                return false
            }
            // Record, work-limit and cleanup failures propagate; retrying would duplicate consumer callbacks.
            seen.use { scan.scan(it) }
            for (partition in 1 until scan.partitions) {
                files.evidenceIds(UUID.randomUUID().toString()).use { scan.scan(it, partition) }
            }
            true
        }
    }

    private fun result(
        query: PerfEvidenceQuery,
        proof: PerfEvidenceCoverageProof?,
        scan: PerfEvidenceStreamScan,
    ): PerfEvidenceReceipt {
        val statuses = sinks?.statuses().orEmpty()
        if (proof == null && statuses.any { it.failed + it.dropped + it.pending > 0 }) {
            scan.issue("evidence_sink_loss_or_pending")
        }
        val observed = if (scan.issues.any {
                it !in proof?.coverage?.issues.orEmpty()
            }
        ) {
            0L
        } else {
            proof?.coverage?.observedMs ?: 0L
        }
        val coverage = PerfCoverage(query.fromMs, query.untilMs, observed, scan.issues.distinct(), statuses)
        if (PerfHistorySize.coverage(
                coverage,
            ) > EVIDENCE_COVERAGE_BYTES
        ) {
            return unavailable(query, "evidence_metadata_limit")
        }
        return PerfEvidenceReceipt(
            coverage,
            proof?.asOfMs ?: clock(),
            proof != null && scan.issues.isEmpty(),
            if (proof == null) "unknown" else "committed_observations_as_of",
        )
    }

    private fun unavailable(query: PerfEvidenceQuery, code: String): PerfEvidenceReceipt {
        costs.refused()
        return PerfEvidenceReceipt(
            PerfCoverage(query.fromMs, query.untilMs, 0, listOf(PerfIssue(code, EVIDENCE_SOURCE))),
            clock(),
            false,
        )
    }
}

private data class StreamProofRead(val proof: PerfEvidenceCoverageProof?, val issue: String?)

// Reserve 3 MiB for proof validation and streaming decode without retaining the complete observation history.
private const val EVIDENCE_STREAM_RESERVE_BYTES = 3L * 1_024 * 1_024

// Charge 4 KiB beyond coverage for the streaming receipt and its ownership bookkeeping.
private const val RECEIPT_OVERHEAD = 4_096L
