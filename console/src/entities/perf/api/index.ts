// The performance entity's HTTP segment: the three perf routes and a head's capture switch, no
// rendering. The client helper
// carries the management key, the 401 lockout and the error envelope (CONTRACTS.md 8), so nothing
// here re-implements any of them.
import { pendingOf as routePendingOf, request } from '@shared/api';
import type { HeadsPayload } from '@shared/api';
import { poll } from '@shared/lib';
import { afterRead, afterWrite } from '../model/capture';
import type { CaptureWriteResult } from '../model/capture';
import { inflightFrom } from '../model/derive';
import { mergeTurns } from '../model/turns-wire';
import { captureStore, perfStore, perfSummaryStore, perfTurnsStore } from '../model/store';
import type {
  CaptureWire,
  PerfPayload,
  PerfSummaryPayload,
  PerfTurnsWire,
  PerfWindowLabel,
} from '../model/types';

/** The v0.4.0 item that will serve GET /api/perf/turns. */
export const PENDING_TURNS = 'V4-127';

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
 * One head's capture settings, as the daemon runs them (GET /api/heads/{head}/capture). Read when a
 * turn is opened or a page names a head, never polled: nothing changes them but a write and a
 * restart. The route takes no turn: it serves settings, never a body (CaptureRoutes.read).
 */
export async function fetchCapture(head: string): Promise<void> {
  captureStore.startLoading();
  try {
    const read = await request<CaptureWire>(`/api/heads/${encodeURIComponent(head)}/capture`);
    captureStore.setData(afterRead(captureStore.get().data, read));
  } catch (err) {
    captureStore.setError(messageOf(err));
  }
}

/**
 * Turn one head's capture on or off: PUT, then GET again. The store takes the RE-READ as what runs
 * and the PUT's answer as what was written, never the request, because the daemon applies a write
 * only at its next restart (`restart_required`) and a refused write changes nothing: neither may
 * read as capture on. A refusal is kept in the daemon's own words.
 */
export async function putCapture(head: string, enabled: boolean): Promise<void> {
  const previous = captureStore.get().data;
  const mine = previous !== null && previous.running.head === head ? previous : null;
  if (mine !== null) captureStore.setData({ ...mine, writing: true });
  let write: CaptureWriteResult;
  try {
    const answer = await request<CaptureWire>(`/api/heads/${encodeURIComponent(head)}/capture`, {
      method: 'PUT',
      body: JSON.stringify({ enabled }),
    });
    write = { ok: true, answer };
  } catch (err) {
    write = { ok: false, reason: messageOf(err) };
  }
  try {
    const reread = await request<CaptureWire>(`/api/heads/${encodeURIComponent(head)}/capture`);
    captureStore.setData(afterWrite(mine, write, reread));
  } catch (err) {
    // The write's outcome is still known when the re-read fails; what runs is the last read, and the
    // failed read is reported beside it rather than hidden behind a stale switch.
    if (mine !== null) captureStore.setData(afterWrite(mine, write, mine.running));
    captureStore.setError(messageOf(err));
  }
}
