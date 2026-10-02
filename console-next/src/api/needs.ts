// The Needs-you list, read from the same queries every other page uses: one Read per input, so the list
// says which input it could not read instead of saying nothing.
import type { UseQueryResult } from '@tanstack/react-query';
import { useEffect } from 'react';
import { failureText } from './client';
import { useDoctor } from './doctor';
import { useAccounts, useAuth, useHealth, useHeads, useSessions, useTeams, useUsage } from './queries';
import { needsOf } from '../lib/needs';
import { observeBoot, useRestartPending } from '../lib/restart-pending';
import type { NeedsList, Read } from '../types/needs';

/** A query as a Read: what it holds (kept across a failed poll), the error of its newest poll, and when it last answered. */
function readOf<T>(query: UseQueryResult<T>): Read<T> {
  return {
    data: query.data ?? null,
    error: query.isError ? failureText(query.error) : null,
    lastUpdated: query.dataUpdatedAt === 0 ? null : query.dataUpdatedAt,
  };
}

export function useNeeds(now: number): NeedsList {
  const heads = useHeads();
  const auth = useAuth();
  const accounts = useAccounts();
  const usage = useUsage();
  const sessions = useSessions();
  const teams = useTeams();
  const doctor = useDoctor();
  const health = useHealth();
  const restartPending = useRestartPending();
  // a replacement boot is what clears the settings that waited for one
  const boot = health.data?.bootedAtEpochMillis;
  useEffect(() => {
    if (typeof boot === 'number') observeBoot(boot);
  }, [boot]);
  const topology = readOf(health);
  return needsOf({
    heads: { ...readOf(heads), data: heads.data?.heads ?? null },
    auth: readOf(auth),
    accounts: readOf(accounts),
    usage: readOf(usage),
    sessions: readOf(sessions),
    teams: readOf(teams),
    doctor: readOf(doctor),
    topology: { ...topology, data: health.data === undefined ? null : health.data.topologyStale === true },
    restartPending,
  }, now);
}
