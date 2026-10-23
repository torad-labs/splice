// NEW: Oct 12, 2026 — what one perf read hands the hourly totals, moved out of PerfRowsFileSource so the source holds
// the reading and this the shape (the push of 8ab63f4c1: PerfRowsFileSource.kt in the concentration band HIGH).
// Oct 23, 2026: every line the read could not parse is handed on with where it lies ([PerfTear]), not only the ones
// that could be probes, so the backfill leaves out the hours a tear could belong to instead of refusing the whole pass.
// Later that day the probe check, the reprice and the shared read took the same rule ([PerfTear.mayHold]): one
// probe-like line with no time in claudex's Oct 1 archive made its whole hourly history Unavailable once the backfill
// reached back past it, and the refusal lasted one run, so every boot read 23 days again (15.6 s at both boots of
// 34d505416).
package splice.app.sources

import splice.usage.perf.PerfRow
import splice.usage.perf.PerfRowsWindow
import kotlin.time.Duration.Companion.hours

private val TEAR_HOUR_MS = 1.hours.inWholeMilliseconds

/** Which rows a scan keeps: the work rows alone, or the work rows with the probes the hourly totals subtract. */
internal enum class PerfSelection { WORK, ECONOMICS }

/** One line inside the window that a scan could not read as a row: its leading timestamp, or null where its readable
 *  start names none; whether that start names a model, which no legacy probe row does; and when its row can lie. */
internal data class PerfTear(val ts: Long?, val namedModel: Boolean, val span: PerfTearSpan = PerfTearSpan()) {
    /** Whether this line's row may belong to the hour starting at [hour], so that hour's rows may not be all its turns:
     *  its leading ts is in it, or it has none, [hour] ends after the earliest time its row can carry, and a read from
     *  [hour] opens its file. */
    fun mayHold(hour: Long, files: PerfReadFiles): Boolean = ts?.let { hourOf(it) == hour }
        ?: ((span.notBefore ?: hour) < hour + TEAR_HOUR_MS && files.opens(span.rotatedAt, hour))

    /** The newest hour this line's row can belong to, or null when nothing bounds it: no ts, in `.1` or the live file.
     *  An archive's rotation second bounds its rows to within a second past it, which may reach the next hour. */
    fun latestHour(files: PerfReadFiles): Long? = ts?.let { hourOf(it) } ?: span.rotatedAt?.let { rotated ->
        val next = hourOf(rotated) + TEAR_HOUR_MS
        if (files.opens(rotated, next)) next else hourOf(rotated)
    }

    /** Whether a read from [sinceMs] meets this line: its ts is not before it, or it has none and the read opens its
     *  file, where a line with no ts is met whatever the cutoff. */
    fun metFrom(sinceMs: Long, files: PerfReadFiles): Boolean =
        ts?.let { it >= sinceMs } ?: files.opens(span.rotatedAt, sinceMs)

    private fun hourOf(ms: Long): Long = ms / TEAR_HOUR_MS * TEAR_HOUR_MS
}

/** When a torn line's row can lie, from where the line sits. [notBefore] is the newest row time ahead of it in its
 *  file, less the lag of a row appended late, or null when no row comes ahead of it. [rotatedAt] is the rotation second
 *  of the archived generation it lies in, or null in `.1` or the live file, whose names bound no row. */
internal data class PerfTearSpan(val notBefore: Long? = null, val rotatedAt: Long? = null)

/** One settled scan supplies the work and probe sides of historical economics reconciliation, and every line inside
 *  the window it could not read as a row ([tears]). */
internal data class EconomicsPerfEvidence(
    val work: PerfRowsWindow,
    val probes: List<PerfRow>,
    val tears: List<PerfTear>,
) {
    /** The tears that might still be legacy liveness probes: lines whose readable start does not show a model, since
     *  every probe row carries an empty one. */
    val probeLike: List<PerfTear> get() = tears.filterNot { it.namedModel }
}

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
     *  read from [sinceMs] would cut them; null when no open read holds one that reaches back that far. A torn line
     *  with no time is kept only where a read from [sinceMs] opens its file: 944 NUL bytes in claudex's Sep archive,
     *  read for the backfill from Sep 8, once refused every later hour of claudex's hourly history (live, Oct 13). */
    @Synchronized
    fun since(sinceMs: Long): EconomicsPerfEvidence? {
        val earlier = held?.takeIf { reads > 0 && sinceMs >= it.sinceMs && files.unchanged(it.stamp) } ?: return null
        val read = earlier.evidence
        return read.copy(
            probes = read.probes.filter { it.ts >= sinceMs },
            tears = read.tears.filter { it.metFrom(sinceMs, files) },
        )
    }

    private data class Held(val sinceMs: Long, val stamp: PerfSourceStamp, val evidence: EconomicsPerfEvidence)
}
