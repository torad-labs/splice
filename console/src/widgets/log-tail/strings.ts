// Labels of the log tail. Three words or fewer, lowercase, no em-dash (the label
// wall globs this file). The path, the empty tail's reason and the pending empties
// are not labels and live in the component (CONTRACTS.md section 4).
export const S = {
  title: 'log tail',
  head: 'head',
  tail: 'tail',
  search: 'search',
  follow: 'follow',
  paused: 'paused',
  time: 'time',
  text: 'message',
  path: 'file',
  all: 'all',
  /** The count of lines that arrived while the reader was not following. */
  newLines: 'new lines',
} as const;
