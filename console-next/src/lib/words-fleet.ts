// What the plan-card derivation says: each state's one line and its note. Copy lives in modules like this one, never inline in logic.
export const FL = {
  local: 'Runs on this computer',
  stopped: 'This plan is not running.',
  unhealthy: 'Its health check is failing.',
  signedOut: 'Its next turn will fail until you sign in.',
  loginExpired: 'Its login could not be refreshed, so its next turn will fail.',
  accountExcluded: 'The pool has set its selected account aside.',
  queueFull: 'Turns are waiting for a free slot.',
  restartNeeded: 'The configuration changed after this plan started.',
} as const;
