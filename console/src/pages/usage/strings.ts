// Every label this page prints. Lowercase, three words or fewer, no em-dash (CONTRACTS.md
// section 4, enforced by the label wall). Sentences live in the component.
import { ABSENT } from '@shared/lib';

export const S = {
  title: 'usage',
  byHead: 'by head',
  byModel: 'by model',
  window: 'window',
  heads: 'heads',
  head: 'head',
  models: 'models',
  burn: 'burn',
  tokens: 'tokens',
  turns: 'turns',
  inTokens: 'in tokens',
  cached: 'cached',
  cacheWrite: 'cache write',
  outTokens: 'out tokens',
  amplification: 'in per out',
  perTurn: 'in per turn',
  wireDelta: 'wire delta',
  /** The head's token limit for the window, and what it has used against it. */
  ceiling: 'limit',
  spent: 'tokens used',
  /** How long until the limit, at the rate the head is using tokens now. */
  exhaustion: 'runs out in',
  /** A head burning nothing against its limit. */
  idle: 'not in use',
  updated: 'updated',
  limited: 'rate limited',
  sample: 'sample data',
  detail: 'head detail',
  openHead: 'open head',
  slot: 'slot',
  contextWindow: 'context window',
  sourceLabel: 'window source',
  inputRate: 'input rate',
  cacheReadRate: 'cache read rate',
  cacheWriteRate: 'cache write rate',
  outputRate: 'output rate',
  pinned: 'pinned',
  /** The holder edge's state for a tier a model fills, against one it does not: `not declared`,
   *  the same word the models page prints for the same fact. It replaces the slot name, which is
   *  the very next field on the same strip (m1 design review B10). */
  slotted: 'auto',
  undeclared: 'vacant',
  /** The `slot` field of a model no tier hands out. */
  noSlot: 'none',
  /** What any cell with no value prints — the approved comp's own glyph (m1 design review B8).
   *  It replaces four phrasings this page used for one fact: `not reported`, `no turns` (twice),
   *  `no output`, `not declared`, `no rates` (twice) and the empty string. */
  absent: ABSENT,
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
