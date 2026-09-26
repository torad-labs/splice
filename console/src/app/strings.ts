// Copy the shell prints itself: the sidebar's group names and every page's name. The copy gate
// reads this file: every value is a label (three words or fewer, sentence case).
export const S = {
  /** What needs the operator, and the session in flight: needs you, sessions, turns, teams, projects. */
  inFlight: 'In flight',
  /** The head a turn goes to: fleet, models, compaction. */
  routing: 'Routing',
  /** The account window a turn spends: accounts, usage. */
  plans: 'Plans',
  /** The daemon under all of it: settings, mcp, logs, doctor. */
  daemon: 'Daemon',
} as const;

/** Every page's name, as the sidebar and the palette print it. The address stays the slug. */
export const PAGE = {
  'needs-you': 'Needs you',
  sessions: 'Sessions',
  turns: 'Turns',
  teams: 'Teams',
  projects: 'Projects',
  fleet: 'Fleet',
  models: 'Models',
  compaction: 'Compaction',
  accounts: 'Accounts',
  usage: 'Usage',
  settings: 'Settings',
  mcp: 'MCP',
  logs: 'Logs',
  doctor: 'Doctor',
} as const;
