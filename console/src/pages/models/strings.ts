// Every word this page prints. S: labels, three words or fewer, sentence case. H: help, one
// sentence of twelve words or fewer, shown on hover or focus. U: a unit beside a figure.
export const S = {
  title: 'Models',
  sample: 'Sample data',
  byHead: 'By head',
  byProvider: 'By provider',
  heads: 'Heads',
  head: 'Head',
  models: 'Models',
  model: 'Model',
  tiersFilled: 'Tiers filled',
  widestWindow: 'Widest window',
  /** How many models carry a rate card. */
  priced: 'Priced',
  tiers: 'Tiers',
  tier: 'Tier',
  contextWindow: 'Context window',
  windowFrom: 'Window from',
  input: 'Input',
  output: 'Output',
  cacheRead: 'Cache read',
  cacheWrite: 'Cache write',
  pinned: 'Pinned',
  unresolved: 'Unresolved',
  reason: 'Reason',
  description: 'Description',
  headWindow: 'Head window',
  defaultWindow: 'Default window',
  extraWindows: 'Extra windows',
  windowRules: 'Window rules',
  aboutRates: 'About prices',
  aboutTiers: 'About tiers',
  detail: 'Model detail',
  openModel: 'Open model',
  close: 'Close',
  /** The header key and the panel it opens: `splice add-model` (V4-220). */
  addModels: 'Add models',
  noModels: 'No models',
  noHeads: 'No heads',
  catalogPending: 'Catalog unavailable',
  providerUnknown: 'Not reported',
  /** The Claude Code tiers, as the client names them. */
  tierName: {
    opus: 'Opus',
    sonnet: 'Sonnet',
    haiku: 'Haiku',
    fable: 'Fable',
  },
  /** Where a context window came from. The daemon sends a label it never expects the console to
   *  parse (ModelsRoute.kt WINDOW_FROM_*); a label missing here prints in the entity's words. */
  windowSource: {
    model: 'Model catalog',
    head: 'Head setting',
    rule: 'Prefix rule',
    'extra-window': 'Extra window',
    default: 'Provider default',
    unknown: 'Unknown',
  },
} as const;

export const H = {
  rates: 'Prices are USD per million tokens.',
  tiers: 'A grey tier has no model; set its slot in splice.toml.',
  pending: 'This splice version does not serve the model catalog.',
  noHeads: 'Heads declared in splice.toml appear here with their models.',
} as const;

export const U = {
  usd: '$',
  of: 'of',
} as const;
