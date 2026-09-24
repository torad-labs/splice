// The provenance vocabulary of the config entity, plus the per-knob disposition a Settings field
// box prints. Both come from the payload the daemon already sends (GET /api/config); nothing here
// re-derives a value the daemon computed.
import type { ConfigValue } from '@shared/api';

/**
 * A knob's effective value came from exactly one layer. The names are the ones the FieldBox
 * primitive prints and they are in precedence order, weakest first (FEATURES 2.2):
 *
 *   enum default -> [defaults] in TOML -> [heads.<key>.overrides] -> state file -> env -> PATCH
 *
 * The mapping from the payload's layer names to these: `defaults` -> 'default', `toml` ->
 * 'defaults table', `perHead[head]` -> 'head override', `file` -> 'state file', `env` -> 'env',
 * `runtime` -> 'patch'. The two vocabularies are kept apart on purpose: the payload names the
 * daemon's storage, these name what the operator reads.
 */
export const PROVENANCE_LAYERS = [
  'default',
  'defaults table',
  'head override',
  'state file',
  'env',
  'patch',
] as const;

export type Provenance = (typeof PROVENANCE_LAYERS)[number];

/** One knob, resolved: its effective value, where that value came from, and whether saving it
 *  needs a restart. */
export interface KnobDisposition {
  key: string;
  value: ConfigValue;
  provenance: Provenance;
  /** Read from `restart_required_keys` on the payload, never from a hand list (FEATURES 2.2). */
  hot: boolean;
  /** The value the daemon falls back to when no layer sets the knob (the payload's `defaults`
   *  layer). What "changed" and "reset to default" are measured against. */
  defaultValue: ConfigValue;
  /** Heads whose `[heads.<key>.overrides]` in splice.toml set this knob. A value saved in the
   *  global view outranks those overrides (FEATURES 2.2 precedence), so the row says so. */
  overriddenBy: string[];
}
