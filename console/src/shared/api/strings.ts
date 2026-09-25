// Copy of the daemon client. The copy gate reads this file: `H` holds a sentence a page prints
// whole (twelve words or fewer, one sentence).
export const H = {
  /** What a read says when the daemon did not answer at all, in place of the browser's own words
   *  for a refused connection. */
  notAnswering: 'Splice is not answering.',
} as const;
