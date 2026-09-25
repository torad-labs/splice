// Every word this feature prints (docs/design/DESIGN.md section 10). S holds labels, three words or
// fewer in sentence case; H holds help, one sentence of twelve words or fewer.
export const S = {
  title: 'Alerts',
  about: 'About alerts',
  webhook: 'Webhook URL',
  test: 'Send test',
  save: 'Save',
  saved: 'Saved',
  sent: 'Sent',
  unavailable: 'Alerts unavailable',
} as const;

export const H = {
  about: 'Splice posts here when a head passes a warn budget.',
  unavailable: 'This splice version does not serve alerts.',
  /** Why the test key waits: the daemon tests the saved webhook, never the typed one. */
  saveFirst: 'Save a webhook to send a test.',
} as const;
