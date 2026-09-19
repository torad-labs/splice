// Labels of the sessions page. Three words or fewer, lowercase, no em-dash (the
// label wall globs this file). The pending empties, the daemon's own sentence
// about headless runs, and the data values a strip prints are not labels and
// live in the component (CONTRACTS.md section 4).
export const S = {
  title: 'sessions',
  locked: 'console locked',
  headless: 'headless runs',
  registry: 'session registry',
  sample: 'sample data',
  detail: 'session detail',
  close: 'close',
  openHead: 'open head',
  openProject: 'open project',
  openTeam: 'open team',
  conversation: 'conversation',
  files: 'files',
  handoffs: 'hand-offs',
  /** The strip fields, in the order the views declare them. */
  name: 'name',
  head: 'head',
  project: 'project',
  team: 'team',
  started: 'started',
  seen: 'seen',
  peer: 'peer',
  address: 'address',
  at: 'at',
  sent: 'sent',
  /** `recv` and not `received`: the holder edge prints this word, and the contract budgets a
   *  holder edge at 6 characters (CONTRACTS.md section 2). */
  received: 'recv',
  /** What any cell with no value prints. The approved comp's own glyph (m1 design review B8):
   *  the hyphen it replaces was printed with the basis word `unavailable` beside it, which is
   *  one fact in two sentences. `n/r` is not a label and lives in strip.tsx with the cells. */
  absent: 'n/r',
  idle: 'idle',
  undated: 'not dated',
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
