import { mgmt } from '@shared/api';
import type { ConfigValue, PatchResult } from '@shared/api';
import { configStore } from '../model/store';

export async function fetchConfig(): Promise<void> {
  configStore.startLoading();
  try {
    configStore.setData(await mgmt.config());
  } catch (err) {
    configStore.setError(err instanceof Error ? err.message : String(err));
  }
}

/** PATCH /mgmt/config (hot-apply) and refresh the layered view. */
export async function applyConfigPatch(patch: Record<string, ConfigValue>): Promise<PatchResult> {
  const result = await mgmt.patchConfig(patch);
  await fetchConfig();
  return result;
}
