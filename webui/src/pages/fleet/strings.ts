// Every label this page prints. Lowercase, three words or fewer, no em-dash (CONTRACTS.md
// section 4, enforced by the label wall).
//
// Sentences that are not labels live in the component or in model.ts: the honest empties and the
// field-status words are statements, not chrome, and section 4 exempts them.
export const S = {
  title: 'fleet',
  bay: 'heads',
  byHead: 'by head',
  byProvider: 'by provider',
  attentionFirst: 'attention first',
  detail: 'head detail',
  lifecycle: 'lifecycle',
  knobs: 'knobs',
  pool: 'account pool',
  start: 'start',
  stop: 'stop',
  restart: 'restart',
  daemon: 'daemon',
  noOverrides: 'no overrides',
  note: 'note',
  version: 'version',
  /** What any cell with no value prints — the approved comp's own glyph (m1 design review B8).
   *  It replaces the bare `not built` this page used to print in a strip; the pending routes are
   *  named where they belong, in its honest empties (EMPTIES.fields, EMPTIES.pool). */
  absent: 'n/r',
} as const;
