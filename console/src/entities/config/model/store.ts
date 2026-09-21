import { createResource } from '@shared/lib';
import type { ConfigPayload, ConfigValue, EffectiveConfig } from '@shared/api';
import { HOT_TEXT, RESTART_TEXT } from './types';
import type { KnobDisposition, Provenance } from './types';

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

/**
 * Which layer a knob's effective value came from, or null when no layer holds it at all.
 *
 * The walk is strongest-wins over the payload's layers, i.e. it keeps the LAST layer that carries
 * the key, in the precedence FEATURES 2.2 fixes. A key present in no layer is not a knob the
 * daemon knows about: returning 'default' for it would print a confident provenance for a typo,
 * so the null is the honest answer and the caller renders no provenance line.
 */
export function provenanceOf(
  key: string,
  payload: ConfigPayload,
  head?: string,
): Provenance | null {
  const { layers } = payload;
  const ordered: Array<[Provenance, EffectiveConfig | undefined]> = [
    ['default', layers.defaults],
    ['defaults table', layers.toml],
    ['head override', head === undefined ? undefined : layers.perHead[head]],
    ['state file', layers.file],
    ['env', layers.env],
    ['patch', layers.runtime],
  ];
  let found: Provenance | null = null;
  for (const [provenance, layer] of ordered) {
    if (layer !== undefined && Object.hasOwn(layer, key)) found = provenance;
  }
  return found;
}

/** The restart verdict, in the words a field box prints. */
export function dispositionText(hot: boolean): string {
  return hot ? HOT_TEXT : RESTART_TEXT;
}

/**
 * Every knob the daemon reports as effective, resolved into what Settings renders: its value, the
 * layer it came from, and whether saving a new value needs a restart.
 *
 * `effective` is the daemon's fully-resolved view, so it — not any one layer — is the denominator.
 * `head` folds that head's overrides in, and is also what makes the 'head override' layer
 * reachable: without it there is no per-head layer to read, which is why the caller must pass the
 * same head it fetched the view for (review #94, F142).
 */
export function knobDispositions(payload: ConfigPayload, head?: string): KnobDisposition[] {
  const restart = new Set(payload.restart_required_keys);
  return Object.entries(payload.effective)
    .map(([key, value]) => ({
      key,
      value,
      provenance: provenanceOf(key, payload, head) ?? 'default',
      hot: !restart.has(key),
    }))
    .sort((a, b) => a.key.localeCompare(b.key));
}
