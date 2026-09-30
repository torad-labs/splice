// The words the derivation modules in this directory print. Copy lives in modules like this one, never inline
// in logic, so the copy wall reads one place.
export const W = {
  /** A window whose reset passed since splice read it: one word on every surface. */
  notReread: 'Reset, not re-read',
  shortWindow: 'short window',
  longWindow: 'long window',
  /** Printed when the pool excludes an account and the daemon sent no reason of its own. */
  excluded: 'Excluded by the pool, with no reason given.',
  /** What a read says when the daemon did not answer at all. */
  notAnswering: 'splice is not answering.',
  retry: 'Try again',
  searchKey: '⌘K',
} as const;
