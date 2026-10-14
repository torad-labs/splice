package splice.client.transcript

internal const val MAX_CAPTURE_PAGE_BYTES: Long = 16L shl 20

internal data class CapturedWindow(
    val spans: List<CaptureSpan>,
    val bytes: Long,
    val next: Int?,
    val earlier: Int?,
)

/** Bounded source offsets for complete API messages, never decoded conversation history. */
internal class CapturedPageWindow(private val source: CapturedMessageSpans) {
    fun read(position: Int?, backwards: Boolean, limit: Int): CapturedWindow? =
        if (backwards) before(position, limit) else after(position, limit)

    private fun after(position: Int?, limit: Int): CapturedWindow? {
        val candidates = if (position == null) source.all() else source.all().dropWhile { it.start != position }
        val iterator = candidates.iterator()
        if (position != null && !iterator.hasNext()) return null
        val kept = ArrayList<CaptureSpan>()
        var bytes = 0L
        var next: Int? = null
        while (iterator.hasNext()) {
            val span = iterator.next()
            val size = source.bytes(span)
            if (kept.isNotEmpty() && full(kept.size, bytes + size, limit)) {
                next = span.start
                break
            }
            kept += span
            bytes += size
        }
        return CapturedWindow(kept, bytes, next, null)
    }

    private fun before(position: Int?, limit: Int): CapturedWindow? {
        val kept = ArrayDeque<CaptureSpan>()
        val sizes = ArrayDeque<Long>()
        var bytes = 0L
        var earlier: Int? = null
        var boundary = terminal(position)
        for (span in source.all()) {
            if (position != null && span.start >= position) {
                boundary = span.start == position
                break
            }
            val size = source.bytes(span)
            kept.addLast(span)
            sizes.addLast(size)
            bytes += size
            while (kept.size > 1 && overBudget(kept.size, bytes, limit)) {
                kept.removeFirst()
                bytes -= sizes.removeFirst()
                earlier = kept.first().start
            }
        }
        return if (boundary) CapturedWindow(kept.toList(), bytes, null, earlier) else null
    }

    private fun terminal(position: Int?): Boolean = position == null || position == source.array?.end?.minus(1)

    private fun full(count: Int, bytes: Long, limit: Int): Boolean = count >= limit || bytes > MAX_CAPTURE_PAGE_BYTES

    private fun overBudget(count: Int, bytes: Long, limit: Int): Boolean =
        count > limit || bytes > MAX_CAPTURE_PAGE_BYTES
}
