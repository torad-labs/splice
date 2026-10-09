// NEW: compact cached perf-row facts and the seams between decoding, retention and selection.
package splice.app.sources

import splice.usage.perf.PerfRow
import java.io.IOException

// Conservative retained-string charge: object/array headers and alignment, plus UTF-16 storage.
internal const val PERF_STRING_OVERHEAD_BYTES = 64L

// UTF-16 is the worst-case String backing width, even when compact strings are enabled.
internal const val PERF_CHAR_BYTES = 2L

// Covers the facts, row, entry, boxed scalars, linked queue node and 32-byte identity value with 64-bit references.
internal const val PERF_RECORD_OVERHEAD_BYTES = 352L

/** Parsed facts plus the original skip hints; window selection never changes timestamp authority. */
internal data class PerfCachedLine(
    val row: PerfRow?,
    val numericBytes: Long,
    val leadingTs: Long?,
    val emptyModel: Boolean,
    val drops: PerfDropsHint,
    val probe: Boolean,
    /** Projected descriptions are charged once in the source's bounded sharing pool. */
    val retainedTextBytes: Long? = null,
) {
    /** Upper-bound charge for object/queue overhead, primitive field storage and descriptive strings. */
    val retainedBytes: Long
        get() {
            val value = row ?: return PERF_RECORD_OVERHEAD_BYTES
            val text = listOf(
                value.outcome,
                value.cause,
                value.facts.model,
                value.facts.session,
                value.transcript.sessionId,
                value.transcript.responseMessageId,
                value.facts.account,
                value.turn,
                value.turnId,
            ).sumOf { if (it == null) 0L else PERF_STRING_OVERHEAD_BYTES + it.length * PERF_CHAR_BYTES }
            return PERF_RECORD_OVERHEAD_BYTES + numericBytes + (retainedTextBytes ?: text)
        }
}

internal fun interface PerfLineDecode {
    fun decode(line: String): PerfCachedLine
}

/** An aggregate snapshot cannot authorize reading different bytes for its displayed row. */
internal class PerfProjectionChanged : IOException("perf generation changed while reading display rows")

/** Transform retained facts, not the parser or its timestamp/probe/error authority. */
internal fun interface PerfLineKeep {
    fun keep(line: PerfCachedLine, names: PerfFieldNames): PerfCachedLine
}

/** Older evicted input keeps its original on-demand parser; retained input supplies only compact facts. */
internal interface PerfLineVisit {
    /** A canonical pre-cutoff hint, or null when JSON decoding is needed to establish this row. */
    fun beforeCutoff(line: String): Long? = null

    /** The range still lies above known retention evidence and wholly below this window. */
    fun canSkip(minimum: Long, maximum: Long): Boolean = false

    /** Validated evicted rows establish time evidence without treating raw header hints as truth. */
    fun knownSpan(minimum: Long, maximum: Long) = Unit
    fun raw(line: String)
    fun kept(line: PerfCachedLine)
}
