// Every label this page prints. Lowercase, three words or fewer, no em-dash (CONTRACTS.md
// section 4, enforced by the label wall).
//
// Sentences that are not labels live in the component or in model.ts: the honest empties are
// statements, not chrome, and section 4 exempts them.
export const S = {
  title: 'mcp',
  bay: 'servers',
  byName: 'by name',
  hostedFirst: 'hosted first',
  detail: 'server detail',
  name: 'server',
  pid: 'pid',
  sessions: 'sessions',
  streams: 'streams',
  started: 'started',
  activity: 'activity',
  restarts: 'restarts',
  error: 'error',
  reason: 'reason',
  limits: 'host limits',
  restart: 'restart',
  /** What any cell with no value prints — the approved comp's own glyph (m1 design review B8).
   *  It replaces two phrasings this page used for one fact: `not running` in the rack and `none`
   *  in the detail. */
  absent: 'n/r',
} as const;
