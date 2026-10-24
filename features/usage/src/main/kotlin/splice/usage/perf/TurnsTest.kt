// NEW: Oct 11, 2026 — what a request's own fields say about HOW it ended or WHAT it sat through, applied by the daemon
// over every row of the window. Settings counts these figures ("Given up", "Overloaded", "Cut off", "Waited out",
// "Resumed after no progress"); a count made in the browser over the newest 2,000 rows of a busy command is the cap and
// not the count, so the test runs here, in the one read of the window, and the page shows what the daemon counted.
package splice.usage.perf

import splice.core.perf.OutcomeTag
import splice.core.perf.OutcomeTags
import splice.core.perf.PerfKeys
import splice.core.turn.ErrorType
import splice.core.turn.FailureCause

/** What a row says, read the one way every test of it reads it. */
internal object RowReads {
    fun overloaded(row: PerfRow): Boolean = row.outcome == OutcomeTags.failure(ErrorType.OVERLOADED)

    fun caused(row: PerfRow, cause: FailureCause): Boolean = row.cause == cause.name

    /** Neither cut short nor given up: the upstream's own overloaded and nothing more specific. */
    fun unexplained(row: PerfRow): Boolean =
        row.cause != FailureCause.UPSTREAM_TRUNCATED.name && row.cause != FailureCause.UPSTREAM_STALLED.name

    /** The silence a request held mid-answer at or over [floorMs], or 0: it must have begun after the first byte. */
    fun silence(row: PerfRow, floorMs: Long): Long {
        val gap = row.fields[PerfKeys.UP_GAP_MAX_MS] ?: 0L
        val began = row.fields[PerfKeys.UP_GAP_MAX_START_EPOCH_MS]?.takeIf { it != 0L } ?: return 0L
        val firstByteAt = row.fields[PerfKeys.FIRST_BYTE]?.let { row.ts - (row.fields[PerfKeys.TOTAL] ?: 0L) + it }
        val afterFirstByte = firstByteAt == null || began >= firstByteAt
        return if (gap >= floorMs && afterFirstByte) gap else 0L
    }
}

/** One named test of a row, asked as `test=<wire>`. WAITED_OUT reads the silence floor the same request names. */
internal enum class TurnsTest(val wire: String) {
    /** splice stopped waiting on the upstream: the turn cap, or a stall it re-anchored through and gave up on. */
    GAVE_UP("gaveup") {
        override fun matches(row: PerfRow, silenceFloorMs: Long?): Boolean =
            row.outcome == OutcomeTag.TURN_CAP.wire ||
                (RowReads.overloaded(row) && RowReads.caused(row, FailureCause.UPSTREAM_STALLED))
    },

    /** The upstream said overloaded and nothing more specific happened to the answer. */
    OVERLOADED("overloaded") {
        override fun matches(row: PerfRow, silenceFloorMs: Long?): Boolean =
            RowReads.overloaded(row) && RowReads.unexplained(row)
    },

    /** The upstream answered overloaded because its stream was cut short. */
    CUT_OFF("cutoff") {
        override fun matches(row: PerfRow, silenceFloorMs: Long?): Boolean =
            RowReads.overloaded(row) && RowReads.caused(row, FailureCause.UPSTREAM_TRUNCATED)
    },

    /** A silence at or over the floor that began after the first byte, so it was waited through mid-answer. */
    WAITED_OUT("waited") {
        override fun matches(row: PerfRow, silenceFloorMs: Long?): Boolean =
            silenceFloorMs != null && RowReads.silence(row, silenceFloorMs) > 0
    },

    /** The request sat through a silence splice acted on (a stall the row recorded). */
    SILENT("silent") {
        override fun matches(row: PerfRow, silenceFloorMs: Long?): Boolean =
            (row.fields[PerfKeys.STALL_MS] ?: 0L) != 0L
    },
    ;

    abstract fun matches(row: PerfRow, silenceFloorMs: Long?): Boolean

    /** The silence a request held mid-answer at or over [floorMs], or 0: it must have begun after the first byte. */
    fun silence(row: PerfRow, floorMs: Long): Long = RowReads.silence(row, floorMs)
}
