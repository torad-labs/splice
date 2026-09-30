// The Turns page's reads: the landed turns, one turn's kept records, the performance summary, the compaction
// counts and rules, and a head's capture switch.
//
// GET /api/perf/turns answers ONE head per request (an absent head is a 400 by design), so the whole fleet is
// every configured head read in parallel off the same GET /api/heads and merged: one head failing does not
// blank the others, it is named in `unread`, and only a read where EVERY head failed is an error.
//
// The live half of a turn list (`inflight`) comes off the same heads read; GET /api/heads/{head}/turns/live is
// `useLiveTurns` in queries.ts.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { pendingOf } from './auth';
import { failureText, MgmtError, request } from './client';
import { keys } from './queries';
import { inflightFrom } from '../lib/perf';
import type { CompactPayload, InstructionRule, InstructionScopeWire, InstructionsState, InstructionsWire } from '../types/compaction';
import type { HeadsPayload } from '../types/core';
import type {
  CaptureWire,
  KeptTurn,
  PendingRoute,
  PerfSummaryPayload,
  PerfTurnsWire,
  PerfWindowLabel,
  TraceListWire,
  TraceTurnWire,
  TranscriptConversationWire,
  TruncatedHead,
  TurnRow,
  TurnRowWire,
  TurnsState,
  UnreadHead,
  WireRead,
  WireTapWire,
} from '../types/perf';

/** The v0.4.0 item that serves GET /api/perf/turns: a daemon older than it answers 404. */
export const PENDING_TURNS = 'V4-127';

export const DEFAULT_TAIL = 200;
export const TURNS_POLL_MS = 5000;
export const SUMMARY_POLL_MS = 15_000;
export const COMPACT_POLL_MS = 5000;
export const INSTRUCTIONS_POLL_MS = 15_000;

/** A tail read is marked stale by `turn.end` events (keys.perf); a window read is left to its own poll,
 *  timed to its weight, because a day of rows per head per turn is too much to re-read on every turn. */
const windowKey = ['perf-window'] as const;
const summaryKey = ['perf-summary'] as const;
const compactKey = ['compact'] as const;
const instructionsKey = ['compaction-instructions'] as const;
export const captureKey = (head: string) => ['capture', head] as const;

const seg = encodeURIComponent;

// ── paths ────────────────────────────────────────────────────────────────────────────────────────

export const perfSummaryPath = (label: PerfWindowLabel): string => `/api/perf/summary?window=${seg(label)}`;

export function perfTurnsPath(head: string, n: number, since?: number): string {
  const query = new URLSearchParams({ head, n: String(n) });
  if (since !== undefined) query.set('since', String(since));
  return `/api/perf/turns?${query.toString()}`;
}

const headPath = (head: string, leaf: string): string => `/api/heads/${seg(head)}/${leaf}`;
export const capturePath = (head: string): string => headPath(head, 'capture');
export const tracePath = (head: string): string => headPath(head, 'trace');
export const traceTurnPath = (head: string, turn: string): string => `${tracePath(head)}?turn=${seg(turn)}`;
export const wirePath = (head: string): string => headPath(head, 'wire');
export const conversationPath = (head: string, sessionId: string, responseId: string): string =>
  `${headPath(head, 'conversation')}?${new URLSearchParams({ session: sessionId, message: responseId }).toString()}`;
export const instructionsPath = (head: string): string => `/api/compaction/instructions?head=${seg(head)}`;

// ── the landed turns ─────────────────────────────────────────────────────────────────────────────

/** A wire row as a page row. The daemon writes an absent session, account, cache tag or trace turn as null; the
 *  page model spells the same absence by leaving the field out, which is what its readers test. */
export function rowFromWire(head: string, wire: TurnRowWire): TurnRow {
  const { session, account, cache_cold: cacheCold, turn, session_id: sessionId, response_message_id: responseId, ...rest } = wire;
  return {
    ...rest,
    head,
    ...(session !== null ? { session } : {}),
    ...(account !== null ? { account } : {}),
    ...(cacheCold !== null ? { cache_cold: cacheCold } : {}),
    ...(turn !== null ? { turn } : {}),
    ...(sessionId !== null ? { session_id: sessionId } : {}),
    ...(responseId !== null ? { response_message_id: responseId } : {}),
  };
}

export interface MergedTurns {
  landed: TurnRow[];
  unread: UnreadHead[];
  truncated: TruncatedHead[];
}

/**
 * Every head's rows as one list, newest last. The route caps each head at the `n` it was asked for, so the cap
 * is per head and nothing is cut here: a read over a window keeps every row each head served. A head the daemon
 * could not read (`error`, or a generation it could not open, `read_error`) is named in `unread`, and a head whose
 * window held more rows than the cap (`truncated`) is named in `truncated`: a list that silently lost either reads
 * exactly like a head that was idle.
 */
export function mergeTurns(answers: readonly PerfTurnsWire[]): MergedTurns {
  const landed: TurnRow[] = [];
  const unread: UnreadHead[] = [];
  const truncated: TruncatedHead[] = [];
  for (const answer of answers) {
    for (const block of answer.heads) {
      if (block.error !== undefined) unread.push({ head: block.key, reason: block.error });
      if (block.read_error !== undefined) unread.push({ head: block.key, reason: block.read_error });
      const rows = block.rows ?? [];
      if (block.truncated === true) truncated.push({ head: block.key, count: block.count ?? null, returned: rows.length });
      for (const row of rows) landed.push(rowFromWire(block.key, row));
    }
  }
  landed.sort((left, right) => left.ts - right.ts);
  return { landed, unread, truncated };
}

type HeadRead = { ok: true; wire: PerfTurnsWire } | { ok: false; head: string; err: unknown };

async function readHeadTurns(head: string, n: number, since: number | undefined): Promise<HeadRead> {
  try {
    return { ok: true, wire: await request<PerfTurnsWire>(perfTurnsPath(head, n, since)) };
  } catch (err) {
    return { ok: false, head, err };
  }
}

export type TurnsSlice = TurnsState | PendingRoute;

/**
 * The landed rows plus the in-flight set. A read without `since` is a tail, the fleet's newest `n`; a read with
 * `since` is a window (a timeline, the Teams day) and keeps every row each head served.
 *
 * Either cap can leave the list short of what was asked, so `completeFrom` says where the list starts holding every
 * turn: the later of each clamped head's oldest row and the oldest row the fleet cut kept, and never before the window
 * the daemon read. Before it, an hour with no rows is unread, not idle.
 */
export async function fetchTurns(head?: string, n: number = DEFAULT_TAIL, since?: number): Promise<TurnsSlice> {
  try {
    const heads = await request<HeadsPayload>('/api/heads');
    const asked = head !== undefined && head !== '' ? [head] : heads.heads.map((status) => status.key);
    const reads = await Promise.all(asked.map((key) => readHeadTurns(key, n, since)));
    const failed = reads.flatMap((read) => (read.ok ? [] : [read]));
    const first = failed[0];
    if (first !== undefined && failed.length === reads.length) throw first.err;
    const merged = mergeTurns(reads.flatMap((read) => (read.ok ? [read.wire] : [])));
    const unread = [...merged.unread, ...failed.map((read) => ({ head: read.head, reason: failureText(read.err) }))];
    const landed = since === undefined ? merged.landed.slice(-n) : merged.landed;
    const cuts = [
      ...reads.flatMap((read) => (read.ok ? [read.wire.since] : [])),
      ...merged.truncated.flatMap(({ head: clamped }) => merged.landed.find((row) => row.head === clamped)?.ts ?? []),
      ...(landed.length < merged.landed.length ? (landed[0] === undefined ? [] : [landed[0].ts]) : []),
    ];
    return {
      inflight: inflightFrom(heads.heads),
      landed,
      unread,
      truncated: merged.truncated,
      ...(cuts.length === 0 ? {} : { completeFrom: Math.max(...cuts) }),
    };
  } catch (err) {
    const pending = pendingOf(err, PENDING_TURNS);
    if (pending !== null) return pending;
    throw err;
  }
}

export interface TurnsAsk {
  /** One head, or every configured head when absent or empty. */
  head?: string | undefined;
  /** The newest `n` rows per head; default 200. */
  n?: number | undefined;
  /** Epoch ms: a window read from here on (kept whole per head) instead of a tail. */
  since?: number | undefined;
}

/** The landed turns and the in-flight set. A tail is re-read on every `turn.end` event; a window only on its own
 *  poll (`every`, default 5 s; false for none). */
export function usePerfTurns({ head, n = DEFAULT_TAIL, since }: TurnsAsk = {}, every: number | false = TURNS_POLL_MS) {
  return useQuery({
    queryKey: since === undefined ? [...keys.perf, 'tail', head ?? '', n] : [...windowKey, head ?? '', n, since],
    queryFn: () => fetchTurns(head, n, since),
    refetchInterval: every,
  });
}

/** GET /api/perf/summary: one windowed summary per head, on its own 15 s cadence (an aggregate over a window the
 *  events do not name). */
export const usePerfSummary = (label: PerfWindowLabel = '24h') =>
  useQuery({
    queryKey: [...summaryKey, label],
    queryFn: () => request<PerfSummaryPayload>(perfSummaryPath(label)),
    refetchInterval: SUMMARY_POLL_MS,
  });

// ── compaction ───────────────────────────────────────────────────────────────────────────────────

export const useCompactStats = () =>
  useQuery({ queryKey: [...compactKey], queryFn: () => request<CompactPayload>('/api/compact'), refetchInterval: COMPACT_POLL_MS });

/** CompactionInstructions.rules' own order: project-model, project, model, global. */
const PRECEDENCE: Record<InstructionScopeWire, number> = { 'project-model': 0, project: 1, model: 2, global: 3, client: 4 };

/** Per-head answers as the fleet's rules. Most rules apply to every head, so the answers are merged by
 *  (scope, source), each rule carrying the heads that listed it; a model rule lists only the heads whose roster
 *  carries the model. Stable within a tier: two project rules keep the order the daemon listed them in. */
export function mergeInstructions(answers: readonly { head: string; wire: InstructionsWire }[]): InstructionRule[] {
  const rules = new Map<string, InstructionRule>();
  for (const { head, wire } of answers) {
    for (const scope of wire.scopes) {
      const key = `${scope.scope}\n${scope.source}`;
      const held = rules.get(key);
      if (held === undefined) rules.set(key, { ...scope, heads: [head] });
      else if (!held.heads.includes(head)) held.heads.push(head);
    }
  }
  return [...rules.values()].sort((left, right) => PRECEDENCE[left.scope] - PRECEDENCE[right.scope]);
}

type InstructionsRead = { ok: true; head: string; wire: InstructionsWire } | { ok: false; head: string; err: unknown };

async function readInstructions(head: string): Promise<InstructionsRead> {
  try {
    return { ok: true, head, wire: await request<InstructionsWire>(instructionsPath(head)) };
  } catch (err) {
    return { ok: false, head, err };
  }
}

/** The compaction rules in effect across the fleet: every configured head asked in parallel off one heads read.
 *  One head failing is named in `unread`; only a read where EVERY head failed rejects. */
export async function fetchInstructions(): Promise<InstructionsState> {
  const heads = await request<HeadsPayload>('/api/heads');
  const reads = await Promise.all(heads.heads.map((status) => readInstructions(status.key)));
  const answered = reads.flatMap((read) => (read.ok ? [{ head: read.head, wire: read.wire }] : []));
  const failed = reads.flatMap((read) => (read.ok ? [] : [read]));
  const first = failed[0];
  if (first !== undefined && failed.length === reads.length) throw first.err;
  return { rules: mergeInstructions(answered), unread: failed.map((read) => ({ head: read.head, reason: failureText(read.err) })) };
}

/** The rules' lengths are live (a file edit shows without a restart), so they are polled, slower than the
 *  outcome feed because nothing but an edit moves them. */
export const useCompactionInstructions = () =>
  useQuery({ queryKey: [...instructionsKey], queryFn: fetchInstructions, refetchInterval: INSTRUCTIONS_POLL_MS });

// ── what `splice trace` and `splice wire` print ──────────────────────────────────────────────────
// Read when the operator asks and never polled. They carry the user's conversation, so they live as long as
// the drawer that asked: no cache after it closes (gcTime 0). A refusal rejects with the daemon's sentence.

const asked = { refetchInterval: false, gcTime: 0 } as const;

/** The newest turns on the head's trace files, with no body. */
export const useTraceList = (head: string | null, enabled = true) =>
  useQuery({ queryKey: ['trace', head ?? ''], queryFn: () => request<TraceListWire>(tracePath(head ?? '')), enabled: enabled && head !== null, ...asked });

/** A turn a perf row names (its `turn`), records and bodies included. A 400 is the daemon's sentence that its
 *  trace holds no such turn (retention deleted it, or the files were purged): a state the detail says, `{ gone }`. */
export async function readKeptTurn(head: string, turn: string): Promise<KeptTurn> {
  try {
    return { read: await request<TraceTurnWire>(traceTurnPath(head, turn)) };
  } catch (err) {
    if (err instanceof MgmtError && err.status === 400) return { gone: err.message };
    throw err;
  }
}

export const useKeptTurn = (head: string | null, turn: string | null, enabled = true) =>
  useQuery({
    queryKey: ['trace', head ?? '', turn ?? ''],
    queryFn: () => readKeptTurn(head ?? '', turn ?? ''),
    enabled: enabled && head !== null && turn !== null,
    ...asked,
  });

/** One request's redacted conversation through the client-facing response id. The daemon's live global knob gates
 *  the read; the answer says `off` or `missing` as data. */
export const useConversation = (head: string | null, sessionId: string | null, responseId: string | null, enabled = true) =>
  useQuery({
    queryKey: ['conversation', head ?? '', sessionId ?? '', responseId ?? ''],
    queryFn: () => request<TranscriptConversationWire>(conversationPath(head ?? '', sessionId ?? '', responseId ?? '')),
    enabled: enabled && head !== null && sessionId !== null && responseId !== null,
    ...asked,
  });

/** The head's kept upstream bodies, or the daemon's sentence that its tap is off (409): `{ off }`, a state and not a failure. */
export async function readWire(head: string): Promise<WireRead> {
  try {
    return { tap: await request<WireTapWire>(wirePath(head)) };
  } catch (err) {
    if (err instanceof MgmtError && err.status === 409) return { off: err.message };
    throw err;
  }
}

export const useWire = (head: string | null, enabled = true) =>
  useQuery({ queryKey: ['wire', head ?? ''], queryFn: () => readWire(head ?? ''), enabled: enabled && head !== null, ...asked });

// ── capture ──────────────────────────────────────────────────────────────────────────────────────

/** What one PUT came back with: the daemon's answer, or its refusal in its own words. */
export type CaptureWriteResult = { ok: true; answer: CaptureWire } | { ok: false; reason: string };

/** A capture write and the re-read that follows it. `running` is what the daemon records NOW (the re-read); the
 *  write's answer is what splice.toml says, which runs only after the restart `restart_required` asks for, and a
 *  refused write changes nothing: the switch never claims a value the daemon is not running. `running` is null and
 *  `fault` the daemon's words when the re-read itself failed. */
export interface CaptureChange {
  write: CaptureWriteResult;
  running: CaptureWire | null;
  fault: string | null;
}

/** One head's capture settings as the daemon runs them (settings, never a body). Read when a turn is opened or a
 *  page names a head, never polled: nothing changes them but a write and a restart. */
export const useCapture = (head: string | null) =>
  useQuery({ queryKey: [...captureKey(head ?? '')], queryFn: () => request<CaptureWire>(capturePath(head ?? '')), enabled: head !== null, refetchInterval: false });

export async function putCapture(head: string, enabled: boolean): Promise<CaptureChange> {
  let write: CaptureWriteResult;
  try {
    write = { ok: true, answer: await request<CaptureWire>(capturePath(head), { method: 'PUT', body: JSON.stringify({ enabled }) }) };
  } catch (err) {
    write = { ok: false, reason: failureText(err) };
  }
  try {
    return { write, running: await request<CaptureWire>(capturePath(head)), fault: null };
  } catch (err) {
    return { write, running: null, fault: failureText(err) };
  }
}

/** Turn one head's capture on or off. The re-read lands in the capture cache. */
export function useSetCapture() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ head, enabled }: { head: string; enabled: boolean }) => putCapture(head, enabled),
    onSuccess: (change, { head }) => {
      if (change.running !== null) client.setQueryData<CaptureWire>([...captureKey(head)], change.running);
    },
  });
}
