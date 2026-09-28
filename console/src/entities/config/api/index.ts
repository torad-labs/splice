import { control } from '@shared/api';
import type { ConfigValue, PatchResult } from '@shared/api';
import { poll } from '@shared/lib';
import { markRestartPending, observeDaemonBoot, restartStore } from '../model/restart';
import { configStore } from '../model/store';

export async function fetchConfig(head?: string): Promise<void> {
  const key = head ?? null;
  try {
    configStore.land(key, await control.config(head));
  } catch (err) {
    configStore.fail(key, err instanceof Error ? err.message : String(err));
  }
}

let healthRead = 0;
let healthAccepted = 0;

/** One health read says whether topology changed and which boot actually answered. */
export async function probeTopologyStale(): Promise<boolean> {
  const read = ++healthRead;
  const res = await fetch('/health');
  if (!res.ok) throw new Error(`/health answered ${res.status}`);
  const body = (await res.json()) as { topologyStale?: boolean; bootedAtEpochMillis?: unknown };
  if (read >= healthAccepted) {
    healthAccepted = read;
    const boot = body.bootedAtEpochMillis;
    if (typeof boot === 'number' && Number.isSafeInteger(boot) && boot > 0) observeDaemonBoot(boot);
  }
  return body.topologyStale === true;
}

/** The rule strip outlives pages; a failed health read never clears pending keys. */
export function startDaemonBootPolling(intervalMs: number): () => void {
  return poll(async () => { try { await probeTopologyStale(); } catch { /* Needs you and status report health failures. */ } }, intervalMs);
}

/** PATCH /api/config, fanned out to every running head (runtime layer wins
 * over env), then refresh the layered view. The refresh must read the SAME
 * view the operator is looking at (review #94, F142): a bare fetchConfig()
 * repopulated the store with the GLOBAL view while the page's selector still
 * showed a head, rendering global data under a per-head label. */
export async function applyConfigPatch(
  patch: Record<string, ConfigValue>,
  head?: string,
): Promise<PatchResult> {
  const result = await control.patchConfig(patch);
  // Read the boot AFTER PATCH: a restart before the write must not clear a setting B accepted.
  // A restart between PATCH and this read can keep the strip pending too long, but never lie that
  // an unapplied setting took effect. A health failure leaves the origin unknown.
  let bootedAtEpochMillis: number | null = null;
  try {
    await probeTopologyStale();
    bootedAtEpochMillis = restartStore.get().bootedAtEpochMillis;
  } catch { /* no boot identity: keep pending keys until a measured replacement */ }
  // The daemon names which keys it will not read until it restarts; that list is what cocks the
  // daemon strip, so it is recorded here, from the daemon's answer and never from a hand list.
  markRestartPending(result.restart_required, bootedAtEpochMillis);
  await fetchConfig(head);
  return result;
}

// JW-04: the older fail-open topology read still serves Fleet and Settings.
export { fetchTopologyStale } from '@shared/api';
