// NEW: HeadPerfSource, split from ManagedHead.kt (concentration, 2026-08-19) so the
// managed-head surface is not billed for a second column-0 type. Same-package.
package splice.control

/** Reads the head's per-turn perf rows (file truth, numeric fields only, newest last). */
public fun interface HeadPerfSource {
    public fun tailNumeric(n: Int): List<Map<String, Long>>
}

/** One perf row with its outcome tag — the windowed summary's input (v0.4.0, FEATURES.md §3). */
public data class PerfRow(val ts: Long, val outcome: String, val fields: Map<String, Long>)

/** Rows recorded at or after [sinceMs], oldest first, across every generation the file keeps. */
public fun interface PerfRowsSource {
    public fun rowsSince(sinceMs: Long): List<PerfRow>
}
