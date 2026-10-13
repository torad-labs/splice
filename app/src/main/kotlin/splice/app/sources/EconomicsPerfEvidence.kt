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
