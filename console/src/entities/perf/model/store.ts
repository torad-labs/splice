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

/** One head's body capture (GET/PUT /api/heads/{head}/capture): what runs and what was written. */
export const captureStore = createResource<CaptureState>();
