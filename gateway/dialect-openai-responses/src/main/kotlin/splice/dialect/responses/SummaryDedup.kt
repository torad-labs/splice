// PORT-OF: ResponsesStreamTranslator.kt @ f875801 — sequential_cutoff restatement dedup. The
// original per-round cursor now rides a bounded previous/current logical window across keyed turns.
package splice.dialect.responses

import splice.core.turn.SharedSummaryParts

// below this length an exact repeat is plausibly a genuine token fragment; whole summary parts
// (titled sections) are far longer
private const val SUMMARY_PART_DEDUP_MIN_CHARS = 20

// sequential_cutoff restatement dedup. This mode restates summary parts in TWO distinct ways, and
// one structure conflating them is why this kept oscillating (revert/reapply/rescope):
//   (A) CROSS-item recap — each NEW reasoning item replays every part emitted so far, IN ORDER, as
//       a leading prefix, then appends its genuinely-new parts (probed 2026-07-19: part(1,0) ==
//       part(0,0)). Suppressed by matching the leading run against the ordered recap window via a
//       per-item cursor.
//   (B) WITHIN-item repeat — item.done can restate parts whose deltas then re-arrive, or vice versa
//       (openai/codex#16801 ordering anomaly, live 2026-07-19). Suppressed by per-item exact records.
// A turn-global SET over-suppressed a paragraph two DISTINCT items coincidentally shared
// (2026-07-20); a per-ITEM set alone under-suppressed the cross-item recap (the duplication
// staircase). Splitting the two jobs keeps the coincidence (per-item, non-leading) while killing
// the staircase (ordered leading prefix). State + decision live together here (2026-07-23).
// SharedSummaryParts is conversation-lifetime only with a complete session+conversation identity,
// but its ACTIVE recap window is the logical chain observed by the immediately preceding translator
// round. A round that anchors extends that chain; unrelated leading text replaces it. Per-item exact
// records follow the same previous/current window. Count/byte pressure stops tracking and displays
// later parts unchanged; it never shifts active candidate cursors.
internal class SummaryDedup(private val active: Boolean, private val shared: SharedSummaryParts) {
    // One candidate cursor per equal-valued occurrence. A first-value indexOf is wrong when the
    // logical chain contains the same paragraph twice: only later parts reveal which suffix is the
    // recap. Empty means this item's leading recap has diverged permanently.
    private val recapCandidates = HashMap<Int, IntArray>()

    /** True to SUPPRESS a recap/repeat part of item [oi]; a genuinely-new part returns false and is
     *  recorded so later items' recaps (A) and this item's own re-arrivals (B) match. Parts under
     *  the min length always pass and are never recorded (plausibly genuine token fragments).
     *
     *  An item's first part opens every equal anchor in the active window. Each later part narrows
     *  those candidates; the first total divergence ends recap matching for that item. */
    fun suppress(oi: Int, part: String): Boolean {
        if (!active || part.length < SUMMARY_PART_DEDUP_MIN_CHARS) return false
        if (!shared.canTrack(oi, part)) {
            recapCandidates.remove(oi)
            return false
        }
        return suppressTracked(oi, part)
    }

    private fun suppressTracked(oi: Int, part: String): Boolean {
        val candidates = recapCandidates[oi]
        val matched = if (candidates == null) {
            shared.anchorsOf(part)
        } else {
            candidates.filter { shared.partAt(it) == part }.toIntArray()
        }
        return if (matched.isNotEmpty() && shared.markRecap(oi, part)) {
            recapCandidates[oi] = IntArray(matched.size) { matched[it] + 1 }
            true
        } else {
            recapCandidates[oi] = IntArray(0)
            !shared.markEmitted(oi, part)
        }
    }
}
