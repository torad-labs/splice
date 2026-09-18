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
  peakInflight: 'peak inflight',
  ioDrops: 'io drops',
  rows: 'rows',
  coverage: 'coverage',
  refreshes: 'refreshes',
  /** What a percentile the daemon could not compute prints. */
  absent: '-',
} as const;
