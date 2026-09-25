// Labels the shell prints itself: the sidebar's group names. Three words or fewer, lowercase, no
// em-dash (the label wall globs this file).
export const S = {
  /** The session in flight: sessions, turns, teams, projects. */
  inFlight: 'in flight',
  /** The head a turn goes to: fleet, models, compaction. */
  routing: 'routing',
  /** The account window a turn spends: accounts, usage. */
  plans: 'plans',
  /** The daemon under all of it: settings, mcp, logs, doctor. */
  daemon: 'daemon',
} as const;
