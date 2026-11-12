package splice.usage.perf.history

import splice.core.perf.observation.PerfCoverage

/** Producer receipt, including the exact retained bytes to which collection coverage applies. */
public data class PerfEvidenceCoverageProof(
    val coverage: PerfCoverage,
    val asOfMs: Long,
    val prefixes: List<PerfEvidencePrefix>,
)

public data class PerfEvidencePrefix(val fileName: String, val end: Long, val digest: String)

/** Called inside the source's work reservation. Null preserves unknown collection coverage. */
public fun interface PerfEvidenceCoverageSource {
    public fun proof(query: PerfEvidenceQuery): PerfEvidenceCoverageProof?
    public fun horizon(head: String? = null): Long? = null
}
