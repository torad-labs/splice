// The data layer: one typed hook per daemon read, one typed function per write. Pages never touch
// `fetch`; they ask here. The key constants are what the event stream marks stale (events.ts).
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseQueryOptions } from '@tanstack/react-query';
import { health, request } from './client';
import type {
  AuthPayload,
  ConfigPayload,
  ControlStatusPayload,
  HeadActionResult,
  HeadsPayload,
  UsagePayload,
} from '../types/core';
import type { SessionsPayload } from '../types/sessions';
import type { TeamsPayload } from '../types/teams';
import type { LiveTurnsPayload, StopTurnResult } from '../types/turns';

export const keys = {
  status: ['status'],
  heads: ['heads'],
  usage: ['usage'],
  auth: ['auth'],
  accounts: ['accounts'],
  sessions: ['sessions'],
  edges: ['edges'],
  liveTurns: ['live-turns'],
  perf: ['perf'],
  config: ['config'],
  health: ['health'],
  transcript: ['transcript'],
  teams: ['teams'],
} as const;

/** How often a page re-reads without an event: the stream is the fast path, this is the floor. */
const FLOOR_MS = 15_000;

export const read = <T>(key: readonly string[], path: string, extra: Partial<UseQueryOptions<T>> = {}) =>
  ({ queryKey: [...key, path], queryFn: () => request<T>(path), refetchInterval: FLOOR_MS, ...extra }) satisfies UseQueryOptions<T>;

export const useStatus = () => useQuery(read<ControlStatusPayload>(keys.status, '/api/status', { refetchInterval: false, staleTime: 60_000 }));
export const useHeads = () => useQuery(read<HeadsPayload>(keys.heads, '/api/heads'));
export const useUsage = () => useQuery(read<UsagePayload>(keys.usage, '/api/usage'));
export const useAuth = () => useQuery(read<AuthPayload>(keys.auth, '/api/auth'));
export const useSessions = () => useQuery(read<SessionsPayload>(keys.sessions, '/api/sessions'));
export const useTeams = () => useQuery(read<TeamsPayload>(keys.teams, '/api/teams'));
export const useConfig = (head?: string) =>
  useQuery(read<ConfigPayload>(keys.config, head === undefined ? '/api/config' : `/api/config?head=${encodeURIComponent(head)}`, { refetchInterval: false }));
export const useHealth = () =>
  useQuery({ queryKey: [...keys.health], queryFn: () => health<{ topologyStale?: boolean }>(), refetchInterval: FLOOR_MS });

export const useLiveTurns = (head: string, enabled = true) =>
  useQuery(read<LiveTurnsPayload>(keys.liveTurns, `/api/heads/${encodeURIComponent(head)}/turns/live`, { enabled }));

/** A write, then everything it may have changed is read again. */
function useWrite<Vars, Out>(run: (vars: Vars) => Promise<Out>, stale: readonly (readonly string[])[]) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: run,
    onSettled: () => Promise.all(stale.map((key) => client.invalidateQueries({ queryKey: [...key] }))),
  });
}

export const useHeadAction = () =>
  useWrite(
    ({ head, action }: { head: string; action: 'start' | 'stop' | 'restart' }) =>
      request<HeadActionResult>(`/api/heads/${encodeURIComponent(head)}/${action}`, { method: 'POST' }),
    [keys.heads, keys.usage],
  );
export const useStopTurn = () =>
  useWrite(
    ({ head, id }: { head: string; id: string }) =>
      request<StopTurnResult>(`/api/heads/${encodeURIComponent(head)}/turns/${encodeURIComponent(id)}/stop`, { method: 'POST' }),
    [keys.liveTurns, keys.sessions],
  );
