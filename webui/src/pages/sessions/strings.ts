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
