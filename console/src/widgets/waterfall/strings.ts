// Every word the turn waterfall and the request drawer print (docs/design/DESIGN.md section 10).
// S holds labels: three words or fewer, sentence case. H holds help: one sentence of twelve words or
// fewer. U holds the unit words printed beside a figure. tests/copy.test.ts holds all three.
export const S = {
  phases: 'Turn timing',
  /** A turn whose row carries no mark at all. */
  noMarks: 'No stage marks',
  /** The counters beside the bar (FEATURES.md 4.3). */
  counters: 'Counters',
  retries: 'Retries',
  refreshes: 'Refreshes',
  backoff: 'Backoff',
  postSend: 'Post-send retries',
  requestBytes: 'Request size',
  upstreamBytes: 'Upstream size',
  frames: 'Frames out',
  tools: 'Tools loaded',
  searchRounds: 'Search rounds',
  dropped: 'Dropped writes',
  /** The capture switch's name: its printed word is only the state. */
  capture: 'Body capture',
  on: 'On',
  off: 'Off',
  retention: 'Retention',
  bodyCap: 'Body cap',
  /** What runs where it differs from the switch, as badges. */
  captureOn: 'Recording bodies',
  atRestart: 'Restart to apply',
  /** Where captured bodies are read: the daemon's CLI, since no route serves one. */
  readWith: 'Read bodies',
  about: 'About capture',
} as const;

export const H = {
  noMarks: 'The daemon wrote no timing for this turn.',
  capture: 'Records request and response bodies on local disk, off by default.',
} as const;

export const U = {
  days: 'd',
  chars: 'chars',
} as const;
