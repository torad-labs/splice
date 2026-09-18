// The performance entity's HTTP segment: the three perf routes, no rendering. The client helper
// carries the management key, the 401 lockout and the error envelope (CONTRACTS.md 8), so nothing
// here re-implements any of them.
import { pendingOf as routePendingOf, request } from '@shared/api';
import type { HeadsPayload, PendingRoute } from '@shared/api';
import { poll } from '@shared/lib';
import { inflightFrom } from '../model/derive';
import { captureStore, perfStore, perfSummaryStore, perfTurnsStore } from '../model/store';
import type {
  CaptureState,
  PerfPayload,
  PerfSummaryPayload,
  PerfTurnsPayload,
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

/**
 * The perf row's binding of the shared pending rule (@shared/api), kept as a one-argument
 * function because callers that already address this slice should not have to repeat which row
 * they are waiting for. The RULE itself lives in one place now; only the row name is local.
 *
 * NOTE for the daemon: a 404 is unambiguous while the whole route family is absent. Once
 * /api/perf/turns exists, an UNKNOWN HEAD must answer something else (a named error, or 400) or a
 * typo'd head will read to the operator as "not built yet".
 */
export function pendingOf(err: unknown): PendingRoute | null {
  return routePendingOf(err, PENDING_TURNS);
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
