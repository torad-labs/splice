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
  /** The opened project's own row (GET /api/projects/{id}). */
  activity: 'activity',
  liveSessions: 'live sessions',
  turnsToday: 'turns today',
  costToday: 'cost today',
  dayStart: 'day start',
  /** The holder edge's state: something is running in this repo right now, or nothing is. The
   *  edge used to print the noun `repo`, which is the first field's own label and no state at
   *  all (m1 design review B10). */
  running: 'busy',
  quiet: 'quiet',
  /** What any cell with no value prints — the approved comp's own glyph (m1 design review B8),
   *  replacing `no rates` in the cost cell and `unknown` in the last-seen cell. */
  absent: 'n/r',
} as const;

// THE ABSENCE VOCABULARY, written down where the next person writing a cell will see it (M1-66).
// These are DIFFERENT FACTS and collapsing them destroys information; adding a word without one of
// these meanings is how the console reached eleven phrasings for "nothing here".
//   n/r        nobody reported a value for this cell. The default, and the comp's own glyph.
//   none       the question was asked and its answer is nothing (no tier hands this model out).
//   unknown    we asked and were NOT TOLD - a different fact from none, and never a zero.
//   unavailable  it exists and we cannot reach it.
//   ineligible   it does not apply here.
//   not built    it does not exist yet; a pending route names its row.
// A site whose fact cannot be told from the code KEEPS the word it has and gets a note beside it.
// Renaming an absence you have not understood is how `unknown` silently becomes `none`.
