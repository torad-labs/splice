// One session as the board prints it. It is its own module rather than a function inside the page
// because this is the part of the page a reader can be lied to about: the availability word, the
// basis of every field that is not measured, and the peer. Pure, so it is directly testable.
import type { Basis, Tone } from '@shared/ui';
import { timeAgo } from '@shared/lib';
import { sessionLabel, UNKNOWN_HEAD } from '@entities/session';
import type { SessionRow } from '@entities/session';
import { S } from './strings';

export interface Field {
  key: string;
  label: string;
  value: string;
  /** Absent for a cell that carries no value: `–` is the whole statement, and the basis word
   *  that used to sit beside it said the same thing twice (m1 design review B8). */
  basis?: Basis | undefined;
}

/**
 * What a data cell prints when the daemon does not report the value: the approved comp's own
 * glyph, which is the world's rule for an empty cell made shorter — one statement, not a hyphen
 * plus a basis word saying the same thing.
 */
const ABSENT = S.absent;

/** The board's column names, one per field key. */
export const FIELD_LABEL: Readonly<Record<string, string>> = {
  name: S.name,
  head: S.head,
  project: S.project,
  started: S.started,
  seen: S.seen,
  peer: S.peer,
};

/** An absent cell must not pass an explicit `basis: undefined` — shared/ui runs
 *  `exactOptionalPropertyTypes`, where `{ basis: undefined }` is not `{}` (the same rule the
 *  controls follow with `busy?: boolean | undefined`). */
function basisProp(basis: Basis | undefined): { basis?: Basis } {
  return basis === undefined ? {} : { basis };
}

const pad = (value: number): string => String(value).padStart(2, '0');

/** HH:MM:SS of a session's start, or null when the client wrote no timestamp. */
/** When a session started: the time alone today, and the date before it on any other day, so a
 *  session from last week does not read as one from this morning. */
export function startedText(row: SessionRow, now: Date = new Date()): string | null {
  if (row.started_at === null) return null;
  const at = new Date(row.started_at);
  const time = `${pad(at.getHours())}:${pad(at.getMinutes())}:${pad(at.getSeconds())}`;
  if (at.toDateString() === now.toDateString()) return time;
  return `${at.toLocaleDateString('en-US', { month: 'short', day: 'numeric' }).toLowerCase()} ${time}`;
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

/** The head a session rides, or why it has none: started with `claude` directly (`not via
 *  splice`), or not readable (`no splice head`). It printed `unknown head` for both. */
export function headText(row: SessionRow): string {
  if (row.head !== '' && row.head !== UNKNOWN_HEAD) return row.head;
  return row.route === 'direct' ? S.direct : S.noHead;
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
    head: { value: headText(row), basis: 'measured' },
    project: project === null ? { value: ABSENT } : { value: project, basis: 'measured' },
    started: started === null ? { value: ABSENT } : { value: started, basis: 'measured' },
    seen: row.updated_at === null ? { value: ABSENT } : { value: timeAgo(row.updated_at), basis: 'measured' },
    peer: peer === null ? { value: ABSENT } : { value: peer, basis: 'measured' },
  };
  const fields: Field[] = [];
  for (const key of order) {
    const found = values[key];
    const label = FIELD_LABEL[key];
    if (found === undefined || label === undefined) continue;
    fields.push({ key, label, value: found.value, ...basisProp(found.basis) });
  }
  return fields;
}

/** The status a session prints last on its row, as a departure board prints its remark: live is
 *  ok, stale is warn (alive, but not heard from inside the stale window: the one that needs the
 *  operator), and gone is neutral, never struck, because a gone session is still readable (its
 *  transcript and its perf rows stay). */
export function toneOf(row: SessionRow): Tone {
  if (row.availability === 'live') return 'ok';
  return row.availability === 'stale' ? 'warn' : 'neutral';
}
