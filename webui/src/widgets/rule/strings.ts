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
  },
  nearest: 'nearest window',
  noneTail: 'heads report none',
} as const;
