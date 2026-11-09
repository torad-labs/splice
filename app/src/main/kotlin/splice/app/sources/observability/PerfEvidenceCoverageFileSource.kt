package splice.app.sources.observability

import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfIssue
import splice.head.perf.RequestEvidenceFormat
import splice.head.perf.RequestEvidenceProof
import splice.head.perf.RequestEvidenceProofFormat
import splice.usage.perf.history.PerfEvidenceCoverageProof
import splice.usage.perf.history.PerfEvidenceCoverageSource
import splice.usage.perf.history.PerfEvidencePrefix
import splice.usage.perf.history.PerfEvidenceQuery
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** Reads a bounded producer ledger; event extents and aggregate uptime are never coverage inputs. */
public class PerfEvidenceCoverageFileSource(private val directory: Path) : PerfEvidenceCoverageSource {
    override fun proof(query: PerfEvidenceQuery): PerfEvidenceCoverageProof? {
        val proof = load() ?: return null
        val coverage = PerfEvidenceIntervals.coverage(query, proof)
        val prefixes = proof.prefixes.filter {
            query.head == null || RequestEvidenceFormat.headOf(it.fileName) == query.head
        }.map { PerfEvidencePrefix(it.fileName, it.end, it.digest) }
        return PerfEvidenceCoverageProof(coverage, proof.asOfMs, prefixes)
    }

    override fun horizon(head: String?): Long? {
        val proof = load() ?: return null
        val heads = head?.let(::listOf) ?: proof.heads
        val ends = heads.map { name ->
            proof.intervals.filter { it.head == name }.maxOfOrNull { it.untilMs } ?: return null
        }
        return ends.minOrNull()
    }

    private fun load(): RequestEvidenceProof? {
        val bytes = try {
            Files.newInputStream(directory.resolve(RequestEvidenceProofFormat.COVERAGE_FILE)).use {
                it.readNBytes(RequestEvidenceProofFormat.MAX_BYTES + 1)
            }
        } catch (_: NoSuchFileException) {
            return null
        }
        if (bytes.size > RequestEvidenceProofFormat.MAX_BYTES) throw IOException("evidence_proof_limit")
        val proof = RequestEvidenceProofFormat.decode(bytes)
        validate(proof)
        return proof
    }

    private fun validate(proof: RequestEvidenceProof) {
        require(proof.schemaVersion == 1 && proof.asOfMs >= 0L)
        require(proof.heads.size in 1..RequestEvidenceProofFormat.MAX_HEADS)
        require(proof.heads.distinct().size == proof.heads.size && proof.heads.all(evidenceHeadPattern::matches))
        require(proof.intervals.size <= RequestEvidenceProofFormat.MAX_INTERVALS)
        require(proof.lossEpochs.keys.all { it in proof.heads } && proof.retentionEpochs.keys.all { it in proof.heads })
        require(
            proof.prefixes.size <= RequestEvidenceProofFormat.MAX_PROOF_PREFIXES && proof.prefixes.sumOf {
                it.end
            } <= MAX_EVIDENCE_BYTES,
        )
        require(proof.prefixes.map { it.fileName }.distinct().size == proof.prefixes.size)
        intervals(proof)
        prefixes(proof)
    }

    private fun intervals(proof: RequestEvidenceProof) {
        proof.intervals.forEach {
            require(it.head in proof.heads && it.bootId.length <= RequestEvidenceProofFormat.MAX_PROOF_BOOT_CHARS)
            require(it.fromMs >= 0 && it.untilMs in it.fromMs..proof.asOfMs)
        }
    }

    private fun prefixes(proof: RequestEvidenceProof) {
        proof.prefixes.forEach {
            val head = RequestEvidenceFormat.headOf(it.fileName)
            require(head != null && head in proof.heads && it.end in 0..MAX_EVIDENCE_BYTES)
            require(digestPattern.matches(it.digest))
        }
    }
}

internal object PerfEvidenceIntervals {
    fun coverage(query: PerfEvidenceQuery, proof: RequestEvidenceProof): PerfCoverage {
        val heads = query.head?.let(::listOf) ?: proof.heads
        var collected = listOf(ProofInterval(query.fromMs, minOf(query.untilMs, proof.asOfMs)))
        for (head in heads) {
            val intervals = proof.intervals.filter { it.head == head }
                .map { ProofInterval(it.fromMs, it.untilMs) }.sortedBy { it.from }
            collected = intersect(collected, union(intervals))
        }
        val scopeKnown = query.head != null || proof.scopeComplete
        val observed = if (scopeKnown) collected.sumOf { (it.until - it.from).coerceAtLeast(0L) } else 0L
        val issues = if (observed < query.untilMs - query.fromMs) {
            gapIssues(query, proof, heads, scopeKnown)
        } else {
            emptyList()
        }
        return PerfCoverage(query.fromMs, query.untilMs, observed, issues)
    }

    private fun gapIssues(
        query: PerfEvidenceQuery,
        proof: RequestEvidenceProof,
        heads: List<String>,
        scopeKnown: Boolean,
    ): List<PerfIssue> =
        buildList {
            add(PerfIssue("evidence_coverage_gap", EVIDENCE_SOURCE))
            if (!scopeKnown) add(PerfIssue("evidence_head_scope_incomplete", EVIDENCE_SOURCE))
            if (heads.any { (proof.lossEpochs[it] ?: 0L) > 0L }) {
                add(
                    PerfIssue("evidence_collection_loss", EVIDENCE_SOURCE),
                )
            }
            if (heads.any { (proof.retentionEpochs[it] ?: 0L) > 0L }) {
                add(
                    PerfIssue("evidence_retention_expired", EVIDENCE_SOURCE),
                )
            }
            if (query.untilMs > proof.asOfMs) add(PerfIssue("evidence_pending_checkpoint", EVIDENCE_SOURCE))
        }

    private fun union(values: List<ProofInterval>): List<ProofInterval> {
        val result = ArrayList<ProofInterval>()
        for (value in values) {
            val previous = result.lastOrNull()
            if (previous != null && value.from <= previous.until) {
                result[result.lastIndex] = ProofInterval(previous.from, maxOf(previous.until, value.until))
            } else {
                result += value
            }
        }
        return result
    }

    private fun intersect(left: List<ProofInterval>, right: List<ProofInterval>): List<ProofInterval> {
        val result = ArrayList<ProofInterval>()
        for (a in left) {
            for (b in right) {
                val from = maxOf(a.from, b.from)
                val until = minOf(a.until, b.until)
                if (until > from) result += ProofInterval(from, until)
            }
        }
        return result
    }
}

private data class ProofInterval(val from: Long, val until: Long)
private val digestPattern = Regex("[0-9a-f]{64}")

// Reject proof prefixes whose combined extents exceed the producer's 64 MiB retained-evidence contract.
private const val MAX_EVIDENCE_BYTES = 64L * 1_024 * 1_024
