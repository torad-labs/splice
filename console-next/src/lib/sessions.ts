// Pure derivations over the session registry: a session's key and label, what state it is in, and how a
// list of them groups. No rendering, no store; the clock is passed in.
import type { SessionEdge, SessionRow } from '../types/sessions';
import type { LiveTurn } from '../types/turns';
import { UNKNOWN_HEAD } from '../types/sessions';
import { repoNameOf } from './repo';
import { cardSays, waitingQuestion } from './session-says';
import { SW } from './words-sessions';

/** Other clients register a product/version tag; Claude Code registers its bare version. */
export const isNonClaudeClient = (version: string | null): boolean => version?.includes('/') === true;

/** A Claude Code resume recipe needs an id and no measured refusal from the transcript census. */
export const canResumeSession = (row: SessionRow): boolean =>
  row.session_id !== null && row.resumable !== false && !isNonClaudeClient(row.version);

/** A session's key: its session id, else its pid. A registration with no session id still has to be
 *  openable, and its key must not collide with "nothing is open". */
export function sessionKey(row: SessionRow): string {
  return row.session_id ?? `pid:${row.pid ?? 0}`;
}

/** A session's own label: its name, else its repo and the day it started (the daemon reports no first message to quote),
 *  else its pid. A hex id is never a name. */
export function sessionLabel(row: SessionRow): string {
  if (row.name !== null && row.name !== '') return row.name;
  const repo = repoName(row);
  const began = row.started_at;
  if (repo !== null && began !== null) return SW.inRepoOn(repo, new Date(began).toLocaleDateString([], { month: 'short', day: 'numeric' }));
  if (repo !== null) return repo;
  return row.pid === null ? SW.aSession : `pid ${row.pid}`;
}

/** A repository as a person names it: its remote's name, else its folder, never a path. A row with neither a resolved
 *  repo nor a working directory has none. */
export function repoName(row: SessionRow): string | null {
  const root = row.repo?.root ?? row.cwd;
  return root === null || root === undefined || root === '' ? null : repoNameOf(root, row.repo?.remote);
}

/** Where a session stands, in the words the board groups by. `waiting` is the client's own status; `stuck` is a busy
 *  session whose live turn has gone quiet upstream past STUCK_IDLE_MS. `gone` is a registration whose process exited. */
export type SessionState = 'waiting' | 'stuck' | 'working' | 'idle' | 'gone';

/** A turn silent this long, with no byte from upstream, is stuck.
 *  // why: splice's own watchdog asks after a silent path at STREAM_IDLE_MS / FIRST_BYTE_TIMEOUT_MS (90 s, Knob.kt:225-268)
 *  and holds a path that answers, so the proxy is already healing at 90 s; five minutes of that is what the operator
 *  experiences as a hang (the knob's own comment: "five minutes of the former is experienced as a hang"). */
export const STUCK_IDLE_MS = 5 * 60_000;

/** The live turn a session runs, when the caller has read its head's turns: undefined = not read (unknown),
 *  null = the head runs none for it. */
export type TurnOf = (row: SessionRow) => LiveTurn | null | undefined;

/** A session's state. Busy is Working unless its live turn reports itself quiet past STUCK_IDLE_MS: a busy session
 *  with no live turn is Claude Code running a tool locally (a long Bash, a monitor wait), and the registry's
 *  `status_updated_at` only moves when the client changes status, so its age proves nothing (measured 2026-09-29: six
 *  busy seats on this machine, every one working, all with an old timestamp). A daemon that does not send `idle_ms`
 *  yet leaves every busy session Working: absence claims nothing. */
export function stateOf(row: SessionRow, turn?: LiveTurn | null): SessionState {
  if (row.availability === 'gone') return 'gone';
  const status = row.status ?? '';
  if (status === 'waiting') return 'waiting';
  if (status === 'busy' || status === 'shell') {
    return turn !== undefined && turn !== null && !turn.stopped && turn.idle_ms !== undefined && turn.idle_ms > STUCK_IDLE_MS ? 'stuck' : 'working';
  }
  return 'idle';
}

export const needsPerson = (state: SessionState): boolean => state === 'waiting' || state === 'stuck';

export type GroupBy = 'state' | 'repo' | 'head' | 'team';

/** What a session with no value for the grouping field is filed under, so no row is dropped from a count. */
export const UNATTRIBUTED = 'unattributed';

export function groupKeyOf(row: SessionRow, by: GroupBy, turnOf?: TurnOf): string {
  switch (by) {
    case 'state': {
      const state = stateOf(row, turnOf?.(row));
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
export function groupSessions(rows: readonly SessionRow[], by: GroupBy, turnOf?: TurnOf): SessionGroup[] {
  const groups = new Map<string, SessionGroup>();
  for (const row of rows) {
    const key = groupKeyOf(row, by, turnOf);
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
  if (edge.direction === 'out') return nameForAddress(rows, edge.to) ?? (edge.to.startsWith('uds:') ? SW.aSession : edge.to);
  const sender = rows.find((row) => row.session_id === edge.from);
  if (sender === undefined) return SW.anEndedSession;
  const named = sender.name !== null && sender.name !== '';
  const repo = repoName(sender);
  return named || repo === null ? sessionLabel(sender) : SW.aSessionIn(repo);
}

/** The peer an edge names, or null when the registry no longer knows who it was. */
export function namedPeer(rows: readonly SessionRow[], edge: SessionEdge): string | null {
  const label = peerLabel(rows, edge);
  return label === SW.anEndedSession ? null : label;
}

/** How a session sits in the hand-offs: it sent work to several sessions (`lead`), to one (`to`), it was handed work by
 *  one (`from`), or neither. A lead is a session whose newest edges go to two or more distinct peers; a
 *  session that both sent and was handed work reads as what it did last. */
export type Handoff = { kind: 'lead'; peers: number } | { kind: 'from'; peer: string } | { kind: 'to'; peer: string } | null;

export function handoffOf(rows: readonly SessionRow[], edges: readonly SessionEdge[]): Handoff {
  if (edges.length === 0) return null;
  const newest = edges.reduce((a, b) => (b.at > a.at ? b : a));
  if (newest.direction === 'in') return { kind: 'from', peer: peerLabel(rows, newest) };
  const peers = new Set(edges.filter((edge) => edge.direction === 'out').map((edge) => edge.to));
  return peers.size >= 2 ? { kind: 'lead', peers: peers.size } : { kind: 'to', peer: peerLabel(rows, newest) };
}

/** How long a session has been in its state, in ms, or null when the registry gave no time. Every state counts from its
 *  last status change (a busy session's is when its turn began, which is what "working for" means; the session's own
 *  age would read 8 h for a seat that began a turn a minute ago), else from the last update. */
export function sinceOf(row: SessionRow, now: number): number | null {
  const from = row.status_updated_at ?? row.updated_at ?? row.started_at;
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

/** How long a busy session with no live turn may sit before its line says it is running a tool, quietly. */
export const QUIET_AFTER_MS = 2 * 60_000;

/** A card's one line, and the note that goes to its quiet line: what the session last said or did, with the state's own sentence
 *  beside the facts; the state's sentence alone when there is nothing fit to say. */
/** `agent` is whose words the line is: the session's own (its newest say or do: the terminal) or splice's about it (the state's sentence). */
export function cardLine(row: SessionRow, state: SessionState, since: number | null, quiet: number | null): { line: string; note: string | null; agent: boolean } {
  // A session that waits shows the question it asked; one that asked none says it waits. The rest show what they last said or did.
  const last = state === 'waiting' ? waitingQuestion(row.last) : cardSays(row.last);
  return last === null ? { line: activityText(state, since, quiet), note: null, agent: false } : { line: last, note: noteText(state, since), agent: true };
}

/** The state's duration in a few words, for the quiet line beside a newest message; nothing when the daemon gave no start. */
function noteText(state: SessionState, since: number | null): string | null {
  if (since === null) return null;
  const span = spanText(since);
  switch (state) {
    case 'waiting': return SW.waitingNote(span);
    case 'stuck': return SW.stuckNote(span);
    case 'working': return SW.workingNote(span);
    case 'idle': return SW.idleNote(span);
    case 'gone': return null;
  }
}

/** What a state means and for how long, and nothing the console cannot know: the sentence a card falls back to when the daemon sent no
 *  newest message. */
export function activityText(state: SessionState, since: number | null, quiet: number | null = null): string {
  const span = since === null ? null : spanText(since);
  switch (state) {
    case 'waiting':
      return span === null ? SW.waiting : SW.waitingFor(span);
    case 'stuck':
      return span === null ? SW.stuck : SW.stuckFor(span);
    case 'working':
      if (quiet !== null && quiet >= QUIET_AFTER_MS) return SW.toolQuiet(spanText(quiet));
      return span === null ? SW.working : SW.workingFor(span);
    case 'idle':
      return span === null ? SW.idle : SW.idleFor(span);
    case 'gone':
      return SW.gone;
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
export function sessionsLede(rows: readonly SessionRow[], turnOf?: TurnOf): string {
  const count = { working: 0, waiting: 0, stuck: 0, idle: 0, gone: 0 };
  for (const row of rows) count[stateOf(row, turnOf?.(row))] += 1;
  const parts: string[] = [];
  if (count.working > 0) parts.push(`${numberWord(count.working)} ${count.working === 1 ? 'is' : 'are'} working`);
  if (count.waiting > 0) parts.push(`${numberWord(count.waiting)} ${count.waiting === 1 ? 'is' : 'are'} waiting on you`);
  if (count.stuck > 0) parts.push(`${numberWord(count.stuck)} ${count.stuck === 1 ? 'is' : 'are'} stuck`);
  const finished = count.idle + count.gone;
  const first = parts.length === 0 ? '' : `${capital(parts.join(', '))}.`;
  const last = finished === 0 ? '' : `${capital(numberWord(finished))} finished earlier.`;
  return [first, last].filter((sentence) => sentence !== '').join(' ');
}

/** The two durations a card's line needs. `since` is how long the session has been in its state (a stuck one counts
 *  its live turn's own silence); `quiet` is set only for a busy session the head confirms runs no turn, which is
 *  Claude Code running a tool locally: how long the registry has heard nothing from it. */
export function timingOf(row: SessionRow, state: SessionState, turn: LiveTurn | null | undefined, now: number): { since: number | null; quiet: number | null } {
  const since = state === 'stuck' && turn?.idle_ms !== undefined ? turn.idle_ms : sinceOf(row, now);
  const quiet = state === 'working' && turn === null && row.status_updated_at !== null ? Math.max(0, now - row.status_updated_at) : null;
  return { since, quiet };
}
