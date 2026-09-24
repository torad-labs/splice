// Every label this widget prints. Lowercase, three words or fewer, no em-dash (CONTRACTS.md
// section 4, enforced by the label wall). Outcome names are DATA from the daemon; the ones it is
// known to write get words in OUTCOME_WORDS, and any other name prints as the daemon spells it.
export const S = {
  outcomes: 'outcomes',
  events: 'recent compactions',
  detail: 'compaction',
  total: 'total',
  count: 'count',
  share: 'share',
  when: 'when',
  head: 'head',
  outcome: 'outcome',
  chars: 'summary length',
  took: 'took',
  instructions: 'instructions',
  error: 'error',
  openEvent: 'open compaction',
  sample: 'sample data',
  none: 'no compactions yet',
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
  /** Closes the opened detail; printed only where the detail is a full-screen swell (a phone). */
  close: 'close',
} as const;

/** The daemon's outcome names in words (console review, 2026-09-24: the column printed
 *  `tooled_no_text`). The set is the one the daemon writes (StreamPromote.kt, StreamCompact.kt,
 *  PickedText.kt, OutcomeTag.EMPTY_MODEL) plus `upstream_error`, which older rows still carry. The
 *  set stays open: a name missing here prints as the daemon spells it (`outcomeText`). */
export const OUTCOME_WORDS: Readonly<Record<string, string>> = {
  model_text: 'summary written',
  model_thinking: 'summary from reasoning',
  model_text_weak: 'weak summary',
  tooled_no_text: 'tool call instead',
  empty_model: 'empty reply',
  stream_error: 'stream failed',
  upstream_error: 'provider error',
};

/** The instructions a compaction ran under, when it ran under the client's own. */
export const CLIENT_INSTRUCTIONS = 'client default';
