// What the plan-card derivation says: each state's one line and its note. Copy lives in modules like this one, never inline in logic.
export const FL = {
  stopped: 'This command is not running.',
  unhealthy: 'Its health check is failing.',
  signedOut: 'Its next turn will fail until you sign in.',
  loginExpired: 'Its login could not be refreshed, so its next turn will fail.',
  accountExcluded: 'The pool has set its selected account aside.',
  queueFull: 'Turns are waiting for a free slot.',
  restartNeeded: 'The configuration changed after this command started.',
  payPerToken: 'Pays per token; no window',
  noReading: 'No reading yet',
  lastReading: (age: string, pct: number, window: string): string => `Last reading ${age}: ${pct}% of ${window}, which has reset since`,
  plans: (label: string, groups: string): string => `${label}${groups === '' ? '' : `: ${groups}`}.`,
  onePlan: 'One plan',
  manyPlans: 'plans',
  drag: 'Drag a card to put it where you want it; Sessions follows.',
  standing: { ready: 'ready', near: 'near its limit', quota: 'out of quota', 'signed-out': 'signed out', off: 'switched off', other: 'in need of a look' },
} as const;
