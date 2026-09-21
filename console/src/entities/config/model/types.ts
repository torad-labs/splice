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

/** The two verdicts a field box prints. A hot knob applies on the next turn; every other knob
 *  waits for a restart, and the strip cocks until that restart runs. */
export const HOT_TEXT = 'applies live';
export const RESTART_TEXT = 'restart to apply';

/** One knob, resolved: its effective value, where that value came from, and whether saving it
 *  needs a restart. */
export interface KnobDisposition {
  key: string;
  value: ConfigValue;
  provenance: Provenance;
  /** Read from `restart_required_keys` on the payload, never from a hand list (FEATURES 2.2). */
  hot: boolean;
}
