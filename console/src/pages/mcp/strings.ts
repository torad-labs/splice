// Every word this page prints. S: labels, three words or fewer, sentence case. H: help, one
// sentence of twelve words or fewer, shown on hover or focus. U: a unit beside a figure.
export const S = {
  title: 'MCP',
  sample: 'Sample data',
  byName: 'By name',
  hostedFirst: 'Hosted first',
  servers: 'Servers',
  server: 'Server',
  state: 'State',
  hosted: 'Hosted',
  sessions: 'Sessions',
  sessionsByServer: 'Sessions by server',
  streams: 'Streams',
  restarts: 'Restarts',
  aboutRestarts: 'About restarts',
  lastCall: 'Last call',
  started: 'Started',
  pid: 'PID',
  lastError: 'Last error',
  reason: 'Reason',
  aboutDirect: 'About direct servers',
  detail: 'Server detail',
  open: 'Open server',
  close: 'Close',
  limits: 'Host limits',
  limit: 'Limit',
  value: 'Value',
  applies: 'Applies',
  live: 'Live',
  restart: 'On restart',
  notCarried: 'Not carried',
  editLimits: 'Edit in settings',
  openSettings: 'Open settings',
  hostingOff: 'Sharing is off',
  noServers: 'No MCP servers',
  /** A server's state in words. `Unused`: nothing has asked for it yet, which is not a stop.
   *  `Direct`: splice does not share it, and each client reaches it itself. */
  stateName: {
    hosted: 'Hosted',
    idle: 'Unused',
    ineligible: 'Direct',
  },
} as const;

export const H = {
  hostingOff: 'Set mcp_hosting = true under [daemon] to share servers.',
  noServers: "Stdio servers from Claude Code's MCP settings appear here.",
  restarts: 'Exited servers restart on their next call, backing off up to 60s.',
  direct: 'Clients reach a direct server themselves; splice does not share it.',
} as const;

export const U = {
  of: 'of',
} as const;
