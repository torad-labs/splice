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
  /** The holder edge's state: something is running in this repo right now, or nothing is. The
   *  edge used to print the noun `repo`, which is the first field's own label and no state at
   *  all (m1 design review B10). */
  running: 'busy',
  quiet: 'quiet',
  /** What any cell with no value prints — the approved comp's own glyph (m1 design review B8),
   *  replacing `no rates` in the cost cell and `unknown` in the last-seen cell. */
  absent: 'n/r',
} as const;
