// Labels of the fixed rule. Three words or fewer, lowercase, no em-dash (the
// label wall globs this file). The honest empty for a window no head reports
// is not a label and lives in the component (CONTRACTS.md section 4).
export const S = {
  wordmark: 'splice',
  local: 'local',
  utc: 'utc',
  /** The daemon's own three words for its health, printed beside the edge. */
  health: {
    green: 'daemon ok',
    amber: 'daemon degraded',
    red: 'daemon unreachable',
    grey: 'key required',
  },
  nearest: 'nearest window',
  /** Saved knobs the running daemon has not read yet (a restart-only knob was patched). */
  restartPending: 'restart pending',
  /** The live connection, printed beside health: the word follows the holder edge's state. */
  live: 'live',
  reconnecting: 'reconnecting',
  off: 'off',
  noneTail: 'heads report none',
} as const;
