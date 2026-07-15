import { control } from '@shared/api';
import type { ConfigValue, PatchResult } from '@shared/api';
import { configStore } from '../model/store';

export async function fetchConfig(): Promise<void> {
  configStore.startLoading();
  try {
    configStore.setData(await control.config());
  } catch (err) {
    configStore.setError(err instanceof Error ? err.message : String(err));
  }
}

/** PATCH /api/config, fanned out to every running head (runtime layer wins
 * over env), then refresh the layered view. */
export async function applyConfigPatch(patch: Record<string, ConfigValue>): Promise<PatchResult> {
  const result = await control.patchConfig(patch);
  await fetchConfig();
  return result;
}
