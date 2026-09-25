// Copy of the account entity. The copy gate reads this file: `H` holds a sentence a page prints
// whole (twelve words or fewer, one sentence).
export const H = {
  /** Printed when the pool excludes an account and the daemon sent no reason of its own. */
  excluded: 'Excluded by the pool, with no reason given.',
} as const;
