// Every label this page prints. Lowercase, three words or fewer, no em-dash (CONTRACTS.md
// section 4, enforced by the label wall).
export const S = {
  title: 'models',
  byHead: 'by head',
  byProvider: 'by provider',
  catalog: 'catalog',
  tiers: 'tiers',
  model: 'model',
  slot: 'slot',
  contextWindow: 'context window',
  windowSource: 'window source',
  rates: 'rates',
  rateInput: 'input',
  rateRead: 'cache read',
  rateWrite: 'cache write',
  rateOutput: 'output',
  pinned: 'pinned',
  /** The edge state of a model a tier hands out by itself, against one the operator pinned by
   *  hand. Both are inside the contract's 6ch edge budget (CONTRACTS.md section 2). */
  slotted: 'auto',
  noSlot: 'none',
  /** What any cell with no value prints — the approved comp's own glyph (m1 design review B8),
   *  which replaces `no rates` in every rate cell and `not declared` in a missing tier's model. */
  absent: 'n/r',
  pinnedYes: 'pinned',
  /** The edge of a tier no model fills: the tier is vacant, and the row is struck. */
  undeclared: 'vacant',
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
} as const;
