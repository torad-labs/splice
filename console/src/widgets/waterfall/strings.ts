// Labels of the waterfall and the request drawer. Three words or fewer, lowercase,
// no em-dash (the label wall globs this file). The capture-off sentence, the
// "telemetry dropped" gap and the pending empties are not labels and live in the
// component (CONTRACTS.md section 4).
export const S = {
  title: 'waterfall',
  phases: 'phases',
  /** The phase groups, printed as each row's name. */
  ingest: 'ingest',
  queue: 'queue',
  upstream: 'upstream',
  stream: 'stream',
  finish: 'finish',
  /** The counters beside the bar. */
  counters: 'counters',
  retries: 'retries',
  refreshes: 'refreshes',
  backoff: 'backoff',
  postSend: 'post send',
  requestBytes: 'req bytes',
  upstreamBytes: 'upstream bytes',
  frames: 'frames',
  tools: 'tools',
  searchRounds: 'search rounds',
  dropped: 'dropped',
  /** The request drawer. */
  drawer: 'request drawer',
  showCapture: 'show capture',
  /** The capture switch's name: its printed word is only the state. */
  capture: 'body capture',
  on: 'on',
  off: 'off',
  /** What the daemon records now, which a write changes only after a restart. */
  running: 'running',
  retention: 'retention',
  bodyCap: 'body cap',
} as const;
