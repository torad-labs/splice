// NEW: HeadPerfSource, split from ManagedHead.kt (concentration, 2026-08-19) so the
// managed-head surface is not billed for a second column-0 type. Same-package.
package splice.control

/** Reads the head's per-turn perf rows (file truth, numeric fields only, newest last). */
public fun interface HeadPerfSource {
    public fun tailNumeric(n: Int): List<Map<String, Long>>
}

/** The same rows, narrowed to ONE client session — the input the statusline's cost segment needs
 *  (V4-37). A SIBLING of [HeadPerfSource] rather than a second method on it, because that type is a
 *  fun interface with six construction sites and widening it would break every one of them for a
 *  reader most of them never call. Implementations answer with the numeric fields of the rows whose
 *  stored session tag belongs to [sessionId]; the tag on disk is a TRUNCATION of the id the caller
 *  holds, so the matching belongs with the writer that truncates it, not here.
 *
 *  An unknown or empty session yields no rows — never another session's, and never a head-wide
 *  total: a per-session number that silently becomes a per-head number is a differently-wrong
 *  confident number, which is the exact defect this row exists to remove. */
public fun interface HeadSessionPerfSource {
    /** Every matching row the reader's byte-bounded tail holds — deliberately NOT a row count. The
     *  read is already bounded by bytes, and a session's cost needs ALL of its rows in that window:
     *  a `takeLast(n)` here would quietly truncate a long session's spend. */
    public fun tailNumericFor(sessionId: String): List<Map<String, Long>>
}

/** One perf row with its outcome tag — the windowed summary's input (v0.4.0, FEATURES.md §3). */
public data class PerfRow(val ts: Long, val outcome: String, val fields: Map<String, Long>)

/** What one coherent read of the perf files yields for a window (v0.4.0, FEATURES.md §3). */
public data class PerfRowsWindow(
    /** The rows recorded at or after the cutoff in FILE order (the order they were appended, which is
     *  the order the cumulative counters were sampled in), across every generation kept. */
    val rows: List<PerfRow>,
    /** The minimum timestamp a VALID row holds at all, across generations: the retention evidence
     *  that tells sparse traffic (files reach past the window, few rows in it) from truncated files
     *  (rotation ate the window's start). Null when the source cannot say, which the summary treats
     *  as "no further back than the oldest row it returned". */
    val oldestHeldTs: Long? = null,
    /** The cumulative async-io-drops counter on the newest parseable row BEFORE the cutoff: the
     *  baseline the first in-window row's counter is measured against, so a drop between them is
     *  not lost. */
    val dropsBefore: Long? = null,
    /** Why a generation could not be read (rendered, secret-free), or null when every generation the
     *  files keep was read whole. Absence is not an error; a permission or I/O failure is, and the
     *  summary carries it instead of presenting a broken instrument as short retention. */
    val readError: String? = null,
    /** Lines parsed and rejected (not JSON, no top-level unquoted ts): tolerated one by one, but a
     *  generation that yields ONLY such lines is a broken file, not an idle head, and the summary
     *  says so instead of "no rows recorded yet" (review 2026-09-14). */
    val skipped: Int = 0,
)

/** The rows recorded at or after [sinceMs] with the evidence the summary needs about them. */
public fun interface PerfRowsSource {
    public fun window(sinceMs: Long): PerfRowsWindow
}
