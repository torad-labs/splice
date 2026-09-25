// Every word the logs page prints (docs/design/DESIGN.md section 10). S holds labels: three words or
// fewer, sentence case. H holds help: one sentence of twelve words or fewer. tests/copy.test.ts holds
// both.
export const S = {
  title: 'Logs',
  locked: 'Console locked',
  unreadable: 'Log unreadable',
  sample: 'Sample data',
  /** The tail-size choice: how many of the log's last lines to read. */
  tail: 'Lines',
  drawer: 'Request capture',
  /** The tail restarted because the daemon rotated its log file. */
  rotated: 'Log rotated',
  head: 'Head',
  /** The tag and level filters. */
  tag: 'Tag',
  level: 'Level',
  all: 'All',
  search: 'Search',
  /** The rail of filters beside the stream. */
  filters: 'Log filters',
} as const;

export const H = {
  locked: 'The management key unlocks this page.',
} as const;
