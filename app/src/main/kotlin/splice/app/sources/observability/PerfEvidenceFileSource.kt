package splice.app.sources.observability

import splice.app.sources.observability.cursor.PerfEvidenceCursors
import splice.app.sources.observability.cursor.PerfReadRefusal
import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfIssue
import splice.core.util.AsyncFileIo
import splice.core.util.MonoClock
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock
import splice.usage.perf.history.PerfEvidenceCoverageProof
import splice.usage.perf.history.PerfEvidenceCoverageSource
import splice.usage.perf.history.PerfEvidenceCursorRequest
import splice.usage.perf.history.PerfEvidenceCursorSource
import splice.usage.perf.history.PerfEvidencePage
import splice.usage.perf.history.PerfEvidenceQuery
import splice.usage.perf.history.PerfEvidenceRead
import splice.usage.perf.history.PerfEvidenceReceipt
import splice.usage.perf.history.PerfEvidenceSinkSource
import splice.usage.perf.history.PerfObservationConsumer
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.Semaphore

public class PerfEvidenceFileSource(
    private val directory: Path,
    private val clock: WallClock,
    private val sinks: PerfEvidenceSinkSource? = null,
    coverage: PerfEvidenceCoverageSource? = null,
) : PerfEvidenceCursorSource {
    public val costs: PerfSourceCosts = PerfSourceCosts()
    private val coverage = coverage ?: PerfEvidenceCoverageFileSource(directory)
    private val inventory = PerfEvidenceInventory(directory)
    private val cursors = PerfEvidenceCursors(directory, clock, costs)
    private val reading = Semaphore(1)

    override fun page(request: PerfEvidenceCursorRequest): PerfEvidencePage = cursors.page(request)

    override fun consume(query: PerfEvidenceQuery, consumer: PerfObservationConsumer): PerfEvidenceReceipt {
        require(query.fromMs >= 0 && query.untilMs > query.fromMs) { "invalid evidence interval" }
        val head = query.head
        require(head == null || evidenceHeadPattern.matches(head)) { "invalid evidence head" }
        if (!reading.tryAcquire()) {
            return PerfEvidenceReceipt(
                PerfCoverage(query.fromMs, query.untilMs, 0, listOf(PerfIssue("evidence_query_busy", EVIDENCE_SOURCE))),
                clock(),
                false,
            )
        }
        return try {
            PerfEvidenceStreamSource(directory, clock, sinks, coverage, costs).consume(query, consumer)
        } finally {
            reading.release()
        }
    }

    override fun settledUntilMs(head: String?): Long? {
        require(head == null || evidenceHeadPattern.matches(head)) { "invalid evidence head" }
        val lease = PerfWorkBudget.acquire(HORIZON_BYTES, PerfWorkBudget.Kind.EVIDENCE_READ) ?: return null
        return lease.use {
            try {
                coverage.horizon(head)
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    override fun read(query: PerfEvidenceQuery): PerfEvidenceRead {
        require(query.fromMs >= 0 && query.untilMs > query.fromMs) { "invalid evidence interval" }
        val head = query.head
        require(head == null || evidenceHeadPattern.matches(head)) { "invalid evidence head" }
        if (!reading.tryAcquire()) return unavailable(query, "evidence_query_busy")
        val lease = PerfWorkBudget.acquire(EVIDENCE_READ_RESERVE, PerfWorkBudget.Kind.EVIDENCE_READ)
        if (lease == null) {
            reading.release()
            return unavailable(query, "evidence_memory_busy")
        }
        return readHeld(query, lease)
    }

    private fun readHeld(query: PerfEvidenceQuery, lease: PerfWorkBudget.Lease): PerfEvidenceRead {
        val started = MonoClock.nowMs()
        var scanned: PerfEvidenceReadScan? = null
        costs.begin(true)
        var retained = false
        return try {
            val settled = AsyncFileIo.awaitDirectory(directory)
            val receipt = proof(query)
            val proof = receipt.proof
            val scan = PerfEvidenceReadScan(query, inventory, proof)
            scanned = scan
            receipt.issue?.let { scan.issues += it }
            val asOf = proof?.asOfMs ?: clock()
            scan.issues += proof?.coverage?.issues ?: inventory.issues()
            if (!settled && proof == null) scan.issues += PerfIssue("evidence_pending_write", EVIDENCE_SOURCE)
            scan(scan)
            val result = result(query, proof, scan, asOf)
            lease.retain(result, scan.retainedBytes + PerfHistorySize.coverage(result.coverage))
            retained = true
            result
        } finally {
            if (!retained) lease.close()
            costs.end(
                scanned?.scannedRows ?: 0L,
                scanned?.work?.bytes ?: 0L,
                (MonoClock.nowMs() - started).coerceAtLeast(0L),
            )
            reading.release()
        }
    }

    private fun result(
        query: PerfEvidenceQuery,
        proof: PerfEvidenceCoverageProof?,
        scan: PerfEvidenceReadScan,
        asOf: Long,
    ): PerfEvidenceRead {
        val statuses = sinks?.statuses().orEmpty()
        if (proof == null && statuses.any { it.failed + it.dropped + it.pending > 0 }) {
            scan.issues += PerfIssue("evidence_sink_loss_or_pending", EVIDENCE_SOURCE)
        }
        val observed = if (scan.issues.any { it !in proof?.coverage?.issues.orEmpty() }) {
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
        return PerfEvidenceRead(
            scan.observations.values.toList(),
            coverage,
            asOf,
            proof != null && scan.issues.isEmpty(),
            coverageBasis = if (proof == null) "unknown" else "committed_observations_as_of",
        )
    }

    private fun proof(query: PerfEvidenceQuery): EvidenceProofRead = try {
        val proof = coverage.proof(query)
        EvidenceProofRead(proof, if (proof == null) PerfIssue("evidence_coverage_unknown", EVIDENCE_SOURCE) else null)
    } catch (_: IOException) {
        EvidenceProofRead(null, PerfIssue("evidence_proof_read_error", EVIDENCE_SOURCE))
    } catch (_: IllegalArgumentException) {
        EvidenceProofRead(null, PerfIssue("evidence_proof_malformed", EVIDENCE_SOURCE))
    }

    private fun scan(scan: PerfEvidenceReadScan) {
        try {
            scan.scan()
        } catch (failure: PerfReadRefusal) {
            scan.issues += PerfIssue(failure.issue.code, EVIDENCE_SOURCE)
        } catch (_: IOException) {
            scan.issues += PerfIssue("evidence_read_error", EVIDENCE_SOURCE)
        }
    }

    private fun unavailable(query: PerfEvidenceQuery, code: String): PerfEvidenceRead {
        costs.refused()
        return PerfEvidenceRead(
            emptyList(),
            PerfCoverage(query.fromMs, query.untilMs, 0, listOf(PerfIssue(code, EVIDENCE_SOURCE))),
            clock(),
            false,
        )
    }
}

internal val evidenceHeadPattern = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")

// Reserve 6 MiB for decoding, deduplication and the bounded retained-evidence result together.
private const val EVIDENCE_READ_RESERVE = 6L * 1_024 * 1_024

private data class EvidenceProofRead(val proof: PerfEvidenceCoverageProof?, val issue: PerfIssue?)

// Reserve 1 MiB while decoding coverage proof to determine a settled evidence horizon.
private const val HORIZON_BYTES = 1L * 1_024 * 1_024
