// The newest pre-cutoff counter samples one perf window read keeps, split out of
// PerfRowsFileSource's scan so the scan holds one coherent responsibility.
package splice.app.sources

/** Pre-cutoff counter samples kept; a run of more torn lines than this before the cutoff costs
 *  the baseline, not the window (JsonlSink heals a torn tail before the next append). */
private const val BASELINE_CANDIDATES = 4

/** A baseline sample: the counter already parsed, or the raw writer-shaped line still to parse. */
internal data class Baseline(val drops: Long? = null, val raw: String? = null)

/** The newest pre-cutoff counter samples one window read keeps, split out of Scan. */
internal class PerfCandidates {
    val before = ArrayDeque<Baseline>(BASELINE_CANDIDATES)

    /** Only samples appended BEFORE the window's first row can be its baseline: a later line
     *  stamped before the cutoff (a clock step) was sampled after it. */
    fun baseline(sample: Baseline, windowHasRows: Boolean) {
        if (windowHasRows) return
        if (before.size == BASELINE_CANDIDATES) before.removeFirst()
        before.addLast(sample)
    }

    /** A counter read off a line stamped before the cutoff, kept as a baseline candidate. */
    fun cutoffSample(drops: Long?, beforeCutoff: Boolean, windowHasRows: Boolean) {
        if (beforeCutoff && drops != null) baseline(Baseline(drops = drops), windowHasRows)
    }
}
