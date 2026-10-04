// The payload contract of the topology entity. Every edit here is BOOT-ONLY: `/health` reports
// `topologyStale` when the file on disk no longer matches what the daemon booted with, and
// `splice restart` drains in-flight turns before restarting (V4-74). The console therefore shows a
// topology write as "written, restart to apply", never as "applied".
//
//   GET /api/topology -> TopologyPayload      (PENDING V4-128, typed from FEATURES.md 6 + 2.3)
//   PUT /api/topology -> TopologyWriteResult  (same row)
import type { Provenance } from './config';
import type { PendingRoute } from './budget';

/**
 * What every topology field box prints as its provenance.
 *
 * A topology leaf has ONE source: the file at `TopologyPayload.path`. The closed provenance set
 * names it outright, `'splice.toml'`, so this is the accurate label, not a stand-in. It read
 * `'defaults table'` until 2026-09-18 on the reasoning that no name in the set meant "the TOML
 * file"; that was true when it was written and stopped being true when `5feab71d` (M2-10) added
 * `'splice.toml'` to the set. A justification pinned in prose outlived the fact it rested on, and
 * the label under it became a confident wrong answer where it had been an honest shorthand.
 *
 * A CONSTANT is the right shape, not a per-key map: the function is constant. The six-layer
 * vocabulary belongs to CONFIG KNOBS, and per-key knob provenance is already rendered from real
 * data on the fleet and knob-form surfaces out of `GET /api/config`. Topology leaves are not in
 * that key space: `TopologyKnobLayer.configOverrides()` projects a few `[daemon]`/`[defaults]`
 * fields INTO the knob layer, so the direction is topology to config, and `providers.*` and
 * `heads.*` leaves have no config key to join against.
 */
export const TOPOLOGY_PROVENANCE: Provenance | 'splice.toml' = 'splice.toml';

/** One finding from the pure validator, or from the daemon's structured writer. */
export interface TopologyFinding {
  /** Dotted path to the offending key, e.g. `providers.codex.quirks.wibble`. */
  path: string;
  message: string;
}

export interface TopologyPayload {
  /** Absolute path of the file the daemon reads (`~/.config/splice/splice.toml`). */
  path: string;
  /** The parsed topology. The console edits THIS, never raw text: the write goes through the
   *  structured TOML writer `add-model` uses (FEATURES 4.7), which is what keeps a round-trip from
   *  reflowing the operator's comments. */
  topology: Record<string, unknown>;
  /** The same flag `/health` carries as `topologyStale`. */
  stale: boolean;
}

/** Read-only file-backed instruction preview, bounded by the daemon. */
export interface InstructionFilePreview {
  text: string;
  chars: number;
  truncated: boolean;
}

export interface TopologyWriteResult {
  ok: boolean;
  /** The backup taken before the write. FEATURES 4.7: "Backs the file up first". */
  backup_path?: string;
  /** The writer's own diagnostics; empty on a clean write. */
  findings?: TopologyFinding[];
  /** True when the write moved a key only boot reads. False for a refusal, which wrote nothing (V4-413),
   *  and for a write that changed nothing but context windows, which the daemon re-reads (V4-162). */
  restart_required: boolean;
}

/** The v0.4.0 item that will serve GET and PUT /api/topology (FEATURES.md 6). */
export const PENDING_TOPOLOGY = 'V4-128';

export type TopologyState = TopologyPayload | PendingRoute;

/**
 * A node in the schema tree.
 *   `{}`            a leaf: any scalar is accepted.
 *   `keys`          a table with exactly these keys; a key not in the map is a finding.
 *   `each`          a table keyed by free names (providers.<name>, heads.<key>): every child table
 *                   is validated against this node.
 *   `array`         an array of tables ([[...]]): every element is validated against this node.
 *   `open`          a bag whose child KEYS are not schema (extra_headers, overrides). Key-checking
 *                   one of these would reject legal config, which is worse than not checking it.
 *   `names`         a list of names the daemon knows ([claude] share, a head's isolate): an entry
 *                   outside them is a notice, since the daemon also matches any on-disk item by name.
 *   `foldedKeys`    a table whose KEYS are these names in any letter case, as the loader lowercases them
 *                   (a head's model_slots), quote marks around a key dropped: another key is unknown, two that fold to one are a duplicate.
 */
export interface SchemaNode {
  keys?: Record<string, SchemaNode>;
  each?: SchemaNode;
  array?: SchemaNode;
  open?: boolean;
  names?: readonly string[];
  foldedKeys?: readonly string[];
}
