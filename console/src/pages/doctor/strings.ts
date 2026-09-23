// Every label this page prints. Lowercase, three words or fewer, no em-dash (CONTRACTS.md
// section 4, enforced by the label wall).
//
// The copy gate bans an em-dash in UI TEXT. `copy fix` and friends carry none; the daemon's own
// em-dash separator lives inside the check detail, which is data, not a label.
export const S = {
  title: 'doctor',
  /** The rack's plate. */
  checks: 'checks',
  /** What the rack's first column is: the check's own id, whole. Singular, because a column name
   *  and the rack it names are two different words in this page's own copy (M2-22). */
  check: 'check',
  /** The report's own facts, as served: the payload's field name beside its value. */
  report: 'report',
  field: 'field',
  value: 'value',
  version: 'version',
  installed: 'installed',
  latest: 'latest',
  rollback: 'rollback',
  copy: 'copy fix',
  copied: 'copied',
  fix: 'fix',
  detail: 'check detail',
  playground: 'playground',
  head: 'head',
  prompt: 'prompt',
  send: 'send',
  /** The send key while its request is out: a working key prints that it is working. */
  sending: 'sending',
  request: 'request',
  response: 'response',
  clear: 'clear',
  upgrade: 'upgrade',
  restart: 'restart',
  noFix: 'no fix offered',
  /** What any cell with no value prints — the approved comp's own glyph (m1 design review B8),
   *  replacing `unknown` in the upgrade strip's three cells. */
  absent: 'n/r',
  /** A measured nothing: the rollback read looked and found no previous release. */
  none: 'none',
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
