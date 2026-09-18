// The payload contract of the topology entity. Every edit here is BOOT-ONLY: `/health` reports
// `topologyStale` when the file on disk no longer matches what the daemon booted with, and
// `splice restart` drains in-flight turns before restarting (V4-74). The console therefore shows a
// topology write as "written, restart to apply", never as "applied".
//
//   GET /api/topology -> TopologyPayload      (PENDING V4-128, typed from FEATURES.md 6 + 2.3)
//   PUT /api/topology -> TopologyWriteResult  (same row)
import type { PendingRoute } from '@shared/api';
import type { Provenance } from '@shared/ui';

/**
 * What every topology field box prints as its provenance.
 *
 * The FieldBox vocabulary (CONTRACTS.md section 2) names the six CONFIG layers, weakest first:
 * enum default, `[defaults]`, `[heads.<key>.overrides]`, state file, env, PATCH. A topology key's
 * source is none of those — it is the file — and 'defaults table' is the only name in the closed
 * set that means "the TOML file". It is printed BESIDE a row that names `splice.toml` outright, so
 * the operator reads the file name and not the shorthand; the gap is recorded on the M2-05 ledger
 * note for the orchestrator to close with a real name if the shorthand is not good enough.
 */
export const TOPOLOGY_PROVENANCE: Provenance = 'defaults table';

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

export interface TopologyWriteResult {
  ok: boolean;
  /** The backup taken before the write. FEATURES 4.7: "Backs the file up first". */
  backup_path?: string;
  /** The writer's own diagnostics; empty on a clean write. */
  findings?: TopologyFinding[];
  /** Always true. A topology edit cannot reach running sessions, so a write that reported
   *  `restart_required: false` would be lying about the daemon's own boot behaviour. */
  restart_required: boolean;
}

/** The v0.4.0 item that will serve GET and PUT /api/topology (FEATURES.md 6). */
export const PENDING_TOPOLOGY = 'V4-128';

export type TopologyState = TopologyPayload | PendingRoute;
