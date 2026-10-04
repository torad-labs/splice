// The settings and topology reads and writes: PATCH /api/config, GET/PUT /api/topology, the instruction-file
// preview, and GET /api/mcp. `useConfig` (the layered view, optionally one head's) is in queries.ts.
//
// TWO WRITE PATHS, and neither takes a head. PATCH /api/config is global: the daemon has no per-head fanout
// (ConfigRoutes.patchConfig), so the body is the flat patch `{ key: value }` (null deletes the key), and a head
// only scopes the READ that shows the result. A head's own override is `[heads.<key>.overrides]` in splice.toml,
// written through `useTopologyWrite` (the whole topology, the override folded in), never through PATCH: a PATCH
// saved while a head's view was open landed in the state file, which outranks every head override.
//
// Boot-only keys: a saved knob or topology write that the running daemon does not apply until a restart is
// recorded by lib/config `markRestartPending(state, result.restart_required, boot)`, where `boot` is what
// `readBootedAt()` answers after the write.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { pendingOf } from './auth';
import { request } from './client';
import { keys } from './queries';
import { refetch } from './refetch';
import { recordSaved } from '../lib/restart-pending';
import { PENDING_TOPOLOGY } from '../types/topology';
import type { ConfigValue, PatchResult } from '../types/core';
import type { McpPayload } from '../types/mcp';
import type { InstructionFilePreview, TopologyPayload, TopologyState, TopologyWriteResult } from '../types/topology';

export const topologyKey = ['topology'] as const;
export const mcpKey = ['mcp'] as const;

export const TOPOLOGY_POLL_MS = 30_000;
export const MCP_POLL_MS = 10_000;

// ── config ───────────────────────────────────────────────────────────────────────────────────────

/** PATCH /api/config. The answer names what was `applied`, what was `rejected` (a 200 whose `rejected` names the
 *  key did not save it), `restart_required` keys, and whether the value reached disk (`persisted`, else
 *  `not_persisted` says why: live until a restart reads the saved one). */
export const patchConfig = (patch: Record<string, ConfigValue>): Promise<PatchResult> =>
  request<PatchResult>('/api/config', { method: 'PATCH', body: JSON.stringify(patch) });

/** Save knobs, then read every config view again (all heads', since a global value shows in each) and the health. */
export function useConfigWrite() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: patchConfig,
    onSettled: () => refetch(client, [keys.config, keys.health]),
  });
}

/** Save knobs as Settings does: the daemon's `restart_required` keys are recorded under the boot the write reached, so the
 *  page can say a change waits and Needs you can say splice must restart. Every config view is read again after. */
export function useKnobSave() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: async (patch: Record<string, ConfigValue>): Promise<PatchResult> => {
      const result = await patchConfig(patch);
      if (result.restart_required.length > 0) recordSaved(result.restart_required, await readBootedAt().catch(() => null));
      return result;
    },
    onSettled: () => refetch(client, [keys.config, keys.health]),
  });
}

/** The boot the daemon answering /health is on (`bootedAtEpochMillis`), or null when it did not say. It is what a
 *  saved boot-only key is pending under: a different boot later proves the restart happened. */
export async function readBootedAt(): Promise<number | null> {
  const body = await request<{ bootedAtEpochMillis?: unknown }>('/health');
  const boot = body.bootedAtEpochMillis;
  return typeof boot === 'number' && Number.isSafeInteger(boot) && boot > 0 ? boot : null;
}

// ── topology ─────────────────────────────────────────────────────────────────────────────────────

/** GET /api/topology: the parsed splice.toml, or `{ pending }` when the daemon does not serve it. */
export async function fetchTopology(): Promise<TopologyState> {
  try {
    return await request<TopologyPayload>('/api/topology');
  } catch (err) {
    const pending = pendingOf(err, PENDING_TOPOLOGY);
    if (pending !== null) return pending;
    throw err;
  }
}

export const useTopology = () => useQuery({ queryKey: [...topologyKey], queryFn: fetchTopology, refetchInterval: TOPOLOGY_POLL_MS });

/**
 * PUT /api/topology: the whole topology. The daemon backs the file up first and writes through its structured
 * writer, so a rejected key comes back as `findings` on a SUCCESSFUL response, not as a thrown error. A route the
 * daemon does not serve throws (a 404 MgmtError): there is no honest empty for a write.
 */
export const saveTopology = (topology: Record<string, unknown>): Promise<TopologyWriteResult> =>
  request<TopologyWriteResult>('/api/topology', { method: 'PUT', body: JSON.stringify({ topology }) });

/** Write the topology, then read it and the health (`topologyStale`) again. */
export function useTopologyWrite() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: saveTopology,
    onSettled: () => refetch(client, [topologyKey, keys.health]),
  });
}

/** Write the topology as a Settings switch does. `keys` are the topology keys the edit touches: when the daemon says the write
 *  moved a key only boot reads, they wait for a restart like any saved knob. */
export function useTopologyEdit() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: async ({ topology, keys: touched }: { topology: Record<string, unknown>; keys: readonly string[] }): Promise<TopologyWriteResult> => {
      const result = await saveTopology(topology);
      if (result.ok && result.restart_required) recordSaved(touched, await readBootedAt().catch(() => null));
      return result;
    },
    onSettled: () => refetch(client, [topologyKey, keys.health, mcpKey]),
  });
}

/** POST /api/topology/preview: read a draft instruction file as splice would load it, without saving the draft. */
export const previewInstructionFile = (head: string, file: string, mode: string): Promise<InstructionFilePreview> =>
  request<InstructionFilePreview>('/api/topology/preview', { method: 'POST', body: JSON.stringify({ head, file, mode }) });

export const usePreviewInstructionFile = () =>
  useMutation({ mutationFn: ({ head, file, mode }: { head: string; file: string; mode: string }) => previewInstructionFile(head, file, mode) });

// ── mcp ──────────────────────────────────────────────────────────────────────────────────────────

/** GET /api/mcp: the shared MCP host. One read and nothing to write: a hosted server that exits respawns on its next call. */
export const useMcp = () => useQuery({ queryKey: [...mcpKey], queryFn: () => request<McpPayload>('/api/mcp'), refetchInterval: MCP_POLL_MS });
