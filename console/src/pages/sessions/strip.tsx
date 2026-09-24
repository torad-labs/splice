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

// Measured against the widest thing each column can carry. These were measured when an absent
// cell printed "- unavailable" (13 characters), and every field that can be absent is now three
// characters shorter, so no column is narrower than what it prints: StripField clips rather than
// wraps, and the width that fits the old empty state fits the new one.
//
// ONE declaration per column - label and width together - so a row cannot print a name that
// disagrees with its own width. The comp's strip carries its field names IN the strip (its two
// strips in one bay carry different sets), so the rows print them and no bay head does.
// ---- THE BUDGET STAYS AND THE SHARES MOVE (M2-31, the diagnostic M2-29 brought) --------------
// Declared width over content held, per column, measured rack-wide at 1536 dark with every cell
// cloned unconstrained so the number is what the column ACTUALLY holds rather than what it was
// once sized for. Healthy reads 1.0 to 1.5. This rack read, before:
//
//   name 1.95 · head 1.51 · project 2.84 · started 2.08 · seen 2.13 · PEER 4.63
//
// 578px of content inside a rack declaring 1287px. `peer` at 4.63 holds `n/r` in 190px; `project`
// at 2.84 holds `repo v0.4.0` in 274px; and `head`, which holds the longest string on the page
// after `name`, was the most starved of the six.
//
// THE TOTAL IS UNCHANGED AT 122ch, which is not a nicety -- it is the whole constraint. A strip is
// `width: max-content` and each cell carries `flexGrow: w`, so rendered width is proportional to
// declared ch and the rack has no slack to redistribute; fitting every column to its content does
// not tighten the rack, it ends the rack early and leaves bare ground to the bay edge (M2-29
// measured 453px of it on fleet, past tsc, forty tests and a zero-clipped check). So each column
// now takes the share of the SAME budget that its content actually needs.
//
// Widths went to the two columns holding real names and away from the four holding tokens and
// absences. No phrase was shortened and no absence renamed to buy the pixels.
//
// The default view (by head) stopped printing `head`, which its bay label already names, so its
// 29ch went back into the same 122: to `name` and `project`, the two real names, and to `peer`,
// whose label is now `last hand-off` (13 characters) and which holds a session name or id.
// The views that still print `head` keep its 29ch (console review, 2026-09-24).
const COLUMNS: Record<string, { label: string; w: number }> = {
  name: { label: S.name, w: 42 },
  head: { label: S.head, w: 29 },
  project: { label: S.project, w: 30 },
  started: { label: S.started, w: 17 },
  seen: { label: S.seen, w: 17 },
  peer: { label: S.peer, w: 16 },
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
      {fieldsOf(row, peer, order).map((field) => (
        <StripField key={field.key} w={field.w} label={field.label} value={field.value} {...basisProp(field.basis)} />
      ))}
    </Strip>
  );
}
