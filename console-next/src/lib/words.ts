// The words the derivation modules in this directory print. Copy lives in modules like this one, never inline
// in logic, so the copy wall reads one place.
export const W = {
  /** A window whose reset passed since splice read it: one word on every surface. */
  notReread: 'Reset, not re-read',
  /** Printed when the pool excludes an account and the daemon sent no reason of its own. */
  excluded: 'Excluded by the pool, with no reason given.',
} as const;
