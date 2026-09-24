// Every label this page prints. Lowercase, three words or fewer, no em-dash (CONTRACTS.md
// section 4, enforced by the label wall). Sentences — the honest empties, the wrap side effects,
// the validator messages — are not labels and live in the components.
export const S = {
  title: 'settings',
  knobs: 'runtime knobs',
  find: 'find a knob',
  findHint: 'name or key',
  topology: 'topology',
  /** A value written at the top of splice.toml, outside any table. */
  topLevel: 'top level',
  claudeHead: 'claude head',
  mode: 'head mode',
  separate: 'separate',
  wrapped: 'wrapped',
  wrap: 'wrap',
  unwrapLabel: 'unwrap',
  confirmUnwrap: 'confirm unwrap',
  confirmWrap: 'confirm wrap',
  rawToml: 'raw topology',
  showDiff: 'show diff',
  write: 'write topology',
  discard: 'discard changes',
  reload: 'reload',
  count: 'fields',
  changed: 'changed fields',
  allKnobs: 'all knobs',
  live: 'applies live',
  restart: 'restart to apply',
  sample: 'sample data',
  onPath: 'claude on path',
  shim: 'shim',
  realBinary: 'real binary',
  logins: 'stored logins',
  selected: 'selected login',
  backups: 'backup files',
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
