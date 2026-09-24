// Every label this feature prints. Lowercase, three words or fewer, no em-dash (CONTRACTS.md
// section 4, enforced by the label wall).
export const S = {
  add: 'add account',
  label: 'label',
  start: 'start login',
  cancel: 'cancel',
  copy: 'copy',
  code: 'code',
  link: 'link',
  switch: 'switch',
  /** Drops a manual switch's pin, so the selector's own order picks again. */
  unpin: 'unpin',
  relabel: 'relabel',
  remove: 'remove',
  refresh: 'refresh',
  actions: 'actions',
  account: 'account',
  head: 'head',
  provider: 'provider',
  note: 'note',
  notReported: 'not reported',
  noAccount: 'no account',
} as const;
