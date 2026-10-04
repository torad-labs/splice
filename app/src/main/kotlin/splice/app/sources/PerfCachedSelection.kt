// NEW: replay every uncached byte gap, or a bounded before-window range's original raw anchors.
package splice.app.sources

import java.nio.channels.FileChannel

/** Selection is ordered by source offsets. A wider query must reread the rejected ranges, never lose them. */
internal class PerfCachedSelection(
    private val channel: FileChannel,
    private val visit: PerfLineVisit,
    ranges: List<PerfSkippedRange>,
) {
    private val ranges = ranges.iterator()
    private var range: PerfSkippedRange? = null
    private var position = 0L

    fun kept(start: Long, end: Long, line: PerfCachedLine) {
        gap(start)
        visit.kept(line)
        position = end
    }

    fun gap(end: Long) {
        while (position < end) {
            val current = current()
            if (current == null || current.start >= end) {
                raw(end)
            } else if (current.start > position) {
                raw(minOf(current.start, end))
            } else {
                val next = minOf(current.end, end)
                val wholeRange = position == current.start && next == current.end
                if (wholeRange && visit.canSkip(current.minimum, current.maximum)) {
                    current.replay(visit)
                    position = next
                } else {
                    raw(next)
                }
            }
        }
    }

    private fun current(): PerfSkippedRange? {
        while (range == null || requireNotNull(range).end <= position) {
            if (!ranges.hasNext()) {
                range = null
                return null
            }
            range = ranges.next()
        }
        return range
    }

    private fun raw(end: Long) {
        val reader = PerfLineReader(channel, position, end)
        while (true) visit.raw(reader.next() ?: break)
        position = end
    }
}
