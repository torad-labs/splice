// Labels of the turns page. Three words or fewer, lowercase, no em-dash (the label
// wall globs this file). The pending empties, the "telemetry dropped" gap and the
// capture sentences are not labels and live in the component (CONTRACTS.md
// section 4).
export const S = {
  title: 'turns',
  locked: 'console locked',
  sample: 'sample data',
  inflight: 'in flight',
  landed: 'landed',
  summary: 'summary',
  detail: 'turn detail',
  close: 'close',
  /** The strip fields, in the order the views declare them. */
  time: 'time',
  head: 'head',
  model: 'model',
  outcome: 'outcome',
  session: 'session',
  compact: 'compact',
  phase: 'phase',
  age: 'age',
  idle: 'idle',
  account: 'account',
  total: 'total',
  firstByte: 'first byte',
  tokensIn: 'in',
  cached: 'cached',
  cacheWrite: 'cache write',
  tokensOut: 'out',
  retries: 'retries',
  attempts: 'attempts',
  inflightCount: 'inflight',
  dropped: 'dropped',
  /** The summary bay. */
  failureShare: 'failure share',
  cacheHit: 'cache hit',
  /** The summary edge's state for a window the rollup has no rows for. Two words, because the
   *  filter sentence it replaced was four and a holder edge has a character budget, not a word
   *  count (m1 design review B10). The sentence itself still reaches a reader through the
   *  strip's aria-label. */
  noRows: 'empty',
  /** The summary edge's state for a window the rollup has rows for. */
  hasRows: 'rows',
  peakInflight: 'peak inflight',
  ioDrops: 'io drops',
  rows: 'rows',
  coverage: 'coverage',
  refreshes: 'refreshes',
  /** What any cell with no value prints: the approved comp's own glyph (m1 design review B8,
   *  which found thirteen phrasings across the console for one fact). Two characters carry the
   *  whole statement — the basis word that used to sit beside it was a second sentence saying
   *  the same thing, and printed as "- unavailable" it read as a typo. A cell that has a value
   *  but a qualified one still prints its basis: `estimated` and `stale` qualify something. */
  absent: 'n/r',
} as const;
