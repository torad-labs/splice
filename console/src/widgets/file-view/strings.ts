// Every word the file view prints (docs/design/DESIGN.md section 10). S holds labels: three words or
// fewer, sentence case. H holds help: one sentence of twelve words or fewer. tests/copy.test.ts holds
// both.
export const S = {
  instructions: 'Instructions',
  memory: 'Memory',
  /** The head of a repo's own file, which belongs to no head's config dir. */
  repo: 'Repo',
  contents: 'Show contents',
  files: 'Files',
  /** The empties, one factual line each. */
  noFiles: 'No files',
  memoryOff: 'Client memory off',
} as const;

export const H = {
  /** Printed before the directories the reader looked in, when it found no file. */
  lookedIn: 'Looked in:',
  noDirectory: 'The daemon reported no directory it looked in.',
} as const;
