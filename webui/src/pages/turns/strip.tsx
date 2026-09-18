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
  basis: Basis;
}

/** Measured against the widest thing each column prints, which for a column that can be absent is
 *  the value plus its basis word, not the value alone (found by capture in M2-02). */
// Each width is the widest thing that column prints plus room for the cell's own padding, measured
// off a capture: a value of N characters needs about N + 3 ch, and for a column that can be absent
// the widest thing is "- unavailable", not the value. Too narrow and the ellipsis hides the basis
// that makes an empty honest (found three times on this tree before it was measured).
const WIDTHS: Record<string, number> = {
  time: 12, head: 20, model: 18, outcome: 16, session: 20, compact: 9, phase: 12, age: 10, idle: 10,
  account: 14, total: 15, firstByte: 14, tokensIn: 13, cached: 13, cacheWrite: 15, tokensOut: 13,
  retries: 9, attempts: 10, inflight: 11, dropped: 22,
};

const pad = (value: number): string => String(value).padStart(2, '0');

/** HH:MM:SS of a turn, or null when the row carries no timestamp. */
export function atText(ts: number | undefined): string | null {
  if (typeof ts !== 'number') return null;
  const at = new Date(ts);
  return `${pad(at.getHours())}:${pad(at.getMinutes())}:${pad(at.getSeconds())}`;
}

/** A number the row does not carry prints `-` with the basis that says why: never a zero the
 *  daemon did not report. */
function measured(value: number | undefined, format: (n: number) => string): { value: string; basis: Basis } {
  return value === undefined ? { value: S.absent, basis: 'unavailable' } : { value: format(value), basis: 'measured' };
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
  const values: Record<string, { label: string; value: string; basis: Basis }> = {
    time: at === null ? { label: S.time, value: S.absent, basis: 'unavailable' } : { label: S.time, value: at, basis: 'measured' },
    head: { label: S.head, value: row.head, basis: 'measured' },
    model: { label: S.model, value: row.model, basis: 'measured' },
    outcome: { label: S.outcome, value: row.outcome, basis: 'measured' },
    session: row.session === undefined ? { label: S.session, value: S.absent, basis: 'unavailable' } : { label: S.session, value: row.session, basis: 'measured' },
    compact: { label: S.compact, value: row.compact ? 'yes' : 'no', basis: 'measured' },
    account: row.account === undefined ? { label: S.account, value: S.absent, basis: 'unavailable' } : { label: S.account, value: row.account, basis: 'measured' },
    total: { label: S.total, ...measured(row.total, fmtMs) },
    firstByte: { label: S.firstByte, ...measured(row.first_byte, fmtMs) },
    tokensIn: { label: S.tokensIn, ...measured(row.in_tokens, fmtTokens) },
    cached: { label: S.cached, ...measured(row.cached_tokens, fmtTokens) },
    cacheWrite: { label: S.cacheWrite, ...measured(row.cache_write_tokens, fmtTokens) },
    tokensOut: { label: S.tokensOut, ...measured(row.out_tokens, fmtTokens) },
    retries: { label: S.retries, ...measured(row.retries, String) },
    attempts: { label: S.attempts, ...measured(row.attempts, String) },
    inflight: { label: S.inflightCount, ...measured(row.inflight, String) },
    dropped: dropped
      ? { label: S.dropped, value: 'telemetry dropped', basis: 'measured' }
      : { label: S.dropped, value: S.absent, basis: 'measured' },
  };
  const fields: Field[] = [];
  for (const key of order) {
    const found = values[key];
    if (found === undefined) continue;
    fields.push({ key, label: found.label, w: WIDTHS[key] ?? 12, value: found.value, basis: found.basis });
  }
  return fields;
}

/** An in-flight turn has no outcome yet: the gate knows its phase and how long it has been there,
 *  so those are the only numbers it can honestly print. */
export function inflightFieldsOf(turn: InflightTurn, order: readonly string[]): Field[] {
  const values: Record<string, { label: string; value: string; basis: Basis }> = {
    session: { label: S.session, value: turn.label, basis: 'measured' },
    head: { label: S.head, value: turn.head, basis: 'measured' },
    phase: { label: S.phase, value: turn.phase, basis: 'measured' },
    age: { label: S.age, value: fmtMs(turn.ageMs), basis: 'measured' },
    idle: { label: S.idle, value: timeAgo(Date.now() - turn.idleMs), basis: 'measured' },
    compact: { label: S.compact, value: turn.compact ? 'yes' : 'no', basis: 'measured' },
  };
  const fields: Field[] = [];
  for (const key of order) {
    const found = values[key];
    if (found === undefined) continue;
    fields.push({ key, label: found.label, w: WIDTHS[key] ?? 12, value: found.value, basis: found.basis });
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
      edgeLabel={row.outcome}
      selected={selected}
      onOpen={onOpen}
      ariaLabel={`${S.title} ${row.head} ${row.model}`}
    >
      {fieldsOf(row, order).map((field) => (
        <StripField key={field.key} w={field.w} label={field.label} value={field.value} basis={field.basis} />
      ))}
    </Strip>
  );
}

export function InflightStrip({ turn, order }: { turn: InflightTurn; order: readonly string[] }) {
  const state = edgeOfInflight(turn);
  return (
    <Strip
      edge={state}
      edgeLabel={state === 'amber' ? 'stalled' : 'running'}
      cocked={state === 'amber'}
      ariaLabel={`${S.inflight} ${turn.label}`}
    >
      {inflightFieldsOf(turn, order).map((field) => (
        <StripField key={field.key} w={field.w} label={field.label} value={field.value} basis={field.basis} />
      ))}
    </Strip>
  );
}
