// NEW: HeadPerfSource, split from ManagedHead.kt (concentration, 2026-08-19) so the
// managed-head surface is not billed for a second column-0 type. Same-package.
package splice.usage.perf

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

/** How many rows the COST reader DROPPED because they would not parse (V4-45).
 *
 *  A SIBLING of [HeadSessionPerfSource] for the same reason that type is a sibling of
 *  [HeadPerfSource] — a fun interface carries one method, and widening either would break every
 *  construction site for a reader most of them never call.
 *
 *  NOT A DUPLICATE OF [PerfRowsWindow.skipped], and the difference is the whole reason both exist.
 *  That one is an Int, scoped to ONE WINDOW, produced by the control-plane reader that walks whole
 *  generations, consumed into the /api/perf payload and its coverage note, and it resets on every
 *  read. This one is a Long, MONOTONIC for the life of the reader, produced by the cost-plane
 *  reader that walks a byte-bounded tail, and consumed by the statusline. Two instruments, two
 *  planes, two readers, two lifetimes: folding either into the other breaks one of them, because
 *  a per-window count cannot qualify a figure rendered long after the read that skipped, and a
 *  monotonic total cannot say whether THIS generation is a broken file or an idle head.
 *
 *  MONOTONIC IS THE CONTRACT, not an implementation detail: a healthy read must NOT clear it. The
 *  rows it counted are still missing from every figure summed afterwards, so forgetting them
 *  renders a clean number over a holed file — this row's own defect, one layer up.
 *
 *  HEAD-WIDE, while the cost segment it qualifies is PER-SESSION. A renderer may therefore say
 *  that the figure it shows may be low and how many rows the reader dropped; it may NEVER say that
 *  this session lost N turns. That would be a differently-wrong confident number, which is the
 *  exact defect V4-37 was cut to remove. */
public fun interface HeadPerfSkipSource {
    public fun skippedRowCount(): Long
}

/** One perf row with its outcome tag — the windowed summary's input (v0.4.0, FEATURES.md §3).
 *
 *  V4-127: [fields] is the NUMERIC half only, because the reader accumulated it by asking every value
 *  in the row whether it parses as a Long. The writer's five string-and-flag facts — model, session,
 *  account, cache_cold, compact — therefore reached no control-plane consumer at all: they were in the
 *  file, in the JsonObject the reader had just parsed, and dropped one line later. The console's
 *  per-turn view (FEATURES.md §6) is built out of exactly those five, so they are carried as named
 *  properties rather than re-derived by a second reader over the same bytes.
 *
 *  NULL MEANS THE ROW DOES NOT CARRY THE FIELD, deliberately distinguished from a false or empty
 *  value. `cache_cold` is written ONLY alongside an account (PerfStats.record), so a row with no
 *  account never had the question asked — reading that as `false` would report "the cache was warm"
 *  about a turn where nothing looked, which is a did-not-run wearing a legitimate answer, the same
 *  defect class the unset-port named-5xx rule exists for. `compact` and `model` are unconditional in
 *  the current writer, so null there means a LEGACY or torn row, and a payload omits the field rather
 *  than inventing a value for it.
 *
 *  NAMED ARGUMENTS ARE THE CONTRACT at every construction site (the ModelRates scar, V4-127): four of
 *  these five are nullable and two of the strings are adjacent, so a POSITIONAL call that swaps
 *  session and account compiles, passes, and reports the wrong facts with a green suite. */
public data class PerfRow(
    val ts: Long,
    val outcome: String,
    val fields: Map<String, Long>,
    val model: String? = null,
    val session: String? = null,
    val account: String? = null,
    val cacheCold: Boolean? = null,
    val compact: Boolean? = null,
)

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
    /** The maximum timestamp a VALID row holds at all, in or before the window: the head's last turn
     *  as the files record it, so a window that excludes the newest row still names it. The mirror of
     *  [oldestHeldTs], and null on the same terms: the source cannot say, which the summary treats as
     *  "no newer than the newest row it returned". Declared last so no positional call site moves. */
    val newestHeldTs: Long? = null,
)

/** The rows recorded at or after [sinceMs] with the evidence the summary needs about them. */
public fun interface PerfRowsSource {
    public fun window(sinceMs: Long): PerfRowsWindow
}
