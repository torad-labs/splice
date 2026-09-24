// Every label this widget prints. Lowercase, three words or fewer, no em-dash (CONTRACTS.md
// section 4, enforced by the label wall).
export const S = {
  provider: 'provider',
  account: 'account',
  plan: 'plan',
  none: 'no plan',
  heads: 'heads',
  noHeads: 'no heads',
  next: 'next',
  resets: 'resets',
  window: 'window',
  /** The two window tracks' names when the slot holds no window: the daemon's slot lengths. */
  shortWindow: '5h',
  longWindow: '7d',
  excluded: 'excluded',
  singleLogin: 'single login',
} as const;
