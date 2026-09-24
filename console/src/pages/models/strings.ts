// Every label this page prints. Lowercase, three words or fewer, no em-dash (CONTRACTS.md
// section 4, enforced by the label wall).
import { ABSENT } from '@shared/lib';

export const S = {
  title: 'models',
  byHead: 'by head',
  byProvider: 'by provider',
  catalog: 'catalog',
  tiers: 'tiers',
  model: 'model',
  /** The Claude Code model tier (opus, sonnet, haiku, fable) a model answers for on this head. */
  slot: 'tier',
  contextWindow: 'context window',
  windowSource: 'window from',
  rates: 'rates',
  rateInput: 'input',
  rateRead: 'cache read',
  rateWrite: 'cache write',
  rateOutput: 'output',
  pinned: 'pinned',
  noSlot: 'none',
  /** What any cell with no value prints — the approved comp's own glyph (m1 design review B8),
   *  which replaces `no rates` in every rate cell and `not declared` in a missing tier's model. */
  absent: ABSENT,
  pinnedYes: 'pinned',
  /** The edge of the opened model's rate strip. `none` and not `no rates` (8 characters) because
   *  the rate cells themselves print the absence glyph. */
  noRates: 'none',
  /** The edge of the opened model's window strip: the head is the panel's own subject, so the edge
   *  names the state instead of repeating its key. */
  window: 'window',
  extraWindows: 'extra windows',
  windowRules: 'window rules',
  defaultWindow: 'default window',
  headWindow: 'head window',
  sample: 'sample data',
  openModel: 'open model',
  /** Closes the opened detail; printed only where the detail is a full-screen swell (a phone). */
  close: 'close',
} as const;

// THE ABSENCE VOCABULARY, written down where the next person writing a cell will see it (M1-66).
// These are DIFFERENT FACTS and collapsing them destroys information; adding a word without one of
// these meanings is how the console reached eleven phrasings for "nothing here".
//   –          (en dash, ABSENT in @shared/lib) nobody reported a value for this cell. The
//              default. It was `n/r`, which no reader could expand.
//   none       the question was asked and its answer is nothing (no tier hands this model out).
//   unknown    we asked and were NOT TOLD - a different fact from none, and never a zero.
//   unavailable  it exists and we cannot reach it.
//   ineligible   it does not apply here.
//   not built    it does not exist yet; a pending route names its row.
// A site whose fact cannot be told from the code KEEPS the word it has and gets a note beside it.
// Renaming an absence you have not understood is how `unknown` silently becomes `none`.
