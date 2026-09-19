/** §campaign-inflight — the ledger is the memory; surface in-flight items at turn end.
 *
 *  Concept #945: agents die, sessions compact — durable state lives in
 *  dev/campaigns/*.toml. At Stop, list items still marked in_flight so the session
 *  either (a) records what it just finished (`manifest.py set-status <ID> done` +
 *  `note`), or (b) consciously confirms the item is running on a PEER session
 *  (fleet-builder) and turn-end is expected.
 *
 *  WARN, not block, by design: this orchestration model dispatches work to peer
 *  sessions and legitimately ends turns while items are in flight — a block here
 *  would fight the async workflow. The deliberate exception to block-preferred.
 *
 *  The payload names `python3 dev/campaigns/manifest.py` because that IS still the CLI an operator
 *  types (V4-143 owns converting it). The instruction is therefore correct as written, and this
 *  file stays in the no-python invoker census until that row lands.
 */
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";

import { HookResult, hookResult } from "../../orchestrator/result";
import { findProjectRoot } from "../../lib/astgrep_gate";

export const MODULE_NAME = "05_campaign_inflight_note";

type HookEvent = Record<string, unknown>;

export function applies(data: HookEvent): boolean {
  return !data.stop_hook_active;
}

export function run(data: HookEvent): HookResult | null {
  const root = findProjectRoot(data.cwd as string | undefined);
  if (root === null) return null;
  const campaigns = join(root, "dev/campaigns");
  if (!existsSync(campaigns) || !statSync(campaigns).isDirectory()) return null;

  const inflight: string[] = [];
  const manifests = readdirSync(campaigns)
    .filter((name) => name.endsWith(".toml"))
    .sort();
  for (const name of manifests) {
    let doc: Record<string, unknown>;
    try {
      doc = Bun.TOML.parse(readFileSync(join(campaigns, name), "utf8")) as Record<string, unknown>;
    } catch {
      continue;
    }
    const items = Array.isArray(doc.items) ? (doc.items as Record<string, unknown>[]) : [];
    for (const item of items) {
      if (item.status !== "in_flight") continue;
      const title = String(item.title ?? "").slice(0, 70);
      inflight.push(`${item.id ?? "?"} [${name.replace(/\.toml$/, "")}] ${title}`);
    }
  }

  if (inflight.length === 0) return null;

  return hookResult(
    "warn",
    "§campaign-inflight — items still in_flight at turn end:\n  - " +
      inflight.join("\n  - ") +
      "\n  If YOU finished one: python3 dev/campaigns/manifest.py set-status <ID> done " +
      "(+ note the evidence). If it runs on a peer (fleet-builder), this is expected.",
    MODULE_NAME,
  );
}
