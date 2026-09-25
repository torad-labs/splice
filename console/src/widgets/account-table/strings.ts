// Every word this widget prints. S: labels, three words or fewer, sentence case. U: a unit beside a
// figure.
export const S = {
  account: 'Account',
  provider: 'Provider',
  plan: 'Plan',
  state: 'State',
  /** The daemon's two window slots: up to six hours, and longer (AccountPool QuotaSlots). */
  short: '5h',
  long: '7d',
  heads: 'Heads',
  next: 'Next',
  singleLogin: 'Single login',
  primary: 'Primary',
  pinned: 'Pinned',
  stale: 'Stale',
  windowsRead: 'Windows read',
  reason: 'Reason',
  /** An account's state in words: the badge beside the figures that decide it. */
  stateName: {
    ok: 'OK',
    warn: 'Near limit',
    spent: 'Spent',
    excluded: 'Excluded',
    unknown: 'Unknown',
  },
  /** Why the daemon takes an account next, in the order its selector walks (AccountPool.kt:163). */
  ruleName: {
    pinned: 'Pinned',
    primary: 'Primary',
    'last used': 'Last used',
    'most weekly room': 'Most weekly room',
  },
} as const;

export const U = {
  used: '%',
} as const;
