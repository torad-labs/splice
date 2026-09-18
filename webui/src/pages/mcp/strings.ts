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
  state: 'state',
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
  none: 'none',
  notRunning: 'not running',
} as const;
