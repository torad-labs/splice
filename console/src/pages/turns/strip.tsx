// One turn, and one in-flight turn, as printed strips. Their own module because this is the part a
// reader can be lied to about: which numbers are measured, which the daemon did not report, and
// which row lost telemetry on the way to disk. Rendered from props, they are directly testable
// (a virtualized list renders nothing without a viewport).
import { Strip, StripField } from '@shared/ui';
import type { Basis, Edge } from '@shared/ui';
import { fmtMs, fmtTokens, timeAgo } from '@shared/lib';
import type { InflightTurn, TurnRow } from '@entities/perf';
import { S } from './strings';

export interface Field {
  key: string;
  label: string;
  w: number;
  value: string;
  /** Absent for a cell that carries no value: `–` is the whole statement, and a basis word
   *  beside it was a second sentence saying the same thing (m1 design review B8). */
  basis?: Basis | undefined;
}

/** Measured against the widest thing each column prints: the value itself (found by capture in
 *  M2-02). */
// Each width is the widest thing that column prints plus room for the cell's own padding, measured
// off a capture: a value of N characters needs about N + 3 ch. Too narrow and the ellipsis hides
// the value (found three times on this tree before it was measured). The widths were measured when
// an absent cell printed `- unavailable` and are left as they were: a column narrower than its own
// empty state hides the word that makes the empty honest, and the widest of those empties is now
// three characters shorter, so every column still fits.
// ONE declaration per column - label and width together - so a row cannot print a name that
// disagrees with its own width. The comp's strip carries its field names IN the strip (its two
// strips in one bay carry different sets), so the rows print them and no bay head does.
const COLUMNS: Record<string, { label: string; w: number }> = {
  time: { label: S.time, w: 12 },
  head: { label: S.head, w: 20 },
  model: { label: S.model, w: 18 },
  outcome: { label: S.outcome, w: 16 },
  session: { label: S.session, w: 20 },
  compact: { label: S.compact, w: 9 },
  phase: { label: S.phase, w: 12 },
  age: { label: S.age, w: 10 },
  idle: { label: S.idle, w: 10 },
  account: { label: S.account, w: 14 },
  total: { label: S.total, w: 15 },
  firstByte: { label: S.firstByte, w: 14 },
  tokensIn: { label: S.tokensIn, w: 13 },
  cached: { label: S.cached, w: 13 },
  cacheWrite: { label: S.cacheWrite, w: 15 },
  tokensOut: { label: S.tokensOut, w: 13 },
  retries: { label: S.retries, w: 9 },
  attempts: { label: S.attempts, w: 10 },
  inflight: { label: S.inflightCount, w: 11 },
  dropped: { label: S.dropped, w: 22 },
};

/** An absent cell must not pass an explicit `basis: undefined` — shared/ui runs
 *  `exactOptionalPropertyTypes`, where `{ basis: undefined }` is not `{}` (the same rule the
 *  controls follow with `busy?: boolean | undefined`). */
export function basisProp(basis: Basis | undefined): { basis?: Basis } {
  return basis === undefined ? {} : { basis };
}

const pad = (value: number): string => String(value).padStart(2, '0');

/** HH:MM:SS of a turn, or null when the row carries no timestamp. */
export function atText(ts: number | undefined): string | null {
  if (typeof ts !== 'number') return null;
  const at = new Date(ts);
  return `${pad(at.getHours())}:${pad(at.getMinutes())}:${pad(at.getSeconds())}`;
}

/** A number the row does not carry prints `–`, never a zero the daemon did not report. */
function measured(value: number | undefined, format: (n: number) => string): { value: string; basis?: Basis } {
  return value === undefined ? { value: S.absent } : { value: format(value), basis: 'measured' };
}

/**
 * The turn's fields, in the view's own order (FEATURES.md 4.3's columns).
 *
 * THE ONE HONEST GAP: a row whose `async_io_drops` is above zero lost telemetry before it reached
 * disk, so its numbers are short by an unknown amount. That row prints `telemetry dropped` in its
 * own column instead of quietly showing smaller figures.
 */
export function fieldsOf(row: TurnRow, order: readonly string[]): Field[] {
  const dropped = row.async_io_drops !== undefined && row.async_io_drops > 0;
  const at = atText(row.ts);
  const values: Record<string, { value: string; basis?: Basis }> = {
    time: at === null ? { value: S.absent } : { value: at, basis: 'measured' },
    head: { value: row.head, basis: 'measured' },
    model: row.model === null ? { value: S.absent } : { value: row.model, basis: 'measured' },
    outcome: { value: row.outcome, basis: 'measured' },
    session: row.session === undefined ? { value: S.absent } : { value: row.session, basis: 'measured' },
    compact: row.compact === null ? { value: S.absent } : { value: row.compact ? 'yes' : 'no', basis: 'measured' },
    account: row.account === undefined ? { value: S.absent } : { value: row.account, basis: 'measured' },
    total: measured(row.total, fmtMs),
    firstByte: measured(row.first_byte, fmtMs),
    tokensIn: measured(row.in_tokens, fmtTokens),
    cached: measured(row.cached_tokens, fmtTokens),
    cacheWrite: measured(row.cache_write_tokens, fmtTokens),
    tokensOut: measured(row.out_tokens, fmtTokens),
    retries: measured(row.retries, String),
    attempts: measured(row.attempts, String),
    inflight: measured(row.inflight, String),
    dropped: dropped
      ? { value: 'telemetry dropped', basis: 'measured' }
      : { value: S.absent, basis: 'measured' },
  };
  const fields: Field[] = [];
  for (const key of order) {
    const found = values[key];
    const column = COLUMNS[key];
    if (found === undefined || column === undefined) continue;
    fields.push({ ...column, key, value: found.value, ...basisProp(found.basis) });
  }
  return fields;
}

/** An in-flight turn has no outcome yet: the gate knows its phase and how long it has been there,
 *  so those are the only numbers it can honestly print. */
export function inflightFieldsOf(turn: InflightTurn, order: readonly string[]): Field[] {
  const values: Record<string, { value: string; basis?: Basis }> = {
    session: { value: turn.label, basis: 'measured' },
    head: { value: turn.head, basis: 'measured' },
    phase: { value: turn.phase, basis: 'measured' },
    age: { value: fmtMs(turn.ageMs), basis: 'measured' },
    idle: { value: timeAgo(Date.now() - turn.idleMs), basis: 'measured' },
    compact: { value: turn.compact ? 'yes' : 'no', basis: 'measured' },
  };
  const fields: Field[] = [];
  for (const key of order) {
    const found = values[key];
    const column = COLUMNS[key];
    if (found === undefined || column === undefined) continue;
    fields.push({ ...column, key, value: found.value, ...basisProp(found.basis) });
  }
  return fields;
}

/**
 * An in-flight turn past its head's own idle threshold is the one that needs the operator: it is
 * the difference between a turn that is reasoning and a turn that has hung. It cocks, and the
 * printed phase is what says which.
 */
export function edgeOfInflight(turn: InflightTurn): Edge {
  return turn.idleMs > turn.streamIdleMs ? 'amber' : 'green';
}

export function TurnStrip({ row, selected, order, onOpen }: {
  row: TurnRow;
  selected: boolean;
  order: readonly string[];
  onOpen: () => void;
}) {
  return (
    <Strip
      edge={row.outcome === 'ok' ? 'green' : 'amber'}
      /* The edge carries the verdict and the outcome column carries the daemon's own tag. They
         were the same string 8px apart (m1 design review B10), and the tag is a datum that can
         outrun the contract's 6ch edge budget, where `landed` and `failed` are states that cannot
         (CONTRACTS.md section 2). */
      edgeLabel={row.outcome === 'ok' ? 'landed' : 'failed'}
      selected={selected}
      onOpen={onOpen}
      ariaLabel={`${S.title} ${row.head} ${row.model ?? S.absent}`}
    >
      {/* No label on a cell: the bay head prints the column names once for the whole rack
          (CONTRACTS.md section 2, m1 design review B9). */}
      {fieldsOf(row, order).map((field) => (
        <StripField key={field.key} w={field.w} label={field.label} value={field.value} {...basisProp(field.basis)} />
      ))}
    </Strip>
  );
}

export function InflightStrip({ turn, order }: { turn: InflightTurn; order: readonly string[] }) {
  const state = edgeOfInflight(turn);
  return (
    <Strip
      edge={state}
      edgeLabel={state === 'amber' ? 'hung' : 'live'}
      cocked={state === 'amber'}
      ariaLabel={`${S.inflight} ${turn.label}`}
    >
      {inflightFieldsOf(turn, order).map((field) => (
        <StripField key={field.key} w={field.w} label={field.label} value={field.value} {...basisProp(field.basis)} />
      ))}
    </Strip>
  );
}
