// Copy of the world's controls. The copy gate reads this file: `S` holds labels (three words or
// fewer, sentence case), `U` the fragments spoken beside a figure. A caller's own button text is the
// caller's copy; only the words these controls say by themselves live here.
export const S = {
  /** What an armed destructive key prints second. */
  confirm: 'Confirm',
  /** What a cocked key's holder edge prints: the edge is the state, and a state needs a word. */
  armed: 'Armed',
  cancel: 'Cancel',
  /** What a busy key prints beside its own label: a working key is visible, not merely disabled. */
  busy: 'Working',
  /** The retry key a Fault may carry. */
  retry: 'Retry',
  /** What a fault prints before the age of the rows a failed read left on screen. */
  lastRead: 'Last read',
  /** What a Choice's box says while its rack is shut, and while it is open. */
  open: 'Open',
  close: 'Close',
  /** What a chosen option's holder edge prints: the mark is a line, and a line needs a word. */
  chosen: 'Chosen',
  /** The copy key's three words: its default label, what it prints once the value is on the
   *  clipboard, and what it prints when there is no clipboard to put it on (plain http). */
  copy: 'Copy',
  copied: 'Copied',
  copyByHand: 'Copy by hand',
} as const;

export const U = {
  /** The basis a fault's held rows are spoken with. */
  stale: 'stale',
} as const;

// THE ABSENCE VOCABULARY, written down where the next person writing a cell will see it (M1-66).
// These are DIFFERENT FACTS and collapsing them destroys information; adding a word without one of
// these meanings is how the console reached eleven phrasings for "nothing here".
//   –          (en dash, ABSENT in @shared/lib) nobody reported a value for this cell. The
//              default. It was `n/r`, which no reader could expand.
//   none       the question was asked and its answer is nothing (no tier hands this model out).
//   unknown    we asked and were NOT TOLD - a different fact from none, and never a zero.
//   unavailable  it exists and we cannot reach it.
//   ineligible   it does not apply here.
//   not built    it does not exist yet; a pending route names its row.
// A site whose fact cannot be told from the code KEEPS the word it has and gets a note beside it.
// Renaming an absence you have not understood is how `unknown` silently becomes `none`.
