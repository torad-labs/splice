// The doctor read, the fixes the daemon runs itself, and the draining daemon restart.
//
// useTopologyStale is not here: it is the /health `topologyStale` flag, already read by `useHealth`
// in queries.ts, and a second hook for one field of the same query would be a copy.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { MgmtError, request } from './client';
import { keys } from './queries';
import { pendingOf } from './auth';
import { refetch } from './refetch';
import type { DoctorSlice, DoctorWirePayload } from '../types/doctor';

/** The v0.4.0 item that serves GET /api/doctor: a daemon older than it answers 404. */
export const PENDING_DOCTOR = 'V4-127';

export const doctorKey = ['doctor'] as const;

/** The report is generated fresh per request (it probes processes and files), so it is read slowly: the
 *  page is a diagnostic surface, not a live gauge. */
export const DOCTOR_POLL_MS = 60_000;

/** GET /api/doctor: the report, or `{ pending }` when the daemon does not serve it. */
export async function fetchDoctor(): Promise<DoctorSlice> {
  try {
    return await request<DoctorWirePayload>('/api/doctor');
  } catch (err) {
    const pending = pendingOf(err, PENDING_DOCTOR);
    if (pending !== null) return pending;
    throw err;
  }
}

export const useDoctor = () => useQuery({ queryKey: [...doctorKey], queryFn: fetchDoctor, refetchInterval: DOCTOR_POLL_MS });

/** A fix's answer, with the report the daemon took after it. Applied: doctor re-run found no row still
 *  calling for the fix. Refused: the daemon's one sentence, because the fix refused or rows still call for it. */
export type DoctorFixResult =
  | { applied: true; report: DoctorWirePayload }
  | { applied: false; refusal: string; report: DoctorWirePayload };

/** The report a 409 carries, when it carries one. */
function reportOf(body: unknown): DoctorWirePayload | null {
  if (typeof body !== 'object' || body === null || !('report' in body)) return null;
  const report = (body as { report: unknown }).report;
  return typeof report === 'object' && report !== null && Array.isArray((report as { checks?: unknown }).checks)
    ? (report as DoctorWirePayload)
    : null;
}

export const doctorFixPath = (id: string): string => `/api/doctor/fix/${encodeURIComponent(id)}`;

/**
 * POST /api/doctor/fix/{id}. The daemon runs the fix in its own environment, runs doctor again, and answers
 * with that report whether or not the fix took (200 `{fix, report}`, 409 `{error, fix, report}`). Any answer
 * without a report (404 unknown fix, 503 unwired, no answer) rejects with the daemon's words.
 */
export async function runDoctorFix(id: string): Promise<DoctorFixResult> {
  try {
    const answer = await request<{ fix: string; report: DoctorWirePayload }>(doctorFixPath(id), { method: 'POST' });
    return { applied: true, report: answer.report };
  } catch (err) {
    if (!(err instanceof MgmtError) || err.status !== 409) throw err;
    const report = reportOf(err.body);
    if (report === null) throw err;
    return { applied: false, refusal: err.message, report };
  }
}

/** Run a fix; either answer's report replaces the cached doctor read, so the page shows what its next poll would. */
export function useDoctorFix() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: runDoctorFix,
    onSuccess: (result) => client.setQueryData<DoctorSlice>([...doctorKey], result.report),
  });
}

/** POST /api/daemon/restart, as DaemonRoutes.restartJson answers a taken one: `{"status": "draining"}`.
 *  A 202 means the drain was taken on, never that the daemon came back. */
export interface DaemonRestartWire {
  status: string;
}

/** Ask the daemon to drain and exit so its supervisor brings it back. A REFUSAL REJECTS: the daemon answers
 *  409 on a hand-started process and 503 when its wiring gave it no way to know, and the sentence is the
 *  whole answer. No pending row: the route is served, so a 404 is a daemon that stopped answering. */
export const restartDaemon = (): Promise<DaemonRestartWire> => request<DaemonRestartWire>('/api/daemon/restart', { method: 'POST' });

/** Restart the daemon, then read the health and status again. */
export function useRestartDaemon() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: restartDaemon,
    onSettled: () => refetch(client, [keys.health, keys.status, keys.heads]),
  });
}
