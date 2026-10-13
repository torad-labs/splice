// NEW: Oct 12, 2026 — what one perf read hands the hourly totals, moved out of PerfRowsFileSource so the source holds
// the reading and this the shape (the push of 8ab63f4c1: PerfRowsFileSource.kt in the concentration band HIGH).
package splice.app.sources

import splice.usage.perf.PerfRow
import splice.usage.perf.PerfRowsWindow

/** Which rows a scan keeps: the work rows alone, or the work rows with the probes the hourly totals subtract. */
internal enum class PerfSelection { WORK, ECONOMICS }

/** One settled scan supplies the work and probe sides of historical economics reconciliation. [probeTears] holds, for
 *  each line the scan could not read as a row that might still be a legacy liveness probe, its leading timestamp (null
 *  where it names none): a line whose readable start does not show a model, since every probe row carries an empty one. */
internal data class EconomicsPerfEvidence(
    val work: PerfRowsWindow,
    val probes: List<PerfRow>,
    val probeTears: List<Long?>,
)

/** One hourly-totals read's evidence, lent to the asks after it inside that read. One read asked the log three
 *  times (backfill, probe check, reprice), each from its own oldest hour, and a week of whole rows outgrows the full
 *  tier, so each ask decoded the week again: two decodes of every line in the startup scan (jfr on desk d, Oct 12).
 *  The evidence is held only while a read is open ([open]), never between reads, and only while [files] are the ones
 *  it was read of. */
internal class EconomicsEvidenceShare(private val files: PerfReadFiles) {
    private var reads = 0
    private var held: Held? = null

    /** Opens one read. Until it is closed, each ask that tolerates an earlier cutoff is answered by the read
     *  before it. */
    @Synchronized
    fun open(): AutoCloseable {
        reads++
        return AutoCloseable { synchronized(this) { if (--reads == 0) held = null } }
    }

    /** Keeps [evidence], read from [sinceMs] when the files were as [stamp], while a read is open. */
    @Synchronized
    fun keep(sinceMs: Long, stamp: PerfSourceStamp?, evidence: EconomicsPerfEvidence) {
        if (reads > 0) held = stamp?.let { Held(sinceMs, it, evidence) }
    }

    /** The evidence an earlier ask of the same unchanged files took, its probes and torn lines cut at [sinceMs] as a
     *  read from [sinceMs] would cut them; null when no open read holds one that reaches back that far, or when the one
     *  it holds started earlier and holds a torn line with no time. Such a line cannot be cut at [sinceMs], and a read
     *  from [sinceMs] may never open its file: 944 NUL bytes in claudex's Sep archive, read for the backfill from Sep 8,
     *  refused every later hour of claudex's hourly history (live, Oct 13). */
    @Synchronized
    fun since(sinceMs: Long): EconomicsPerfEvidence? {
        val earlier = held?.takeIf { reads > 0 && sinceMs >= it.sinceMs && files.unchanged(it.stamp) } ?: return null
        val read = earlier.evidence
        if (sinceMs > earlier.sinceMs && read.probeTears.any { it == null }) return null
        return read.copy(
            probes = read.probes.filter { it.ts >= sinceMs },
            probeTears = read.probeTears.filter { it == null || it >= sinceMs },
        )
    }

    private data class Held(val sinceMs: Long, val stamp: PerfSourceStamp, val evidence: EconomicsPerfEvidence)
}
