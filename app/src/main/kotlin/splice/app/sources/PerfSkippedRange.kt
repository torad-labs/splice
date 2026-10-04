// NEW: bounded raw anchors for a canonical pre-window byte range, never a substitute row timestamp.
package splice.app.sources

import splice.core.perf.PerfKeys

// why: four raw candidates preserve the source reader's existing newest-row and counter evidence depth.
private const val RANGE_ANCHORS = 4
// why: 1 KiB conservatively covers the range object, candidate nodes and bounded list/queue capacities.
private const val RANGE_OVERHEAD_BYTES = 1_024L

/** A skipped range can replay its counter and newest-row candidates only under the same time proof. */
internal class PerfSkippedRange(
    val start: Long,
    var end: Long,
    hint: Long,
) {
    var minimum: Long = hint
        private set
    var maximum: Long = hint
        private set
    private val latest = ArrayList<Candidate>()
    private val counters = ArrayDeque<Candidate>()

    val retainedBytes: Long
        get() = RANGE_OVERHEAD_BYTES + latest.sumOf { charge(it.raw) } + counters.sumOf { charge(it.raw) }

    fun add(raw: String, hint: Long, nextEnd: Long) {
        minimum = minOf(minimum, hint)
        maximum = maxOf(maximum, hint)
        end = nextEnd
        val at = latest.indexOfFirst { it.hint > hint }.takeIf { it >= 0 } ?: latest.size
        val candidate = Candidate(hint, nextEnd, raw)
        latest.add(at, candidate)
        if (latest.size > RANGE_ANCHORS) latest.removeAt(0)
        if (raw.contains("\"${PerfKeys.ASYNC_IO_DROPS}\"")) {
            if (counters.size == RANGE_ANCHORS) counters.removeFirst()
            counters.addLast(candidate)
        }
    }

    fun replay(visit: PerfLineVisit) {
        (latest + counters).distinctBy { it.end }.sortedBy { it.end }.forEach { visit.raw(it.raw) }
    }

    private fun charge(raw: String): Long = PERF_STRING_OVERHEAD_BYTES + raw.length * PERF_CHAR_BYTES

    private data class Candidate(val hint: Long, val end: Long, val raw: String)
}
