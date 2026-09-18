// Every label this widget prints. Lowercase, three words or fewer, no em-dash (CONTRACTS.md
// section 4, enforced by the label wall).
export const S = {
  provider: 'provider',
  head: 'head',
  port: 'port',
  dialect: 'dialect',
  model: 'model',
  account: 'account',
  inflight: 'in flight',
  window: 'window',
  turn: 'last turn',
  /* THE VOCABULARY, in the file a cell author edits (M1-69). Each word is a DIFFERENT FACT:
       notBuilt  `not built`  - it does not exist yet; the row that will build it is named elsewhere.
       none      `none`       - the question was asked and its answer is nothing.
     A cell with a value nobody reported prints the glyph (see the tables under pages/); a site whose
     fact cannot be told from the code keeps the word it has and gets a note, never a guess. */
  notBuilt: 'not built',
  none: 'none',
} as const;
