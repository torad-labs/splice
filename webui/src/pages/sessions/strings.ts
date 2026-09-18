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
  received: 'received',
  /** The one word a field prints when the daemon does not report the value is a
   *  plain hyphen, which is not a label and lives in strip.tsx. */
  idle: 'idle',
  undated: 'not dated',
} as const;
