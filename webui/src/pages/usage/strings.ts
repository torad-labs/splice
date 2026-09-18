// Every label this page prints. Lowercase, three words or fewer, no em-dash (CONTRACTS.md
// section 4, enforced by the label wall). Sentences live in the component.
export const S = {
  title: 'usage',
  byHead: 'by head',
  byModel: 'by model',
  window: 'window',
  heads: 'heads',
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
  ceiling: 'ceiling',
  spent: 'spent',
  exhaustion: 'exhaustion',
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
  absent: 'n/r',
} as const;
