// Every word this feature prints. S: labels, three words or fewer, sentence case. H: help and the
// line an action reads back, one sentence of twelve words or fewer.
export const S = {
  add: 'Add account',
  label: 'Label',
  start: 'Start login',
  cancel: 'Cancel',
  copy: 'Copy',
  code: 'Code',
  link: 'Link',
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
  waiting: 'Starting the sign-in.',
  afterRestart: 'Signed in; the head restarts to take the account.',
  added: 'Account added.',
  failed: 'Login failed; try again, or run splice login <head>.',
  switched: 'Takes effect on the next turn; a running turn keeps its account.',
  unpinned: 'The usual order picks again from the next turn.',
  refreshed: 'Login refreshed.',
  renamed: 'Account renamed.',
  removed: 'Account removed.',
  unsupported: 'This splice version cannot do that.',
  refused: 'The daemon refused it.',
} as const;
