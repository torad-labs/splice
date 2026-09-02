import { createResource } from '@shared/lib';
import type { ConfigPayload, ConfigValue, EffectiveConfig } from '@shared/api';

export const configStore = createResource<ConfigPayload>();

export interface DiffEntry {
  key: string;
  from: ConfigValue | undefined;
  to: ConfigValue;
  restartRequired: boolean;
}

/** Pure diff of a proposed patch against the effective config — powers the
 * confirm modal (reference pattern: show exactly what changes before PATCH). */
export function diffPatch(
  effective: EffectiveConfig,
  patch: Record<string, ConfigValue>,
  restartKeys: string[],
): DiffEntry[] {
  const out: DiffEntry[] = [];
  for (const [key, to] of Object.entries(patch)) {
    const from = effective[key];
    if (from === to) continue;
    out.push({ key, from, to, restartRequired: restartKeys.includes(key) });
  }
  return out;
}

/** Parse an edited string back into the config value space. */
export function parseConfigInput(raw: string, reference: ConfigValue): ConfigValue {
  const s = raw.trim();
  if (s === '' || s === 'null') return null;
  if (typeof reference === 'boolean' || s === 'true' || s === 'false') return s === 'true';
  if (typeof reference === 'number' || /^-?\d+$/.test(s)) {
    const n = parseInt(s, 10);
    return Number.isFinite(n) ? n : s;
  }
  return s;
}

/** JW-06: the head-selector roster — 'global' plus every override-carrying head, stable order.
 * Only heads that actually override anything are selectable; everything else IS the global view. */
export function headOptions(perHead: Record<string, unknown> | undefined): string[] {
  return ['global', ...Object.keys(perHead ?? {}).sort()];
}
