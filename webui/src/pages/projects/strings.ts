// Labels of the projects page. Three words or fewer, lowercase, no em-dash (the
// label wall globs this file). The pending empties and the data values a strip
// prints are not labels and live in the component (CONTRACTS.md section 4).
export const S = {
  title: 'projects',
  locked: 'console locked',
  sample: 'sample data',
  /** The rack's label: what the bay holds, not the page's own name. */
  repos: 'repos',
  sessionsWord: 'sessions',
  detail: 'project detail',
  close: 'close',
  files: 'project files',
  open: 'open project',
  /** The strip fields, in the order the view declares them. */
  repo: 'repo',
  sessions: 'live',
  teams: 'teams',
  turns: 'turns',
  cost: 'cost',
  last: 'last seen',
  day: 'today',
  unknown: 'unknown',
  /** What a cost cell says when the heads that ran here declare no rates. */
  noRates: 'no rates',
} as const;
