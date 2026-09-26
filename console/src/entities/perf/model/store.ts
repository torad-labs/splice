import { createResource } from '@shared/lib';
import type { PendingRoute } from '@shared/api';
import type { CaptureState, PerfPayload, PerfSummaryPayload, TurnsState } from './types';

/** Per-field percentiles per head (GET /api/perf). */
export const perfStore = createResource<PerfPayload>();

/** Windowed summaries per head (GET /api/perf/summary). */
export const perfSummaryStore = createResource<PerfSummaryPayload>();

/** The turns view: the in-flight set off the heads gate snapshots beside the landed rows. A union
 *  with PendingRoute because the turns route does not exist yet (V4-127): the store holds the
 *  honest empty, never a mocked row. */
export const perfTurnsStore = createResource<TurnsState | PendingRoute>();

/**
 * The body capture the console holds (GET/PUT /api/heads/{head}/capture): the last head read, with
 * what runs and what this console wrote for it, and each head whose last capture read or write
 * failed, in the daemon's words. Failures are kept by head because every reader asks for ONE head:
 * the store's one error printed under whichever head's drawer was open, while that head's own read
 * was in flight, and for good if it never landed (V4-301).
 */
export interface CaptureCell {
  state: CaptureState | null;
  failures: ReadonlyMap<string, string>;
}

export const captureStore = createResource<CaptureCell>();

/** One head's capture, as a drawer prints it. */
export interface HeadCapture {
  capture: CaptureState | null;
  error: string | null;
}

/** The capture and the failure that belong to `head`, each null when they are another head's: the
 *  one view both the turns and the logs page read, so neither gates at its call site. */
export function captureFor(cell: CaptureCell | null, head: string | null): HeadCapture {
  if (cell === null || head === null) return { capture: null, error: null };
  return {
    capture: cell.state !== null && cell.state.running.head === head ? cell.state : null,
    error: cell.failures.get(head) ?? null,
  };
}
