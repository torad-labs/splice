// The doctor entity's HTTP segment: the report, the upgrade status, and the fixes the daemon runs.
import { MgmtError, pendingOf, request } from '@shared/api';
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

/** A fix's answer. Applied: doctor re-run found no row still calling for it. Refused: the daemon's
 *  one sentence, because the fix refused or rows still call for it. */
export type DoctorFixAnswer = { applied: true } | { applied: false; refusal: string };

/** The report a 409 carries, when it carries one: the run the daemon took after the fix. */
function reportOf(body: unknown): DoctorPayload | null {
  if (typeof body !== 'object' || body === null || !('report' in body)) return null;
  const report = (body as { report: unknown }).report;
  return typeof report === 'object' && report !== null && Array.isArray((report as { checks?: unknown }).checks)
    ? report as DoctorPayload
    : null;
}

/**
 * POST /api/doctor/fix/{id} (V4-220 item 4). The daemon runs the fix in its own environment, runs
 * doctor again, and answers with that report whether or not the fix took (DoctorRoute.kt: 200
 * `{fix, report}`, 409 `{error, fix, report}`). Either report replaces the doctor's, so the page shows
 * what its next poll would read. Any answer without a report (404 unknown fix, 503 unwired, no answer)
 * rejects with the daemon's words and leaves the report as it was.
 */
export async function runDoctorFix(id: string): Promise<DoctorFixAnswer> {
  try {
    const answer = await request<{ fix: string; report: DoctorPayload }>(`/api/doctor/fix/${encodeURIComponent(id)}`, { method: 'POST' });
    doctorStore.setData(answer.report);
    return { applied: true };
  } catch (err) {
    if (!(err instanceof MgmtError) || err.status !== 409) throw err;
    const report = reportOf(err.body);
    if (report === null) throw err;
    doctorStore.setData(report);
    return { applied: false, refusal: err.message };
  }
}

export function startDoctorPolling(intervalMs = 60000): () => void {
  return poll(fetchDoctor, intervalMs);
}
