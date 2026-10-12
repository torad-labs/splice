// NEW: Oct 11, 2026 — the lines one cached generation retains, in file order, behind one seam: whole objects for the
// newest requests, a dense encoding for a compact window.
package splice.app.sources

import java.util.LinkedList

/** One retained line: where it starts and ends, its facts, and its raw text while it is still an unterminated last line. */
internal data class PerfCachedEntry(
    val start: Long,
    val line: PerfCachedLine,
    val raw: String?,
    var end: Long = start,
) {
    val retainedBytes: Long
        get() = line.retainedBytes + (raw?.let { PERF_STRING_OVERHEAD_BYTES + it.length * PERF_CHAR_BYTES } ?: 0L)
}

/** The retained lines of one generation. Only the last line may be unterminated, and it is removed and read again as the
 *  file grows; the first is evicted when the cache is over its ceiling. [bytes] is what the list charges the cache. */
internal interface PerfEntryList : Iterable<PerfCachedEntry> {
    val size: Int
    val bytes: Long

    fun isEmpty(): Boolean = size == 0

    fun addLast(entry: PerfCachedEntry)

    fun removeFirst(): PerfCachedEntry

    fun lastOrNull(): PerfCachedEntry?

    fun removeLast(): PerfCachedEntry

    /** One more byte on the last line, when it ends at [at]: the LF a split CRLF delivered. */
    fun extendLast(at: Long)

    fun clear()
}

/** Makes the list of one generation, which knows its generation by [ref]. */
internal fun interface PerfEntryFactory {
    fun of(ref: PerfGenerationRef): PerfEntryList
}

/** Every retained line as an object: fast to select, and about a kilobyte a line. */
internal class PerfLinkedEntries : PerfEntryList {
    private val lines = LinkedList<PerfCachedEntry>()
    override var bytes = 0L
        private set
    override val size: Int get() = lines.size

    override fun iterator(): Iterator<PerfCachedEntry> = lines.iterator()

    override fun addLast(entry: PerfCachedEntry) {
        lines.addLast(entry)
        bytes += entry.retainedBytes
    }

    override fun removeFirst(): PerfCachedEntry = lines.removeFirst().also { bytes -= it.retainedBytes }

    override fun lastOrNull(): PerfCachedEntry? = lines.lastOrNull()

    override fun removeLast(): PerfCachedEntry = lines.removeLast().also { bytes -= it.retainedBytes }

    override fun extendLast(at: Long) {
        lines.lastOrNull()?.takeIf { it.end == at }?.let { it.end++ }
    }

    override fun clear() {
        lines.clear()
        bytes = 0L
    }
}
