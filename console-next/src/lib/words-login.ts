// Every word the sign-in flow prints. S: labels, three words or fewer, sentence case. H: help and the
// line an action reads back, one sentence of twelve words or fewer.
export const S = {
  add: 'Add account',
  renew: 'Sign in again',
  label: 'Label',
  start: 'Start login',
  cancel: 'Cancel',
  copy: 'Copy',
  copied: 'Copied',
  code: 'Code',
  link: 'Link',
  openSignIn: 'Open sign-in page',
  openVerification: 'Open verification page',
  opening: 'Opening sign-in',
  switch: 'Switch',
  /** Drops a manual switch's pin, so the selector's own order picks again. */
  unpin: 'Unpin',
  relabel: 'Relabel',
  remove: 'Remove',
  refresh: 'Refresh',
  signInUnavailable: 'Sign-in unavailable',
} as const;

export const H = {
  signInUnavailable: 'This splice version cannot sign in here; run splice login <head>.',
  device: 'Finish signing in in the browser with this code.',
  browser: 'Finish signing in at this link.',
  opening: 'Opening sign-in with your provider; return to splice when finished.',
  waiting: 'Starting the sign-in.',
  afterRestart: 'Signed in; the plan restarts to use this account.',
  added: 'Account added.',
  renewed: (label: string): string => `${label} renewed; old usage set aside, read again next request.`,
  renewedExisting: (label: string): string => `${label} signed in again.`,
  failed: 'Login failed; try again, or run splice login <head>.',
  switched: 'Takes effect on the next turn; a running turn keeps its account.',
  unpinned: 'The usual order picks again from the next turn.',
  refreshed: 'Login refreshed.',
  renamed: 'Account renamed.',
  removed: 'Account removed.',
  unsupported: 'This splice version cannot do that.',
  refused: 'Splice refused it.',
} as const;
