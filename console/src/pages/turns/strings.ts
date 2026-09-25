// Every word the turns page prints (docs/design/DESIGN.md section 10). S holds labels: three words or
// fewer, sentence case. H holds help: one sentence of twelve words or fewer. U holds the unit words
// printed beside a figure. tests/copy.test.ts holds all three.
import { ABSENT } from '@shared/lib';

export const S = {
  title: 'Turns',
  about: 'About turns',
  locked: 'Console locked',
  sample: 'Sample data',
  /** The views. */
  table: 'Table',
  timeline: 'Timeline',
  byModel: 'By model',
  byOutcome: 'By outcome',
  /** The sections. */
  inflight: 'In flight',
  summary: 'Last 24 hours',
  summaryWhy: 'About the summary',
  stages: 'Time per stage',
  stagesKey: 'Stage key',
  stagesWhy: 'About stages',
  tokensWhy: 'About tokens',
  /** The legend's name for the two waits, which share a grey. */
  waits: 'Waits',
  tokens: 'Tokens',
  tokensKey: 'Token key',
  landed: 'Landed',
  detail: 'Turn detail',
  timing: 'Timing',
  capture: 'Request capture',
  close: 'Close',
  undated: 'Undated',
  /** The empties, one factual line each. */
  nothingInFlight: 'Nothing in flight',
  noSummary: 'No summary yet',
  noTurns: 'No turns yet',
  historyUnavailable: 'History unavailable',
  /** The columns. */
  time: 'Time',
  head: 'Head',
  model: 'Model',
  outcome: 'Outcome',
  session: 'Session',
  slots: 'Slots in use',
  phase: 'Phase',
  age: 'Age',
  idle: 'Idle',
  turns: 'Turns',
  firstByte: 'First byte',
  turnTime: 'Turn time',
  failed: 'Failed',
  cacheHit: 'Cache hit',
  peak: 'Peak concurrent',
  retries: 'Retries',
  refreshes: 'Refreshes',
  lostRows: 'Lost log rows',
  input: 'Input',
  output: 'Output',
  /** The input split and the stage bars' names. */
  cached: 'Cached',
  written: 'Cache write',
  uncached: 'Uncached',
  /** The badges a turn wears. */
  stalled: 'Stalled',
  compaction: 'Compaction',
  dropped: 'Telemetry dropped',
  /** Printed before the heads a window holds no turns for. */
  noTurnsIn: 'No turns',
  never: 'Never',
  /** What any cell with no value prints (ABSENT in @shared/lib). */
  absent: ABSENT,
} as const;

export const H = {
  about: "What runs now, what landed, and where each turn's time went.",
  summary: 'Solid bar is the median; the pale end reaches p95.',
  nothingInFlight: 'A turn shows here while it runs.',
  noSummary: 'Waiting for the daemon to answer.',
  noTurns: 'Turns land here as the heads serve them.',
  historyUnavailable: 'This splice version does not serve turn history.',
  stalled: "Idle past its head's stream idle limit: hung, or still reasoning.",
  stages: 'The average landed turn below, in its parts, per head.',
  tokens: 'The landed turns below; the strong grey is priced in full.',
} as const;

export const U = {
  window: 'window',
  idle: 'idle hours',
  retries: 'retries',
  queued: 'queued',
  last: 'last',
} as const;
