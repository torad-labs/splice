// The doctor entity's HTTP segment. One route, pending V4-127.
import { pendingOf, request } from '@shared/api';
import { poll } from '@shared/lib';
import { doctorStore } from '../model/store';
import type { DoctorPayload } from '../model/types';

/** The v0.4.0 item that will serve GET /api/doctor. */
export const PENDING_DOCTOR = 'V4-127';

/**
 * The report is generated fresh per request (it probes processes and files), so this polls slowly:
 * a doctor run spawns process probes, and the page is a diagnostic surface, not a live gauge.
 */
export async function fetchDoctor(): Promise<void> {
  doctorStore.startLoading();
  try {
    doctorStore.setData(await request<DoctorPayload>('/api/doctor'));
  } catch (err) {
    const pending = pendingOf(err, PENDING_DOCTOR);
    if (pending !== null) {
      doctorStore.setData(pending);
      return;
    }
    doctorStore.setError(err instanceof Error ? err.message : String(err));
  }
}

export function startDoctorPolling(intervalMs = 60000): () => void {
  return poll(fetchDoctor, intervalMs);
}
