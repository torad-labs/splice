// `splice upgrade` from the console: the release facts, and the run the daemon starts out of process. The run restarts the daemon,
// so a read nothing answers is that restart (`away`) and the reads go on until a daemon answers for the run from disk.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { MgmtError, request } from './client';
import { pendingOf } from './auth';
import { PENDING_UPGRADE } from '../types/doctor';
import type { UpgradeAsk, UpgradeRun, UpgradeSlice } from '../types/doctor';

export const upgradeKey = ['upgrade'] as const;
export const upgradeRunKey = ['upgrade-run'] as const;
/** A run prints a few dozen lines over a minute or two, and restarts the daemon partway. */
export const UPGRADE_RUN_POLL_MS = 2000;

/** GET /api/upgrade: installed, latest and rollback, or `{ pending }` from a daemon that does not serve it. */
export async function fetchUpgrade(): Promise<UpgradeSlice> {
  try {
    return await request<UpgradeSlice>('/api/upgrade');
  } catch (err) {
    const pending = pendingOf(err, PENDING_UPGRADE);
    if (pending !== null) return pending;
    throw err;
  }
}

/** A release changes when one is cut, not when the machine does: read once, again when a run ends. */
export const useUpgrade = () => useQuery({ queryKey: [...upgradeKey], queryFn: fetchUpgrade, refetchInterval: false });

/** The newest run (null before the console ever started one), and whether the last read went unanswered: `away` is the restart the run causes. */
export interface UpgradeRunView {
  run: UpgradeRun | null;
  away: boolean;
}

/** GET /api/upgrade/run. No answer at all keeps the run last seen and marks it away; a daemon that answers with a refusal rejects with its sentence. */
export async function readUpgradeRun(before: UpgradeRun | null): Promise<UpgradeRunView> {
  try {
    return { run: (await request<{ run: UpgradeRun | null }>('/api/upgrade/run')).run, away: false };
  } catch (err) {
    if (err instanceof MgmtError && err.status === 0) return { run: before, away: true };
    throw err;
  }
}

/** Polls while the run last read is running, and while nothing answers, since that is the restart. */
export const useUpgradeRun = () => {
  const client = useQueryClient();
  return useQuery({
    queryKey: [...upgradeRunKey],
    queryFn: async () => {
      const view = await readUpgradeRun(client.getQueryData<UpgradeRunView>(upgradeRunKey)?.run ?? null);
      if (!view.away && view.run !== null && view.run.state !== 'running') void client.invalidateQueries({ queryKey: [...upgradeKey] });
      return view;
    },
    refetchInterval: (query) => {
      if (query.state.status === 'error') return false;
      const data = query.state.data;
      return data === undefined || data.away || data.run?.state === 'running' ? UPGRADE_RUN_POLL_MS : false;
    },
  });
};

/** POST /api/upgrade: starts the run and answers 202 with it. A refusal rejects with the daemon's sentence (400 not a release,
 *  409 a run already going, 500 could not start, 503 not wired). */
export const useStartUpgrade = () => {
  const client = useQueryClient();
  return useMutation({
    mutationFn: async (ask: UpgradeAsk) => (await request<{ run: UpgradeRun }>('/api/upgrade', { method: 'POST', body: JSON.stringify(ask) })).run,
    onSuccess: (run) => client.setQueryData<UpgradeRunView>(upgradeRunKey, { run, away: false }),
  });
};
