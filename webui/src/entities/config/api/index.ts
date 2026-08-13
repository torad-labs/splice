import { control } from '@shared/api';
import type { ConfigValue, PatchResult } from '@shared/api';
import { configStore } from '../model/store';

export async function fetchConfig(head?: string): Promise<void> {
  configStore.startLoading();
  try {
    configStore.setData(await control.config(head));
  } catch (err) {
    configStore.setError(err instanceof Error ? err.message : String(err));
  }
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
  await fetchConfig(head);
  return result;
}

// JW-04: re-exported through the entity so pages stay inside the boundaries policy
// (pages -> entities -> shared-api).
export { fetchTopologyStale } from '@shared/api';
