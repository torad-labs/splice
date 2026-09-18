// Every label this widget prints. Lowercase, three words or fewer, no em-dash (CONTRACTS.md
// section 4, enforced by the label wall). Outcome names are DATA from the daemon, not labels, and
// are printed as the daemon spells them.
export const S = {
  outcomes: 'compact outcomes',
  events: 'compact events',
  detail: 'event detail',
  total: 'total',
  count: 'count',
  when: 'when',
  head: 'head',
  outcome: 'outcome',
  chars: 'chars',
  took: 'took',
  status: 'status',
  error: 'error',
  openEvent: 'open event',
  sample: 'sample data',
  none: 'no events recorded',
  live: 'live',
  /** THE HOLDER EDGE'S WORD, one per STATE and not one per outcome (m1 design review B10, ruled
   *  2026-09-18). The edge used to carry the outcome name itself, and `ui.css:349` gives the edge
   *  label a fixed 6ch budget that clips rather than pushing the fields (B1, deliberate) — so
   *  `model_summary` and `model_fallback` both printed `mode…` on adjacent rows: one label, two
   *  outcomes, and the full name already sitting two cells to the right.
   *
   *  A WORD PER OUTCOME IS NOT AVAILABLE and that is a fact about the payload, not a preference:
   *  `by_outcome` is `Record<string, number>` (shared/api/index.ts:244), so the daemon's outcome
   *  set is open and the console cannot enumerate it — a per-outcome table would print nothing for
   *  the first name `gateway/compact` adds. The state set is closed, because `stateOf` computes it.
   *
   *  THE THREE WORDS ARE NOT NEW. They are the console's existing holder-edge vocabulary with the
   *  identical colour mapping: doctor prints `ok`/`warn`/`fail` on a green/amber/red edge, and
   *  scope-chart's header names the same three states in prose ("they mean attention — ok, warn,
   *  unhealthy"). The precedent for the move is on the page beside this one: usage's `slotted:
   *  'auto'` and `undeclared: 'vacant'` replaced an edge that repeated the very next cell, and
   *  they are words that say the state rather than abbreviations of the name. Several rows sharing
   *  one state word is what that precedent does too — the edge says what happened, the cell says
   *  which outcome it was, and neither is a truncation of the other. */
  state: { ok: 'ok', warn: 'warn', fail: 'fail' },
} as const;
