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
import { topologyKey } from './config';
import { failureText, MgmtError, request } from './client';
import { keys } from './queries';
import { awaitRefetch } from './refetch';
import { awaitPendingRead, PENDING_READ_HEADERS } from './pending-read';
import { inflightFrom } from '../lib/perf';
import { U } from '../lib/words-usage';
import type { CompactPayload, InstructionRule, InstructionScopeWire, InstructionsState, InstructionsWire } from '../types/compaction';
import type { HeadsPayload } from '../types/core';
import type {
  CaptureWire,
  KeptTurn,
  PendingRoute,
  PerfSummaryPayload,
  PerfTurnsFilter,
  PerfTurnsWire,
  PerfWindowLabel,
  TraceTurnWire,
  TranscriptConversationWire,
  TruncatedHead,
  TurnRow,
  TurnRowWire,
  TurnUsageWire,
  TurnUsageStats,
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

/** The window one turns read asks of a head: from `since` (the daemon's last 24 hours when absent), to `until`
 *  exclusive (now when absent), narrowed by `filter`. */
export interface TurnsWindow {
  since?: number | undefined;
  until?: number | undefined;
  filter?: PerfTurnsFilter | undefined;
  timeZone?: string | undefined;
}

const FILTER_TEXT = ['outcome', 'model', 'account', 'session', 'unattributed'] as const;

export function perfTurnsPath(head: string, n: number, { since, until, filter = {}, timeZone }: TurnsWindow = {}): string {
  const query = new URLSearchParams({ head, n: String(n) });
  if (since !== undefined) query.set('since', String(since));
  if (until !== undefined) query.set('until', String(until));
  if (timeZone !== undefined) query.set('time_zone', timeZone);
  for (const key of FILTER_TEXT) {
    const value = filter[key];
    if (value !== undefined) query.set(key, value);
  }
  if (filter.compact !== undefined) query.set('compact', filter.compact ? '1' : '0');
  if (filter.local === false) query.set('local', '0');
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
  const { session, account, cache_cold: cacheCold, turn, turn_id: turnId, session_id: sessionId, response_message_id: responseId, ...rest } = wire;
  return {
    ...rest,
    head,
    ...(session !== null ? { session } : {}),
    ...(account !== null ? { account } : {}),
    ...(cacheCold !== null ? { cache_cold: cacheCold } : {}),
    ...(turn !== null ? { turn } : {}),
    ...(turnId != null ? { turn_id: turnId } : {}),
    ...(sessionId !== null ? { session_id: sessionId } : {}),
    ...(responseId !== null ? { response_message_id: responseId } : {}),
  };
}

export interface MergedTurns {
  landed: TurnRow[];
  unread: UnreadHead[];
  truncated: TruncatedHead[];
  matched: number | null;
  matchedBy: Record<string, number>;
  usageBy: Record<string, TurnUsageWire>;
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
  const matchedBy: Record<string, number> = {};
  const usageBy: Record<string, TurnUsageWire> = {};
  let counted = true;
  for (const answer of answers) {
    for (const block of answer.heads) {
      const reasons = [block.error, block.read_error, (block.skipped_lines ?? 0) > 0 ? `${block.skipped_lines} request ${block.skipped_lines === 1 ? 'record' : 'records'} could not be read.` : undefined].filter((reason): reason is string => reason !== undefined);
      if (reasons.length > 0) unread.push({ head: block.key, reason: reasons.join(' ') });
      if (typeof block.count === 'number') matchedBy[block.key] = block.count;
      else if (block.error === undefined) counted = false;
      if (block.usage !== undefined) usageBy[block.key] = block.usage;
      const rows = block.rows ?? [];
      if (block.truncated === true) truncated.push({ head: block.key, count: block.count ?? null, returned: rows.length });
      for (const row of rows) landed.push(rowFromWire(block.key, row));
    }
  }
  landed.sort((left, right) => left.ts - right.ts);
  const matched = counted ? Object.values(matchedBy).reduce((sum, count) => sum + count, 0) : null;
  return { landed, unread, truncated, matched, matchedBy, usageBy };
}

type HeadRead = { ok: true; wire: PerfTurnsWire } | { ok: false; head: string; err: unknown };

const record = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value);
const numeric = (value: unknown): value is number => typeof value === 'number' && Number.isFinite(value);

function usageStats(value: unknown): value is TurnUsageStats {
  if (!record(value)) return false;
  return ['requests', 'unpriced_requests', 'missing_input_requests', 'missing_output_requests', 'missing_cache_requests'].every(key => numeric(value[key])) &&
    ['input_tokens', 'cached_tokens', 'output_tokens', 'cost_usd', 'cache_share'].every(key => value[key] === null || numeric(value[key])) &&
    ['unpriced_uncounted_requests', 'unpriced_plan_requests', 'unpriced_undeclared_requests', 'unpriced_local_requests', 'unanswered_requests'].every(key => value[key] === undefined || numeric(value[key]));
}

function usageWire(value: unknown): value is TurnUsageWire {
  if (!record(value) || !usageStats(value.totals)) return false;
  return ['models', 'accounts', 'days'].every(key => {
    const rows = value[key];
    return Array.isArray(rows) && rows.every(row => record(row) && (row.key === null || typeof row.key === 'string') && usageStats(row));
  });
}

function readableRow(row: unknown): row is TurnRowWire {
  if (!record(row) || !numeric(row.ts)) return false;
  const reason = row.cost_reason;
  const thinking = row.reasoning_tokens;
  return (row.cause === undefined || row.cause === null || typeof row.cause === 'string') &&
    (reason === undefined || reason === null || ['uncounted', 'plan', 'local', 'undeclared', 'unanswered'].some(word => reason === word)) &&
    (thinking === undefined || thinking === null || numeric(thinking) && Number.isInteger(thinking) && thinking > 0);
}

function readableHistory(wire: unknown, head: string): wire is PerfTurnsWire {
  if (!record(wire) || !Array.isArray(wire.heads)) return false;
  return wire.heads.every(block => record(block) && typeof block.key === 'string' &&
    (block.read_pending === undefined || typeof block.read_pending === 'boolean') &&
    (block.count === undefined || numeric(block.count)) &&
    (block.rows === undefined || Array.isArray(block.rows) && block.rows.every(readableRow)) &&
    (block.usage === undefined || usageWire(block.usage))) && wire.heads.some(block => block.key === head);
}

async function readHeadTurns(head: string, n: number, window: TurnsWindow, signal?: AbortSignal, progressive = false): Promise<HeadRead> {
  try {
    while (true) {
      signal?.throwIfAborted();
      const wire = await request<PerfTurnsWire>(perfTurnsPath(head, n, window), {
        method: 'GET', ...(signal === undefined ? {} : { signal }),
        ...(progressive ? { headers: PENDING_READ_HEADERS } : {}),
      });
      if (!readableHistory(wire, head)) throw new Error(U.historyUnreadable);
      if (!wire.heads.some(block => block.read_pending === true)) return { ok: true, wire };
      await awaitPendingRead(signal);
    }
  } catch (err) {
    return { ok: false, head, err };
  }
}

export type TurnsSlice = TurnsState | PendingRoute;

function settledTurns(heads: HeadsPayload, reads: readonly HeadRead[], window: TurnsWindow & { n: number }, pendingHeads: string[]): TurnsState {
  const { n, since: from, until: to } = window;
  const failed = reads.flatMap(read => read.ok ? [] : [read]);
  const answers = reads.flatMap(read => read.ok ? [read.wire] : []);
  const merged = mergeTurns(answers);
  const landed = from === undefined ? merged.landed.slice(-n) : merged.landed;
  const cuts = [
    ...answers.map(answer => answer.since),
    ...merged.truncated.flatMap(({ head }) => merged.landed.find(row => row.head === head)?.ts ?? []),
    ...(landed.length < merged.landed.length ? landed[0]?.ts === undefined ? [] : [landed[0].ts] : []),
  ];
  return {
    inflight: inflightFrom(heads.heads),
    landed,
    unread: [...merged.unread, ...failed.map(read => ({ head: read.head, reason: failureText(read.err) }))],
    truncated: merged.truncated,
    matched: answers.length === 0 && (reads.length > 0 || pendingHeads.length > 0) ? null : merged.matched,
    matchedBy: merged.matchedBy,
    usageBy: merged.usageBy,
    pendingHeads,
    ...(from === undefined ? {} : { window: { since: from, until: to ?? null } }),
    ...(cuts.length === 0 ? {} : { completeFrom: Math.max(...cuts) }),
  };
}

/**
 * The landed rows plus the in-flight set. A read with no window start is a tail, the fleet's newest `n`; a read from
 * `since`, or over the `last` ms before the moment it runs, is a window (a timeline, the Teams day, the Requests list)
 * and keeps every row each head served. Every head is asked the same window and filters.
 *
 * Either cap can leave the list short of what was asked, so `completeFrom` says where the list starts holding every
 * turn: the later of each clamped head's oldest row and the oldest row the fleet cut kept, and never before the window
 * the daemon read. Before it, an hour with no rows is unread, not idle. `matched` is the daemon's count of what the
 * window and filters hold, however few rows came back.
 */
export async function fetchTurns({ head, n = DEFAULT_TAIL, since, until, last, filter, timeZone }: TurnsAsk = {}, now: () => number = Date.now, publish?: (state: TurnsState) => void, signal?: AbortSignal): Promise<TurnsSlice> {
  try {
    const heads = await request<HeadsPayload>('/api/heads', { method: 'GET', ...(signal === undefined ? {} : { signal }) });
    const asked = head !== undefined && head !== '' ? [head] : heads.heads.map((status) => status.key);
    // A rolling window is pinned to ONE instant, read once: every head is asked the same since and the same exclusive until, and
    // the state says that window, so a link built from its count opens the very range that count was taken over.
    const at = now();
    const rolling = since === undefined && last !== undefined;
    const from = since ?? (rolling ? at - last : undefined);
    const to = until ?? (rolling ? at : undefined);
    const window = { n, since: from, until: to, filter, timeZone };
    const settled: HeadRead[] = [];
    const pending = new Set(asked);
    if (!signal?.aborted) publish?.(settledTurns(heads, settled, window, [...pending]));
    const reads = await Promise.all(asked.map(async key => {
      const read = await readHeadTurns(key, n, window, signal, publish !== undefined);
      settled.push(read);
      pending.delete(key);
      if (!signal?.aborted) publish?.(settledTurns(heads, settled, window, [...pending]));
      return read;
    }));
    signal?.throwIfAborted();
    const failed = reads.flatMap(read => read.ok ? [] : [read]);
    const first = failed[0];
    if (first !== undefined && failed.length === reads.length) throw first.err;
    return settledTurns(heads, reads, window, []);
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
  /** Epoch ms: a window read from here on (kept whole per head) instead of a tail. Wins over `last`. */
  since?: number | undefined;
  /** A rolling window: the `last` ms before each read runs, so a page left open keeps reading the same span. */
  last?: number | undefined;
  /** Epoch ms, exclusive: the window ends here instead of now. */
  until?: number | undefined;
  /** What the daemon narrows each head's window by, before its clamp. */
  filter?: PerfTurnsFilter | undefined;
  /** Re-read on every `turn.end` event as well as on the poll. A tail always is; a window is when its asker says so,
   *  because a window wider than a day re-read on every turn is too heavy. */
  live?: boolean | undefined;
  /** Viewer zone for calendar-day aggregates. */
  timeZone?: string | undefined;
}

/** The landed turns and the in-flight set, re-read on the poll (`every`, default 5 s; false for none) and, for a tail or
 *  a `live` window, on every `turn.end` event. `enabled` false reads nothing. */
export function usePerfTurns(ask: TurnsAsk = {}, every: number | false = TURNS_POLL_MS, enabled = true) {
  const { head, n = DEFAULT_TAIL, since, last, until, filter, live, timeZone } = ask;
  const tail = since === undefined && last === undefined;
  const shape = [head ?? '', n, since ?? null, last ?? null, until ?? null, filter ?? {}, ...(timeZone === undefined ? [] : [timeZone])];
  return useQuery({
    queryKey: tail || live === true ? [...keys.perf, tail ? 'tail' : 'window', ...shape] : [...windowKey, ...shape],
    queryFn: () => fetchTurns(ask),
    refetchInterval: every,
    enabled,
  });
}

/** Usage publishes each settled command while the same pinned fleet read remains in flight. */
export function useUsageTurns(hours: number, timeZone: string, enabled: boolean) {
  const client = useQueryClient();
  const queryKey = ['usage-requests', hours, timeZone];
  return useQuery({
    queryKey,
    queryFn: ({ signal }) => fetchTurns(
      { last: hours * 3_600_000, n: 1, filter: { local: false }, timeZone },
      Date.now,
      state => { client.setQueryData(queryKey, state); },
      signal,
    ),
    refetchInterval: TURNS_POLL_MS,
    enabled,
  });
}

/** GET /api/perf/summary: one windowed summary per head, on its own 15 s cadence (an aggregate over a window the
 *  events do not name). */
export const usePerfSummary = (label: PerfWindowLabel = '24h', enabled = true) =>
  useQuery({
    queryKey: [...summaryKey, label],
    queryFn: () => request<PerfSummaryPayload>(perfSummaryPath(label)),
    refetchInterval: SUMMARY_POLL_MS,
    enabled,
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

/** Observe the tap only when the selected perf row carries exact request ownership. */
export const useWire = (head: string, enabled: boolean) =>
  useQuery({ queryKey: ['wire', head], queryFn: () => readWire(head), enabled, ...asked });

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
    // The saved value is read from the topology, so the mutation stays pending until that read has the write in it.
    onSuccess: async (change, { head }) => {
      if (change.running !== null) client.setQueryData<CaptureWire>([...captureKey(head)], change.running);
      await awaitRefetch(client, [topologyKey]);
    },
  });
}
