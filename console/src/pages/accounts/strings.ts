// Every label this page prints. Lowercase, three words or fewer, no em-dash (CONTRACTS.md
// section 4, enforced by the label wall).
//
// Sentences that are not labels live in the component: the honest empties ("not reported by
// provider") and the selector-order line are statements, not chrome, and section 4 exempts them.
export const S = {
  title: 'accounts',
  bay: 'accounts',
  claudeBay: 'claude logins',
  byProvider: 'by provider',
  nearest: 'nearest exhaustion',
  byHead: 'by head',
  noAccounts: 'no accounts',
  noHeads: 'no heads',
  sample: 'sample data',
  detail: 'account detail',
  provider: 'provider',
  heads: 'heads',
  next: 'next',
  /** Leads the selector's order, printed once above the provider racks. */
  order: 'selector order',
  /** Closes the opened detail; printed only where the detail is a full-screen swell (a phone). */
  close: 'close',
} as const;
