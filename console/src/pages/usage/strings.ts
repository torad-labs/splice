// Every word this page prints (docs/design/DESIGN.md section 10). S holds labels: three words or
// fewer, sentence case. H holds help: one sentence of twelve words or fewer, shown on hover or focus.
// U holds the unit words printed beside a figure. tests/copy.test.ts holds all three.
import { ABSENT } from '@shared/lib';

export const S = {
  title: 'Usage',
  about: 'About usage',
  /** The stat row: the chosen window's sums across every head. */
  totals: 'Totals',
  byHead: 'By head',
  byModel: 'By model',
  window: 'Window',
  heads: 'Heads',
  head: 'Head',
  models: 'Models',
  tokens: 'Tokens',
  turns: 'Turns',
  cost: 'Cost',
  unpricedWhy: 'Unpriced heads',
  /** The heads table's sparkline column: each head's turns, hour by hour. */
  trend: 'Turns per hour',
  inTokens: 'Input',
  fresh: 'Fresh input',
  cached: 'Cache read',
  cacheWrite: 'Cache write',
  outTokens: 'Output',
  amplification: 'In per out',
  perTurn: 'In per turn',
  wireDelta: 'Wire delta',
  /** The head's token limit for the window, and what it has used against it. */
  ceiling: 'Limit',
  spent: 'Tokens used',
  /** How long until the limit, at the rate the head is using tokens now. */
  exhaustion: 'Runs out in',
  /** A head burning nothing against its limit. */
  idle: 'Idle',
  updated: 'Updated',
  limited: 'Rate limited',
  sample: 'Sample data',
  /** The plan limits rack: each head's own 5h and 7d plan windows. */
  planLimits: 'Plan limits',
  /** When splice last read the head's windows. */
  read: 'Read',
  cacheRead: 'Cache read',
  share: 'Share',
  /** A window whose reset time passed after splice read it: the figure is from before the reset. */
  alreadyReset: 'Already reset',
  unknown: 'Unknown',
  detail: 'Head detail',
  openHead: 'Open head',
  slot: 'Slot',
  contextWindow: 'Context window',
  sourceLabel: 'Window source',
  inputRate: 'Input rate',
  outputRate: 'Output rate',
  /** The `slot` field of a model no tier hands out. */
  noSlot: 'None',
  /** The empties, one factual line each. */
  noUsage: 'No usage yet',
  noCatalog: 'Catalog unavailable',
  noModels: 'No models declared',
  noPlan: 'No plan limits',
  /** What any cell with no value prints (m1 design review B8). */
  absent: ABSENT,
} as const;

export const H = {
  about: 'Token sums from the daemon; cost is estimated from rate cards.',
  noUsage: 'A head reports once it has run a turn.',
  noCatalog: 'This splice version does not serve the model catalog.',
  noModels: 'The topology declares no models for this head.',
  noPlan: "Plan windows come from each provider's response headers.",
  /** The cost figure when some heads carry no rate card: they are left out, never priced at zero. */
  unpriced: 'Heads without a rate card are left out of cost.',
  /** The cache read share is a diagnostic, not a saving. */
  cacheRead: 'Share of input served from cache; the plan still meters it.',
} as const;

/** Words printed beside a figure. */
export const U = {
  cached: 'cached',
  turns: 'turns',
  unpriced: 'unpriced',
  resets: 'Resets',
  /** After a window's reported length: `5h window`, `30d window`. */
  window: 'window',
  lastDay: 'last 24h',
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
