// Labels of the logs page. Three words or fewer, lowercase, no em-dash (the label
// wall globs this file). The pending empties, the empty tail's reason and the
// capture-off sentence are not labels and live in the component (CONTRACTS.md
// section 4).
export const S = {
  title: 'logs',
  locked: 'console locked',
  sample: 'sample data',
  /** The tail-size choice: how many of the log's last lines to read. */
  tail: 'lines',
  drawer: 'request capture',
  /** The tail restarted because the daemon rotated its log file. */
  rotated: 'log rotated',
  head: 'head',
  /** The tag and level filters. */
  tag: 'tag',
  level: 'level',
  all: 'all',
  search: 'search',
  /** The rail of filters beside the stream. */
  filters: 'log filters',
} as const;
