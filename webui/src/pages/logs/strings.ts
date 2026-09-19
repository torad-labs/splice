// Labels of the logs page. Three words or fewer, lowercase, no em-dash (the label
// wall globs this file). The pending empties, the empty tail's reason and the
// capture-off sentence are not labels and live in the component (CONTRACTS.md
// section 4).
export const S = {
  title: 'logs',
  locked: 'console locked',
  sample: 'sample data',
  tail: 'tail',
  drawer: 'request drawer',
  openDrawer: 'capture',
  /** The tag and level filters. */
  tag: 'tag',
  level: 'level',
  all: 'all',
} as const;
