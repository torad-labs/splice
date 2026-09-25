// Copy of the account entity. The copy gate reads this file: `S` holds a label (three words or
// fewer, sentence case), `H` a sentence a page prints whole (twelve words or fewer, one sentence).
export const S = {
  /** A window whose reset passed since splice read it (isStale): one word on every surface. */
  notReread: 'Reset, not re-read',
} as const;

export const H = {
  /** Printed when the pool excludes an account and the daemon sent no reason of its own. */
  excluded: 'Excluded by the pool, with no reason given.',
} as const;
