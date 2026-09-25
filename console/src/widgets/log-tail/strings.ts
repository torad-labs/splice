// Every word the log tail prints (docs/design/DESIGN.md section 10). S holds labels: three words or
// fewer, sentence case. H holds help: one sentence of twelve words or fewer. U holds the unit words
// printed beside a figure. tests/copy.test.ts holds all three.
export const S = {
  title: 'Log tail',
  head: 'Head',
  tail: 'Tail',
  follow: 'Follow',
  paused: 'Paused',
  time: 'Time',
  text: 'Message',
  all: 'All',
  /** The empties, one factual line each. */
  reading: 'Reading the log',
  noLines: 'No lines',
  /** A perf line's parts: the disclosure that shows the daemon's own lines for the turn, and each
   *  drawn cell's name for a screen reader. */
  rawLine: 'Raw lines',
  timing: 'Turn timing',
  cacheHit: 'Cache hit',
  tokensIn: 'Tokens in',
  tokensOut: 'Tokens out',
  /** A turn the daemon ran to compact the conversation. */
  compact: 'Compaction',
  /** The legend's three greys, in the order a turn meets them. */
  legend: 'Turn timing key',
  work: 'Splice work',
  waiting: 'Waiting',
  streaming: 'Streaming',
} as const;

export const H = {
  reading: "The last lines of this head's log show here.",
  noLines: 'The log is empty, or no line matches the filters.',
} as const;

export const U = {
  /** After the count of lines that arrived while the reader was not following. */
  newLines: 'new lines',
  cached: 'cached',
  in: 'in',
  out: 'out',
} as const;
