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
  /* THE VOCABULARY, in the file a cell author edits (M1-69):
       none      `none`       - the question was asked and its answer is nothing.
     (`not built` went with the last cell that printed it, M4-06: every route a strip reads is served.)
     A cell with a value nobody reported prints the glyph (see the tables under pages/); a site whose
     fact cannot be told from the code keeps the word it has and gets a note, never a guess. */
  none: 'none',
} as const;
