// Every word the capture read prints (V4-239). S holds labels: three words or fewer, sentence case.
// H holds help: one sentence of twelve words or fewer. U holds the unit words printed beside a
// figure. tests/copy.test.ts holds all three.
export const S = {
  /** The two keys: `splice trace` and `splice wire`, each read when pressed. */
  readTrace: 'Read trace',
  readWire: 'Upstream bodies',
  trace: 'Trace',
  wire: 'Wire tap',
  files: 'Files',
  turns: 'Turns',
  turn: 'Turn',
  time: 'Time',
  session: 'Session',
  model: 'Model',
  outcome: 'Outcome',
  rounds: 'Rounds',
  attempts: 'Attempts',
  total: 'Total',
  /** A turn with no record closing it yet. */
  open: 'Open',
  openTurn: 'Open turn',
  /** Files on disk while capture is off: kept from when it was on. */
  recordedEarlier: 'Recorded earlier',
  aboutRecorded: 'About these files',
  aboutWire: 'About the tap',
  noTraced: 'No turns traced',
  skipped: 'Skipped lines',
  attempt: 'Attempt',
  round: 'Round',
  transport: 'Transport',
  status: 'Status',
  duration: 'Duration',
  url: 'URL',
  failure: 'Failure',
  request: 'Request',
  response: 'Response',
  clientRequest: 'Client request',
  answer: 'Answer',
  truncated: 'Truncated',
  compact: 'Compaction',
  tapOff: 'Tap off',
  kept: 'Kept',
  noBodies: 'No bodies yet',
} as const;

export const H = {
  recordedEarlier: 'Recorded while capture was on; capture is off now.',
  noTraced: 'Turn capture on and restart; traced turns land here.',
  wire: 'The last request bodies this head sent upstream, kept in memory.',
  noBodies: 'Nothing has left this head since the daemon started.',
} as const;

export const U = {
  of: 'of',
  chars: 'chars',
} as const;
