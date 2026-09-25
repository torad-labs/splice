// Every word this page prints. S: labels, three words or fewer, sentence case. H: help, one
// sentence of twelve words or fewer, shown on hover or focus. U: a unit beside a figure. An
// account row's own words (its columns, states and rules) are widgets/account-table's.
export const S = {
  title: 'Accounts',
  about: 'About accounts',
  sample: 'Sample data',
  byProvider: 'By provider',
  nearest: 'Nearest limit',
  byHead: 'By head',
  accounts: 'Accounts',
  account: 'Account',
  state: 'State',
  heads: 'Heads',
  head: 'Head',
  aboutNext: 'About next',
  nearestLimit: 'Nearest limit',
  nextReset: 'Next reset',
  excluded: 'Excluded',
  noHeads: 'No heads',
  claudeLogins: 'Claude logins',
  aboutClaude: 'About Claude logins',
  apiKeys: 'API keys',
  variable: 'Variable',
  keyFile: 'Key file',
  key: 'Key',
  keySet: 'Set',
  keyMissing: 'Missing',
  signedIn: 'Signed in',
  signedOut: 'Signed out',
  /** A client head's forwarded login before upstream answered one of its turns. */
  unverified: 'Unverified',
  note: 'Note',
  aboutKey: 'About this key',
  detail: 'Account detail',
  openAccount: 'Open account',
  openHead: 'Open head',
  close: 'Close',
  noAccounts: 'No accounts yet',
  poolsUnavailable: 'Pools unavailable',
} as const;

export const H = {
  about: 'Which login each head uses, and how near its limits are.',
  claude: 'A Claude head keeps its Claude Code login; it has no pool.',
  noAccounts: 'Open a head to sign one in, or run splice login <head>.',
  poolsUnavailable: 'This splice version does not serve pools; each head shows its login.',
  keyOne: 'This head signs every request with one API key.',
  keyFile: 'Replace the key in its file; a set variable still wins.',
  keyStore: 'No key yet; store one and the next request uses it.',
  keyReplace: 'This replaces the stored key; an exported variable still wins.',
  unverified: 'Checked when upstream answers this head\'s next turn.',
} as const;

export const U = {
  used: '%',
  /** Before the time of the upstream answer a client head's verdict rests on. */
  accepted: 'Accepted',
} as const;

/** The fix for a client head whose forwarded login upstream rejected: Claude Code's own /login, the
 *  sentence doctor gives (DoctorAuthVerdict, V4-220 6b). splice holds nothing to sign in again. */
export const clientSignIn = (head: string): string => `Run ${head}, then /login inside it.`;
