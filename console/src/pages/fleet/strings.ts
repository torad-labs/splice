// Every label this page prints. Lowercase, three words or fewer, no em-dash (CONTRACTS.md
// section 4, enforced by the label wall).
//
// Sentences that are not labels live in the component or in model.ts: the honest empties and the
// field-status words are statements, not chrome, and section 4 exempts them.
import { ABSENT } from '@shared/lib';

export const S = {
  title: 'fleet',
  bay: 'heads',
  byHead: 'by head',
  byProvider: 'by provider',
  attentionFirst: 'attention first',
  detail: 'head detail',
  lifecycle: 'lifecycle',
  /** The opened head's own values, the ones it sets over the global settings. */
  knobs: 'own settings',
  pool: 'account pool',
  /** The pool's rack of account strips, under the section title above. */
  accounts: 'accounts',
  /** Printed before the account the daemon's selector takes next, and the rule that explains it. */
  nextTarget: 'next target',
  start: 'start',
  stop: 'stop',
  restart: 'restart',
  daemon: 'daemon',
  noOverrides: 'uses global values',
  note: 'note',
  version: 'version',
  /** What any cell with no value prints — the approved comp's own glyph (m1 design review B8).
   *  It replaces the bare `not built` this page used to print in a strip; the pending routes are
   *  named where they belong, in its honest empties (EMPTIES.fields, EMPTIES.pool). */
  absent: ABSENT,
  /** Closes the opened detail; printed only where the detail is a full-screen swell (a phone). */
  close: 'close',
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
