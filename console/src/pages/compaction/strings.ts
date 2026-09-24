// Every label this page prints. Lowercase, three words or fewer, no em-dash (CONTRACTS.md
// section 4, enforced by the label wall). The law line is a sentence and lives in the component.
export const S = {
  title: 'compaction',
  /** The reveal's label for the page's one explanation. */
  law: 'which model compacts',
  sample: 'sample data',
  /** The rules bay (GET /api/compaction/instructions). */
  instructions: 'compaction rules',
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
