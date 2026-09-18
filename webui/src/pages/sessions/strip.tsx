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
  basis: Basis;
}

/**
 * What a data cell prints when the daemon does not report the value: a plain
 * hyphen, which is the world's own rule for an empty cell ("a plain hyphen or
 * an explicit empty-state string, never an em-dash placeholder"), with the
 * basis printed beside it saying WHY it is empty. A word here would be a second
 * sentence per cell: the basis already is the sentence.
 */
const ABSENT = '-';

// Measured against the widest thing each column can carry, which for every
// field that can be absent is "- unavailable" (13 characters), not the value
// alone: StripField clips rather than wraps, so a column narrower than its own
// basis would hide the word that makes the empty honest.
const WIDTHS: Record<string, number> = { name: 26, head: 20, project: 26, started: 16, seen: 16, peer: 18 };

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
 * report is `unknown` and says WHY through its basis, never a blank and never a
 * zero.
 */
export function fieldsOf(row: SessionRow, peer: string | null, order: readonly string[]): Field[] {
  const started = startedText(row);
  const project = projectText(row);
  const values: Record<string, { label: string; value: string; basis: Basis }> = {
    name: { label: S.name, value: sessionLabel(row), basis: 'measured' },
    head: { label: S.head, value: row.head === '' ? UNKNOWN_HEAD : row.head, basis: 'measured' },
    project: project === null
      ? { label: S.project, value: ABSENT, basis: 'unavailable' }
      : { label: S.project, value: project, basis: 'measured' },
    started: started === null
      ? { label: S.started, value: ABSENT, basis: 'unavailable' }
      : { label: S.started, value: started, basis: 'measured' },
    seen: row.updated_at === null
      ? { label: S.seen, value: ABSENT, basis: 'unavailable' }
      : { label: S.seen, value: timeAgo(row.updated_at), basis: 'measured' },
    peer: peer === null
      ? { label: S.peer, value: ABSENT, basis: 'unavailable' }
      : { label: S.peer, value: peer, basis: 'measured' },
  };
  const fields: Field[] = [];
  for (const key of order) {
    const found = values[key];
    if (found === undefined) continue;
    fields.push({ key, label: found.label, w: WIDTHS[key] ?? 14, value: found.value, basis: found.basis });
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
      {fieldsOf(row, peer, order).map((field) => (
        <StripField key={field.key} w={field.w} label={field.label} value={field.value} basis={field.basis} />
      ))}
    </Strip>
  );
}
