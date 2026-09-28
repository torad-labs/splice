// Every word the activity feed prints (docs/design/DESIGN.md section 10). S holds labels: three words
// or fewer, sentence case. H holds help: one sentence of twelve words or fewer. U holds the unit
// words printed beside a figure. tests/copy.test.ts holds all three.
export const S = {
  activity: 'Activity',
  activityWhy: 'About activity',
  /** The empties, one factual line each. */
  reading: 'Reading activity',
  unavailable: 'Activity unavailable',
  unreadable: 'Activity unreadable',
  nothingSampled: 'Nothing sampled today',
  deleted: 'Activity history deleted',
  notMatching: 'Client not matching',
} as const;

export const H = {
  activity: 'What each session was doing, sampled about every 30 seconds.',
  unavailable: 'This splice version does not serve team activity.',
  nothingSampled: "Splice samples this team's sessions while they work.",
  deleted: 'Stored activity labels were removed; new ones can be sampled.',
  notMatching: "This session's client is gone, so no new sample will come.",
} as const;

export const U = {
  /** After the count of samples older than the ones printed. */
  older: 'older',
} as const;
