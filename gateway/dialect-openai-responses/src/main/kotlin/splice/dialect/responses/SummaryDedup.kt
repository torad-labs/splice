// PORT-OF: ResponsesStreamTranslator.kt @ f875801 — invariants unchanged: the sequential_cutoff
// restatement dedup state machine, byte-identical, with its oscillating-bug design history intact.
package splice.dialect.responses

import splice.core.turn.SharedSummaryParts

// below this length an exact repeat is plausibly a genuine token fragment; whole summary parts
// (titled sections) are far longer
private const val SUMMARY_PART_DEDUP_MIN_CHARS = 20

// Per-item recap cursor sentinel: the leading cross-item recap has ended for this item, so every
// remaining part is genuinely new (only within-item exact repeats are still suppressed).
private const val RECAP_DONE = -1

// sequential_cutoff restatement dedup. This mode restates summary parts in TWO distinct ways, and
// one structure conflating them is why this kept oscillating (revert/reapply/rescope):
//   (A) CROSS-item recap — each NEW reasoning item replays every part emitted so far, IN ORDER, as
//       a leading prefix, then appends its genuinely-new parts (probed 2026-07-19: part(1,0) ==
//       part(0,0)). Suppressed by matching the leading run against the ordered emitted list via a
//       per-item cursor.
//   (B) WITHIN-item repeat — item.done can restate parts whose deltas then re-arrive, or vice versa
//       (openai/codex#16801 ordering anomaly, live 2026-07-19). Suppressed by a per-item exact set.
// A turn-global SET over-suppressed a paragraph two DISTINCT items coincidentally shared
// (2026-07-20); a per-ITEM set alone under-suppressed the cross-item recap (the duplication
// staircase). Splitting the two jobs keeps the coincidence (per-item, non-leading) while killing
// the staircase (ordered leading prefix). State + decision live together here (2026-07-23).
// The per-item sets are scoped per output_index SLOT across the SHARED state's lifetime
// (2026-07-26, turn-scoped; 2026-08-26, conversation-scoped where a conversation key exists):
// continuation rounds share them and the backend restarts output_index at 0 each round, so a
// byte-identical non-leading part shared by two genuinely different items landing in the same
// slot is suppressed across rounds.
// Accepted (operator call): an exact identity is overwhelmingly a restatement, and no-duplicates
// wins. Re-keying the sets by round or item id is the one fix NOT to make — when the backend
// re-titles a repeat into the same slot, that keying is what lets the mirror duplication back in.
// The 2026-07-20 case stays intact: WITHIN a round, distinct items still get distinct slots.
internal class SummaryDedup(private val active: Boolean, private val shared: SharedSummaryParts) {
    // The emitted parts are TURN-scoped: the caller hands over the turn's one SharedSummaryParts
    // (TurnMeta's default), so continuation rounds see each other's emissions. recapCursor is
    // deliberately per round — each round's stream re-starts its leading recap at position 0 of
    // the shared list, which is exactly what suppresses it.
    private val recapCursor = HashMap<Int, Int>()

    /** True to SUPPRESS a recap/repeat part of item [oi]; a genuinely-new part returns false and is
     *  recorded so later items' recaps (A) and this item's own re-arrivals (B) match. Parts under
     *  the min length always pass and are never recorded (plausibly genuine token fragments).
     *
     *  An item's FIRST part anchors the recap ANYWHERE in the emitted list, not only at position 0
     *  (2026-08-26): with conversation-lifetime shared state, a new client turn's recap restates
     *  the TAIL of the previous turn's emission (live claudex scan: 244/803 thinking messages
     *  opened with an already-emitted ordered run, zero non-leading repeats), which a 0-anchored
     *  cursor never matches. Within one stream the recap genuinely starts at part 0, so the anchor
     *  degenerates to the old behavior there. A leading part that merely COINCIDES with an old
     *  part starts an ordered-run match and is suppressed — the 2026-07-26 operator ruling (an
     *  exact >= 20-char identity is overwhelmingly a restatement; no-duplicates wins) extended to
     *  the leading position. */
    fun suppress(oi: Int, part: String): Boolean {
        if (!active || part.length < SUMMARY_PART_DEDUP_MIN_CHARS) return false
        val cursor = recapCursor.getOrDefault(oi, 0)
        // anchorOf returns -1 when absent, which IS RECAP_DONE — the arms below rely on that.
        val at = when {
            cursor == RECAP_DONE -> RECAP_DONE
            cursor == 0 -> shared.anchorOf(part)
            shared.partAt(cursor) == part -> cursor
            else -> RECAP_DONE
        }
        recapCursor[oi] = if (at == RECAP_DONE) RECAP_DONE else at + 1
        return if (at != RECAP_DONE) true else !shared.markEmitted(oi, part)
    }
}
