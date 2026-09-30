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

/** How a session sits in the hand-offs: it sent work to several sessions (`lead`), it was handed work by
 *  one (`from`), or neither. A lead is a session whose newest edges go to two or more distinct peers; a
 *  session that both led and was handed work reads as what it did last. */
export type Handoff = { kind: 'lead'; peers: number } | { kind: 'from'; peer: string } | null;

export function handoffOf(rows: readonly SessionRow[], edges: readonly SessionEdge[]): Handoff {
  if (edges.length === 0) return null;
  const newest = edges.reduce((a, b) => (b.at > a.at ? b : a));
  if (newest.direction === 'in') return { kind: 'from', peer: peerLabel(rows, newest) };
  const peers = new Set(edges.filter((edge) => edge.direction === 'out').map((edge) => edge.to));
  return peers.size >= 2 ? { kind: 'lead', peers: peers.size } : { kind: 'from', peer: peerLabel(rows, newest) };
}

/** How long a session has been in its state, in ms, or null when the registry gave no time. Waiting and
 *  stuck count from the last status change; working from when it started; idle from the last update. */
export function sinceOf(row: SessionRow, state: SessionState, now: number): number | null {
  const from = state === 'working' ? (row.started_at ?? row.status_updated_at) : (row.status_updated_at ?? row.updated_at);
  return from === null ? null : Math.max(0, now - from);
}

/** Why a session has no conversation to open or resume, or null when it has one or the daemon did not say.
 *  A durable row names the source that found it; a live registry row carries only `resumable`, and false
 *  there means no head's tree holds a transcript with conversation, which is not the same as an empty file. */
export type NoConversation = 'no-transcript' | 'nothing-to-resume' | 'empty-transcript';

export function noConversation(row: SessionRow): NoConversation | null {
  if (row.source === 'history-only' || row.source === 'registry-only') return 'no-transcript';
  if (row.resumable !== false) return null;
  return row.source === undefined ? 'nothing-to-resume' : 'empty-transcript';
}

export type SessionTone = 'work' | 'wait' | 'stuck' | 'idle';

const STATE_WORD: Readonly<Record<SessionState, string>> = {
  working: 'Working', waiting: 'Waiting on you', stuck: 'Stuck', idle: 'Idle', gone: 'Ended',
};
export const stateWord = (state: SessionState): string => STATE_WORD[state];

const STATE_TONE: Readonly<Record<SessionState, SessionTone>> = {
  working: 'work', waiting: 'wait', stuck: 'stuck', idle: 'idle', gone: 'idle',
};
export const stateTone = (state: SessionState): SessionTone => STATE_TONE[state];

/** A span as a person says it: minutes under an hour, hours under two days, then days. */
export function spanText(ms: number): string {
  const minutes = Math.floor(ms / 60_000);
  if (minutes < 1) return 'under a minute';
  if (minutes < 60) return `${minutes} min`;
  const hours = Math.floor(minutes / 60);
  return hours < 48 ? `${hours} h` : `${Math.floor(hours / 24)} d`;
}

/** The one activity line of a card. The daemon has no last-message field yet (V4-444 daemon work), so the
 *  line says what the state means and for how long, and nothing it cannot know. */
export function activityText(state: SessionState, since: number | null): string {
  const span = since === null ? null : spanText(since);
  switch (state) {
    case 'waiting':
      return span === null ? 'Waiting for your answer' : `Waiting for your answer for ${span}`;
    case 'stuck':
      return span === null ? 'Quiet for a while' : `Quiet for ${span}`;
    case 'working':
      return span === null ? 'Working' : `Working for ${span}`;
    case 'idle':
      return span === null ? 'Waiting for your next message' : `Idle for ${span}, waiting for your next message`;
    case 'gone':
      return 'The session ended';
  }
}

/** Whether a session answers to what was typed in the search: its name, repository, head or branch-free
 *  working folder, case-insensitively. An empty search matches all. */
export function matchesQuery(row: SessionRow, query: string): boolean {
  const needle = query.trim().toLowerCase();
  if (needle === '') return true;
  return [sessionLabel(row), repoName(row) ?? '', row.head, row.team ?? ''].some((field) => field.toLowerCase().includes(needle));
}

const NUMBER_WORDS = ['none', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten', 'eleven', 'twelve'] as const;
const numberWord = (n: number): string => NUMBER_WORDS[n] ?? String(n);
const capital = (text: string): string => text.charAt(0).toUpperCase() + text.slice(1);

/** The page's one-sentence summary: what is working, what needs a person, what finished. A registry with no
 *  live session says so. Counts are words up to twelve, digits after. */
export function sessionsLede(rows: readonly SessionRow[], now: number): string {
  const count = { working: 0, waiting: 0, stuck: 0, idle: 0, gone: 0 };
  for (const row of rows) count[stateOf(row, now)] += 1;
  const parts: string[] = [];
  if (count.working > 0) parts.push(`${numberWord(count.working)} ${count.working === 1 ? 'is' : 'are'} working`);
  if (count.waiting > 0) parts.push(`${numberWord(count.waiting)} ${count.waiting === 1 ? 'is' : 'are'} waiting on you`);
  if (count.stuck > 0) parts.push(`${numberWord(count.stuck)} ${count.stuck === 1 ? 'is' : 'are'} stuck`);
  const finished = count.idle + count.gone;
  const first = parts.length === 0 ? '' : `${capital(parts.join(', '))}.`;
  const last = finished === 0 ? '' : `${capital(numberWord(finished))} finished earlier.`;
  return [first, last].filter((sentence) => sentence !== '').join(' ');
}
