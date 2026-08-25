// NEW: HeadPerfSource, split from ManagedHead.kt (concentration, 2026-08-19) so the
// managed-head surface is not billed for a second column-0 type. Same-package.
package splice.control

/** Reads the head's per-turn perf rows (file truth, numeric fields only, newest last). */
public fun interface HeadPerfSource {
    public fun tailNumeric(n: Int): List<Map<String, Long>>
}
