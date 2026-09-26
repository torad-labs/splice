// Copy of the status strip (docs/design/DESIGN.md section 6). The copy gate reads this file: `S`
// holds labels (three words or fewer, sentence case) and `U` the fragments printed beside a figure.
export const S = {
  local: 'Local',
  utc: 'UTC',
  /** The daemon's health: the word printed when it is not fine, and the tip in every state. */
  health: {
    green: 'Daemon ok',
    amber: 'Daemon degraded',
    red: 'Daemon unreachable',
    grey: 'Key required',
  },
  /** Printed beside the check glyph while the daemon is fine. */
  daemon: 'Daemon',
  /** The plan limit closest to running out, across every head that reports one. */
  limit: 'Closest plan limit',
  /** Printed only after the usage and accounts reads both answered and neither reported a window. */
  noLimit: 'No plan limits',
  /** Before those reads answer: nothing is known yet, which is not the same as nothing found. */
  readingLimits: 'Reading limits',
  /** A read that failed: whether a limit exists is unknown. */
  limitsUnread: 'Limits unread',
  /** Saved knobs the running daemon has not read yet (a restart-only knob was patched). */
  restartPending: 'Restart pending',
  /** The live connection. */
  live: 'Live',
  reconnecting: 'Reconnecting',
  off: 'Offline',
  /** The link has gone past its heartbeat with nothing heard. */
  silent: 'Link silent',
  lastEvent: 'Last event',
  noEvents: 'No events yet',
  /** The braid: every running head's turns in flight. */
  braid: 'Turns in flight',
} as const;

export const U = {
  inFlight: 'in flight',
  resets: 'resets',
  /** Beside a count of heads with no plan limit (a pay-per-token key has none). */
  withoutLimit: 'without limits',
} as const;
