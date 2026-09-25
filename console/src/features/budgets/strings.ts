// Every word this feature prints (docs/design/DESIGN.md section 10). S holds labels, three words or
// fewer in sentence case; H holds help, one sentence of twelve words or fewer.
export const S = {
  title: 'Budgets',
  about: 'About budgets',
  head: 'Head',
  daily: 'Daily limit',
  action: 'Past limit',
  save: 'Save',
  saved: 'Saved',
  warn: 'Warn',
  block: 'Block',
  /** An empty box: the head has no budget, which is a state and never $0.00. */
  noLimit: 'No limit',
  unavailable: 'Budgets unavailable',
} as const;

export const H = {
  about: 'A dollar limit per head for each UTC day.',
  unavailable: 'This splice version does not serve budgets.',
  notAmount: 'Not a dollar amount; nothing saved.',
} as const;
