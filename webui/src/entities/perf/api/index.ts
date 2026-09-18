// The performance entity's HTTP segment: the three perf routes, no rendering. The client helper
// carries the management key, the 401 lockout and the error envelope (CONTRACTS.md 8), so nothing
// here re-implements any of them.
import { MgmtError, request } from '@shared/api';
import type { HeadsPayload } from '@shared/api';
import { poll } from '@shared/lib';
import { inflightFrom } from '../model/derive';
import { perfStore, perfSummaryStore, perfTurnsStore } from '../model/store';
import type {
  PerfPayload,
  PerfSummaryPayload,
  PerfTurnsPayload,
  PerfWindowLabel,
  PendingRoute,
} from '../model/types';

/** The v0.4.0 item that will serve GET /api/perf/turns. */
export const PENDING_TURNS = 'V4-127';

const DEFAULT_TAIL = 200;

function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

/**
 * The pending signal CONTRACTS.md 8 fixes: a route the daemon has not built answers 404, or names
 * the unknown route in its own error envelope. Anything else is a real error and is reported as
 * one, so a 500 is never dressed up as "not built yet".
 *
 * CAVEAT for V4-127: today a 404 from this family is unambiguous because the whole route is
 * absent. Once the route exists, it must answer an UNKNOWN HEAD differently from an absent route
 * (a named error, or 400), or a typo'd head will read to the operator as "pending".
 */
export function pendingOf(err: unknown): PendingRoute | null {
  if (!(err instanceof MgmtError)) return null;
  if (err.status === 404 || /unknown route|no such route/i.test(err.message)) {
    return { pending: PENDING_TURNS };
  }
  return null;
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

/**
 * The landed rows plus the in-flight set. The live half comes from GET /api/heads rather than from
 * the heads entity because a slice may not import a sibling slice (eslint-plugin-boundaries): the
 * turns store stays self-contained at the cost of one more heads read per tick.
 */
export async function fetchPerfTurns(head?: string, n = DEFAULT_TAIL, since?: number): Promise<void> {
  perfTurnsStore.startLoading();
  const query = new URLSearchParams({ n: String(n) });
  if (head !== undefined && head !== '') query.set('head', head);
  if (since !== undefined) query.set('since', String(since));
  try {
    const [turns, heads] = await Promise.all([
      request<PerfTurnsPayload>(`/api/perf/turns?${query.toString()}`),
      request<HeadsPayload>('/api/heads'),
    ]);
    perfTurnsStore.setData({ inflight: inflightFrom(heads.heads), landed: turns.turns });
  } catch (err) {
    const pending = pendingOf(err);
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
