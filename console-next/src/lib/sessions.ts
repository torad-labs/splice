// Pure derivations over the session registry: a session's key and label, what state it is in, and how a
// list of them groups. No rendering, no store; the clock is passed in.
import type { SessionEdge, SessionRow } from '../types/sessions';
import { UNKNOWN_HEAD } from '../types/sessions';

/** A session's key: its session id, else its pid. A registration with no session id still has to be
 *  openable, and its key must not collide with "nothing is open". */
export function sessionKey(row: SessionRow): string {
  return row.session_id ?? `pid:${row.pid ?? 0}`;
}

/** A session's own label: its name, else the first 8 of its session id, else its pid. */
export function sessionLabel(row: SessionRow): string {
  if (row.name !== null && row.name !== '') return row.name;
  if (row.session_id !== null && row.session_id !== '') return row.session_id.slice(0, 8);
  return row.pid === null ? 'unknown' : `pid ${row.pid}`;
}

const lastSegment = (path: string): string => path.replace(/\/+$/, '').split('/').pop() ?? path;

/** A repository as a person names it: the folder, never the path. A row with neither a resolved repo nor a
 *  working directory has none. */
export function repoName(row: SessionRow): string | null {
  const root = row.repo?.root ?? row.cwd;
  return root === null || root === undefined || root === '' ? null : lastSegment(root);
}

/** Where a session stands, in the words the board groups by. `waiting` and `stuck` are the two that need a
 *  person: the client's own `waiting` status, and a session that is busy but has not moved for
 *  STUCK_AFTER_MS (or that the daemon calls stale). `gone` is a registration whose process exited. */
export type SessionState = 'waiting' | 'stuck' | 'working' | 'idle' | 'gone';

/** Busy with no status change for this long is stuck: the console's rule, the daemon has no such field. */
export const STUCK_AFTER_MS = 10 * 60_000;

export function stateOf(row: SessionRow, now: number): SessionState {
  if (row.availability === 'gone') return 'gone';
  if (row.availability === 'stale') return 'stuck';
  const status = row.status ?? '';
  if (status === 'waiting') return 'waiting';
  if (status === 'busy' || status === 'shell') {
    const since = row.status_updated_at ?? row.updated_at;
    return since !== null && now - since > STUCK_AFTER_MS ? 'stuck' : 'working';
  }
  return 'idle';
}

export const needsPerson = (state: SessionState): boolean => state === 'waiting' || state === 'stuck';

export type GroupBy = 'state' | 'repo' | 'head' | 'team';

/** What a session with no value for the grouping field is filed under, so no row is dropped from a count. */
export const UNATTRIBUTED = 'unattributed';

export function groupKeyOf(row: SessionRow, by: GroupBy, now: number): string {
  switch (by) {
    case 'state': {
      const state = stateOf(row, now);
      return needsPerson(state) ? 'needs' : state;
    }
    case 'head':
      return row.head === '' ? UNKNOWN_HEAD : row.head;
    case 'repo':
      return repoName(row) ?? UNATTRIBUTED;
    case 'team':
      return row.team !== undefined && row.team !== null && row.team !== '' ? row.team : UNATTRIBUTED;
  }
}

export interface SessionGroup {
  key: string;
  sessions: SessionRow[];
}

const STATE_ORDER = ['needs', 'working', 'idle', 'gone'];

/** The rows filed by [by]. State groups keep their fixed order; the others put the biggest group first,
 *  ties broken by key so the order is stable. */
export function groupSessions(rows: readonly SessionRow[], by: GroupBy, now: number): SessionGroup[] {
  const groups = new Map<string, SessionGroup>();
  for (const row of rows) {
    const key = groupKeyOf(row, by, now);
    const group = groups.get(key) ?? { key, sessions: [] };
    group.sessions.push(row);
    groups.set(key, group);
  }
  const all = [...groups.values()];
  return by === 'state'
    ? all.sort((a, b) => STATE_ORDER.indexOf(a.key) - STATE_ORDER.indexOf(b.key))
    : all.sort((a, b) => b.sessions.length - a.sessions.length || a.key.localeCompare(b.key));
}

/** The operator's order: rows sort by the position of their key in [order]; a key it has never seen keeps
 *  its place after the known ones, in the order given. */
export function inOrder(rows: readonly SessionRow[], order: readonly string[]): SessionRow[] {
  const at = new Map(order.map((key, index) => [key, index] as const));
  const rank = (row: SessionRow): number => at.get(sessionKey(row)) ?? order.length;
  return rows.map((row, index) => ({ row, index })).sort((a, b) => rank(a.row) - rank(b.row) || a.index - b.index).map((entry) => entry.row);
}

/** [order] with [key] moved to sit where [over] sits. Keys the order did not hold yet are added first, in the
 *  sequence [all] gives, so a first drag fixes the whole order. */
export function moveKey(order: readonly string[], all: readonly string[], key: string, over: string): string[] {
  const full = [...order, ...all.filter((candidate) => !order.includes(candidate))];
  const from = full.indexOf(key);
  const to = full.indexOf(over);
  if (from === -1 || to === -1 || from === to) return full;
  const next = full.filter((candidate) => candidate !== key);
  next.splice(to, 0, key);
  return next;
}

/** The address a session mints for other sessions to message, and the name behind one. */
export function nameForAddress(rows: readonly SessionRow[], address: string): string | null {
  const owner = rows.find((row) => row.address === address);
  return owner === undefined ? null : sessionLabel(owner);
}

/** The session on the other end of one edge: a sent edge's `to` is an address (or name) the call used; a
 *  received edge's `from` is the sender's session id, never an address. */
export function peerLabel(rows: readonly SessionRow[], edge: SessionEdge): string {
  if (edge.direction === 'out') return nameForAddress(rows, edge.to) ?? edge.to;
  const sender = rows.find((row) => row.session_id === edge.from);
  return sender === undefined ? edge.from.slice(0, 8) : sessionLabel(sender);
}
