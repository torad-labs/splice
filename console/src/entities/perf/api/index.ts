// The performance entity's HTTP segment: the three perf routes, no rendering. The client helper
// carries the management key, the 401 lockout and the error envelope (CONTRACTS.md 8), so nothing
// here re-implements any of them.
import { pendingOf as routePendingOf, request } from '@shared/api';
import type { HeadsPayload } from '@shared/api';
import { poll } from '@shared/lib';
import { inflightFrom } from '../model/derive';
import { mergeTurns } from '../model/turns-wire';
import { captureStore, perfStore, perfSummaryStore, perfTurnsStore } from '../model/store';
import type {
  CaptureState,
  PerfPayload,
  PerfSummaryPayload,
  PerfTurnsWire,
  PerfWindowLabel,
} from '../model/types';

/** The v0.4.0 item that will serve GET /api/perf/turns. */
export const PENDING_TURNS = 'V4-127';

/** The v0.4.0 item that will serve GET/PUT /api/heads/{head}/capture. */
export const PENDING_CAPTURE = 'V4-133';

const DEFAULT_TAIL = 200;

function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

export async function fetchPerf(tail = DEFAULT_TAIL): Promise<void> {
  perfStore.startLoading();
  try {
    perfStore.setData(await request<PerfPayload>(`/api/perf?tail=${tail}`));
  } catch (err) {
    perfStore.setError(messageOf(err));
  }
}

export async function fetchPerfSummary(label: PerfWindowLabel = '24h'): Promise<void> {
  perfSummaryStore.startLoading();
  try {
    perfSummaryStore.setData(await request<PerfSummaryPayload>(`/api/perf/summary?window=${label}`));
  } catch (err) {
    perfSummaryStore.setError(messageOf(err));
  }
}

/** One head's turns, or the failure that kept them from being read. */
type HeadRead = { ok: true; wire: PerfTurnsWire } | HeadFailure;
type HeadFailure = { ok: false; head: string; err: unknown };

async function readHeadTurns(head: string, n: number, since: number | undefined): Promise<HeadRead> {
  const query = new URLSearchParams({ head, n: String(n) });
  if (since !== undefined) query.set('since', String(since));
  try {
    return { ok: true, wire: await request<PerfTurnsWire>(`/api/perf/turns?${query.toString()}`) };
  } catch (err) {
    return { ok: false, head, err };
  }
}

/**
 * The landed rows plus the in-flight set. The live half comes from GET /api/heads rather than from
 * the heads entity because a slice may not import a sibling slice (eslint-plugin-boundaries): the
 * turns store stays self-contained at the cost of one more heads read per tick.
 *
 * GET /api/perf/turns answers ONE head per request: an absent head is refused with 400 by design
 * (PerfRoutes.turns, pinned by ConsoleRoutesTest), and asking without one is why this page 400ed on
 * every poll from V4-127 until 2026-09-22. So the whole fleet is every configured head, read in
 * parallel off the same heads read and merged (model/turns-wire.ts). One head failing does not
 * blank the others: it is named in `unread`, and only a read where EVERY head failed is an error.
 */
export async function fetchPerfTurns(head?: string, n = DEFAULT_TAIL, since?: number): Promise<void> {
  perfTurnsStore.startLoading();
  try {
    const heads = await request<HeadsPayload>('/api/heads');
    const keys = head !== undefined && head !== '' ? [head] : heads.heads.map((status) => status.key);
    const reads = await Promise.all(keys.map((key) => readHeadTurns(key, n, since)));
    const failed = reads.filter((read): read is HeadFailure => !read.ok);
    const first = failed[0];
    if (first !== undefined && failed.length === reads.length) throw first.err;
    const merged = mergeTurns(reads.flatMap((read) => (read.ok ? [read.wire] : [])), n);
    const unread = [...merged.unread, ...failed.map((read) => ({ head: read.head, reason: messageOf(read.err) }))];
    perfTurnsStore.setData({ inflight: inflightFrom(heads.heads), landed: merged.landed, unread });
  } catch (err) {
    const pending = routePendingOf(err, PENDING_TURNS);
    if (pending !== null) {
      perfTurnsStore.setData(pending);
      return;
    }
    perfTurnsStore.setError(messageOf(err));
  }
}

export function startPerfPolling(intervalMs = 5000): () => void {
  return poll(fetchPerf, intervalMs);
}

export function startPerfSummaryPolling(label: PerfWindowLabel = '24h', intervalMs = 15000): () => void {
  return poll(() => fetchPerfSummary(label), intervalMs);
}

export function startPerfTurnsPolling(head?: string, intervalMs = 5000): () => void {
  return poll(() => fetchPerfTurns(head), intervalMs);
}

/**
 * One head's body capture: the toggle and, when it is on, the bodies of one turn (`at`, its `ts`).
 * Read when a turn is opened, never polled: capture is off by default and a timer would ask the
 * daemon the same question forever.
 *
 * PENDING V4-133, so the pending state is a real outcome rather than an error path.
 */
export async function fetchCapture(head: string, at?: number): Promise<void> {
  captureStore.startLoading();
  const query = at === undefined ? '' : `?at=${at}`;
  try {
    captureStore.setData(await request<CaptureState>(`/api/heads/${encodeURIComponent(head)}/capture${query}`));
  } catch (err) {
    const pending = routePendingOf(err, PENDING_CAPTURE);
    if (pending !== null) {
      captureStore.setData(pending);
      return;
    }
    captureStore.setError(messageOf(err));
  }
}
