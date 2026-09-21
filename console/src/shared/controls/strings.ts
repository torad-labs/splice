// Labels of the world's controls. Three words or fewer, lowercase, no em-dash (the label wall
// globs this file). A caller's own button text is the caller's copy; only the words these controls
// say by themselves live here.
export const S = {
  /** What an armed destructive key prints second. */
  confirm: 'confirm',
  /** What a cocked key's holder edge prints: the edge is the state, and a state needs a word. */
  armed: 'armed',
  cancel: 'cancel',
  /** What a busy key prints beside its own label: a working key is visible, not merely disabled. */
  busy: 'working',
  /** The retry key a Fault may carry. */
  retry: 'retry',
  /** The holder edge's printed word on a fault strip. */
  fault: 'fault',
  /** The single field a fault strip carries: the daemon's own message. */
  message: 'message',
  /** What a Choice's box says while its rack is shut, and while it is open. */
  open: 'open',
  close: 'close',
  /** What a chosen option's holder edge prints: the mark is a line, and a line needs a word. */
  chosen: 'chosen',
} as const;

// THE ABSENCE VOCABULARY, written down where the next person writing a cell will see it (M1-66).
// These are DIFFERENT FACTS and collapsing them destroys information; adding a word without one of
// these meanings is how the console reached eleven phrasings for "nothing here".
//   n/r        nobody reported a value for this cell. The default, and the comp's own glyph.
//   none       the question was asked and its answer is nothing (no tier hands this model out).
//   unknown    we asked and were NOT TOLD - a different fact from none, and never a zero.
//   unavailable  it exists and we cannot reach it.
//   ineligible   it does not apply here.
//   not built    it does not exist yet; a pending route names its row.
// A site whose fact cannot be told from the code KEEPS the word it has and gets a note beside it.
// Renaming an absence you have not understood is how `unknown` silently becomes `none`.
