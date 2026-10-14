package splice.client.transcript.capture

// why: match file paging's 16 MiB source ceiling while refusing, rather than splitting, an oversized complete message.
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
        val kept = ArrayList<CaptureSpan>()
        var bytes = 0L
        var next: Int? = null
        var boundary = position == null
        source.scan { span ->
            if (!boundary) boundary = span.start == position
            when {
                !boundary -> true
                kept.isNotEmpty() && full(kept.size, bytes + span.bytes, limit) -> {
                    next = span.start
                    false
                }
                else -> {
                    kept += span
                    bytes += span.bytes
                    true
                }
            }
        }
        return if (boundary) CapturedWindow(kept, bytes, next, null) else null
    }

    private fun before(position: Int?, limit: Int): CapturedWindow? {
        val kept = ArrayDeque<CaptureSpan>()
        var bytes = 0L
        var earlier: Int? = null
        var boundary = terminal(position)
        source.scan { span ->
            if (position != null && span.start >= position) {
                boundary = span.start == position
                false
            } else {
                kept.addLast(span)
                bytes += span.bytes
                while (kept.size > 1 && overBudget(kept.size, bytes, limit)) {
                    bytes -= kept.removeFirst().bytes
                    earlier = kept.first().start
                }
                true
            }
        }
        return if (boundary) CapturedWindow(kept.toList(), bytes, null, earlier) else null
    }

    private fun terminal(position: Int?): Boolean = position == null || position == source.array?.end?.minus(1)

    private fun full(count: Int, bytes: Long, limit: Int): Boolean = count >= limit || bytes > MAX_CAPTURE_PAGE_BYTES

    private fun overBudget(count: Int, bytes: Long, limit: Int): Boolean =
        count > limit || bytes > MAX_CAPTURE_PAGE_BYTES
}
