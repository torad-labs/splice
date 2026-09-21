// The doctor entity's HTTP segment: two routes, both pending V4-127.
import { pendingOf, request } from '@shared/api';
import { poll } from '@shared/lib';
import { doctorStore, upgradeStore } from '../model/store';
import { PENDING_UPGRADE } from '../model/upgrade';
import type { DoctorPayload } from '../model/types';
import type { UpgradePayload } from '../model/upgrade';

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

/**
 * GET /api/upgrade. The orchestrator's note of 2026-09-18 put this call beside the doctor read
 * rather than in a slice of its own: the doctor page is the only surface that shows it, and a
 * one-route entity would be a directory for a number.
 *
 * It does NOT share the doctor's poll. An upgrade status changes when a release is cut, not when
 * the machine changes, so it is read once on mount.
 */
export async function fetchUpgrade(): Promise<void> {
  upgradeStore.startLoading();
  try {
    upgradeStore.setData(await request<UpgradePayload>('/api/upgrade'));
  } catch (err) {
    const pending = pendingOf(err, PENDING_UPGRADE);
    if (pending !== null) {
      upgradeStore.setData(pending);
      return;
    }
    upgradeStore.setError(err instanceof Error ? err.message : String(err));
  }
}

export function startDoctorPolling(intervalMs = 60000): () => void {
  return poll(fetchDoctor, intervalMs);
}
