// NEW: compact cached perf-row facts and the seams between decoding, retention and selection.
package splice.app.sources

import splice.usage.perf.PerfRow

// Conservative retained-string charge: object/array headers and alignment, plus UTF-16 storage.
internal const val PERF_STRING_OVERHEAD_BYTES = 64L

// UTF-16 is the worst-case String backing width, even when compact strings are enabled.
internal const val PERF_CHAR_BYTES = 2L

// Covers the facts, row, entry, boxed scalars and linked queue node with 64-bit references.
private const val PERF_RECORD_OVERHEAD_BYTES = 320L

/** Parsed facts plus the original skip hints; window selection never changes timestamp authority. */
internal data class PerfCachedLine(
    val row: PerfRow?,
    val numericBytes: Long,
    val leadingTs: Long?,
    val emptyModel: Boolean,
    val dropsCandidate: Boolean,
    val drops: Long?,
    val probe: Boolean,
) {
    /** Upper-bound charge for object/queue overhead, primitive field storage and descriptive strings. */
    val retainedBytes: Long
        get() {
            val value = row ?: return PERF_RECORD_OVERHEAD_BYTES
            val text = listOf(
                value.outcome,
                value.model,
                value.session,
                value.sessionId,
                value.responseMessageId,
                value.account,
                value.turn,
            ).sumOf { if (it == null) 0L else PERF_STRING_OVERHEAD_BYTES + it.length * PERF_CHAR_BYTES }
            return PERF_RECORD_OVERHEAD_BYTES + numericBytes + text
        }
}

internal fun interface PerfLineDecode {
    fun decode(line: String): PerfCachedLine
}

/** Older evicted input keeps its original on-demand parser; retained input supplies only compact facts. */
internal interface PerfLineVisit {
    /** A canonical pre-cutoff hint, or null when JSON decoding is needed to establish this row. */
    fun beforeCutoff(line: String): Long? = null

    /** The range still lies above known retention evidence and wholly below this window. */
    fun canSkip(minimum: Long, maximum: Long): Boolean = false
    fun raw(line: String)
    fun kept(line: PerfCachedLine)
}
