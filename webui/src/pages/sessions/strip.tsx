// One session as a printed strip. It is its own module rather than a function
// inside the page because this is the part of the page a reader can be lied to
// about: the availability word, the basis of every field that is not measured,
// and the peer. Rendered from props, it is directly testable.
import { Strip, StripField } from '@shared/ui';
import type { Basis, Edge } from '@shared/ui';
import { timeAgo } from '@shared/lib';
import { sessionLabel, UNKNOWN_HEAD } from '@entities/session';
import type { SessionRow } from '@entities/session';
import { S } from './strings';

export interface Field {
  key: string;
  label: string;
  w: number;
  value: string;
  /** Absent for a cell that carries no value: `n/r` is the whole statement, and the basis word
   *  that used to sit beside it said the same thing twice (m1 design review B8). */
  basis?: Basis | undefined;
}

/**
 * What a data cell prints when the daemon does not report the value: the approved comp's own
 * glyph, which is the world's rule for an empty cell made shorter — one statement, not a hyphen
 * plus a basis word saying the same thing.
 */
const ABSENT = S.absent;

// Measured against the widest thing each column can carry. These were measured when an absent
// cell printed "- unavailable" (13 characters), and every field that can be absent is now three
// characters shorter, so no column is narrower than what it prints: StripField clips rather than
// wraps, and the width that fits the old empty state fits the new one.
//
// ONE declaration per column, because the rack now prints its names once on the bay head and the
// strips below carry values only (CONTRACTS.md section 2, m1 design review B9). The head and the
// rows read the same numbers, so a name cannot drift off the column it names.
const COLUMNS: Record<string, { label: string; w: number }> = {
  name: { label: S.name, w: 26 },
  head: { label: S.head, w: 20 },
  project: { label: S.project, w: 26 },
  started: { label: S.started, w: 16 },
  seen: { label: S.seen, w: 16 },
  peer: { label: S.peer, w: 18 },
};

/** The view's columns in its own order, for the bay head. */
export function columnsOf(order: readonly string[]): { key: string; label: string; w: number }[] {
  return order.flatMap((key) => (COLUMNS[key] === undefined ? [] : [{ key, ...COLUMNS[key] }]));
}

/** An absent cell must not pass an explicit `basis: undefined` — shared/ui runs
 *  `exactOptionalPropertyTypes`, where `{ basis: undefined }` is not `{}` (the same rule the
 *  controls follow with `busy?: boolean | undefined`). */
function basisProp(basis: Basis | undefined): { basis?: Basis } {
  return basis === undefined ? {} : { basis };
}

const pad = (value: number): string => String(value).padStart(2, '0');

/** HH:MM:SS of a session's start, or null when the client wrote no timestamp. */
export function startedText(row: SessionRow): string | null {
  if (row.started_at === null) return null;
  const at = new Date(row.started_at);
  return `${pad(at.getHours())}:${pad(at.getMinutes())}:${pad(at.getSeconds())}`;
}

/** The last segment of a path, which is how a repo is named on a strip. */
export function baseOf(path: string): string {
  const parts = path.split('/').filter((part) => part !== '');
  return parts.length > 0 ? parts[parts.length - 1] : path;
}

/** The repo a session belongs to: the resolved root, else its cwd. Null when neither is known. */
export function projectKeyOf(row: SessionRow): string | null {
  return row.repo?.root ?? row.cwd ?? null;
}

/** The project as a strip prints it, with the worktree as a second word when it has one. */
export function projectText(row: SessionRow): string | null {
  const key = projectKeyOf(row);
  if (key === null) return null;
  const worktree = row.repo?.worktree;
  return worktree === undefined ? baseOf(key) : `${baseOf(key)} ${baseOf(worktree)}`;
}

/**
 * The strip's fields, in the view's own order. A value the daemon does not
 * report is `unknown` and prints the absence glyph, never a blank and never a
 * zero.
 */
export function fieldsOf(row: SessionRow, peer: string | null, order: readonly string[]): Field[] {
  const started = startedText(row);
  const project = projectText(row);
  const values: Record<string, { value: string; basis?: Basis }> = {
    name: { value: sessionLabel(row), basis: 'measured' },
    head: { value: row.head === '' ? UNKNOWN_HEAD : row.head, basis: 'measured' },
    project: project === null ? { value: ABSENT } : { value: project, basis: 'measured' },
    started: started === null ? { value: ABSENT } : { value: started, basis: 'measured' },
    seen: row.updated_at === null ? { value: ABSENT } : { value: timeAgo(row.updated_at), basis: 'measured' },
    peer: peer === null ? { value: ABSENT } : { value: peer, basis: 'measured' },
  };
  const fields: Field[] = [];
  for (const key of order) {
    const found = values[key];
    const column = COLUMNS[key];
    if (found === undefined || column === undefined) continue;
    fields.push({ key, label: column.label, w: column.w, value: found.value, ...basisProp(found.basis) });
  }
  return fields;
}

/** The holder edge's state. Grey for gone, never struck: striking is the verdict on a disabled or
 *  excluded row, and a gone session is still readable (its transcript and its perf rows stay). */
export function edgeOf(row: SessionRow): Edge {
  if (row.availability === 'live') return 'green';
  return row.availability === 'stale' ? 'amber' : 'grey';
}

export function SessionStrip({ row, peer, selected, order, onOpen }: {
  row: SessionRow;
  peer: string | null;
  selected: boolean;
  order: readonly string[];
  onOpen: () => void;
}) {
  return (
    <Strip
      edge={edgeOf(row)}
      edgeLabel={row.availability}
      cocked={row.availability === 'stale'}
      selected={selected}
      onOpen={onOpen}
      ariaLabel={`${S.title} ${sessionLabel(row)}`}
    >
      {/* No label on a cell: the bay head prints the column names once for the whole rack
          (CONTRACTS.md section 2, m1 design review B9). */}
      {fieldsOf(row, peer, order).map((field) => (
        <StripField key={field.key} w={field.w} value={field.value} {...basisProp(field.basis)} />
      ))}
    </Strip>
  );
}
