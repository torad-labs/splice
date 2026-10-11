// The newest pre-cutoff counter samples and skipped lines one perf window read keeps, split out of
// PerfRowsFileSource's scan so the scan holds one coherent responsibility.
package splice.app.sources

/** Pre-cutoff counter samples kept; a run of more torn lines than this before the cutoff costs
 *  the baseline, not the window (JsonlSink heals a torn tail before the next append). */
private const val BASELINE_CANDIDATES = 4

/** Pre-cutoff lines skipped unparsed that are kept as the newest row's evidence, highest leading ts
 *  first; a run of more torn lines than this at the newest end costs `newestHeldTs` precision (it
 *  names an older valid row), never validity. */
private const val NEWEST_CANDIDATES = 4

/** A baseline sample: the counter already parsed, or the raw writer-shaped line still to parse. */
internal data class Baseline(val drops: Long? = null, val raw: String? = null)

/** A pre-cutoff line skipped unparsed, ordered by its leading-ts [hint]; only its parse is a row. */
internal data class Skipped(val hint: Long, val raw: String? = null, val ts: Long? = null)

/** The newest pre-cutoff counter samples and the highest skipped lines one window read keeps, split out of Scan. */
internal class PerfCandidates {
    val before = ArrayDeque<Baseline>(BASELINE_CANDIDATES)
    val latest = ArrayList<Skipped>(NEWEST_CANDIDATES + 1)

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

    /** Keeps [sample] while it can still be the newest row: once the window holds a row, every
     *  pre-cutoff line is older than it, and a hint at or under a parsed ts cannot beat that ts. */
    fun skipped(sample: Skipped, windowHasRows: Boolean, newest: Long?) {
        if (windowHasRows || sample.hint <= (newest ?: Long.MIN_VALUE)) return
        val at = latest.indexOfFirst { it.hint > sample.hint }.takeIf { it >= 0 } ?: latest.size
        latest.add(at, sample)
        if (latest.size > NEWEST_CANDIDATES) latest.removeAt(0)
    }
}
