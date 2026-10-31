import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { gunzipSync } from "node:zlib";
import type { CurrentInventory } from "./current-inventory";
import { sha256 } from "./qualification";

// A byte offset identifies a declaration only within its immutable upstream revision.
// Refreshing the selected reference retains the entire original census and its
// unresolved dispositions. Historical entries never supply current coverage PASSes.
export const frozenCurrentHistory = {
  sourceRevision: "6aaac694803004a63c6f393d153c96d22521a6b9",
  units: 14615,
  inventory: { path: "current-history/0.164.0.inventory.json.gz", sha256: "c80c199b2b6b2dbdd94191b06d37301a251aebecb44b6812f7da67bd9343eb30", decodedSha256: "6f5ce5ea607d70021bd24baa5646fe7d864f26a100074b9fd5cde4b8b00e106f" },
  dispositions: { path: "current-history/0.164.0.dispositions.json.gz", sha256: "b584962d7f95995bde97c584e42b03c10496add6731a65e9a9e306b7f9bffdf3", decodedSha256: "16ab288bfa04a2fb158e2ac22da56d4796a78e8ef0896f4c1f6a99cad51864a4" },
} as const;

export function historySnapshotFindings(inventoryBytes: Uint8Array, dispositionBytes: Uint8Array): string[] {
  const findings: string[] = [];
  if (sha256(inventoryBytes) !== frozenCurrentHistory.inventory.decodedSha256) findings.push("frozen 14,615-unit source census was changed or shrunk");
  if (sha256(dispositionBytes) !== frozenCurrentHistory.dispositions.decodedSha256) findings.push("frozen current-reference dispositions were changed; unresolved history cannot be erased by a source refresh");
  const inventory = JSON.parse(Buffer.from(inventoryBytes).toString()) as CurrentInventory;
  const dispositions = JSON.parse(Buffer.from(dispositionBytes).toString());
  const recorded = new Map<string, string>(dispositions.units.map((unit: { id: string; sha256: string }) => [unit.id, unit.sha256]));
  if (inventory.sourceRevision !== frozenCurrentHistory.sourceRevision || inventory.units.length !== frozenCurrentHistory.units ||
    new Set(inventory.units.map(unit => unit.id)).size !== frozenCurrentHistory.units) findings.push("frozen source census identity or declaration denominator changed");
  if (dispositions.sourceRevision !== inventory.sourceRevision || dispositions.denominatorSha256 !== inventory.denominatorSha256 ||
    dispositions.units.length !== inventory.units.length || inventory.units.some(unit => recorded.get(unit.id) !== unit.sha256)) findings.push("frozen source obligations no longer retain their complete original dispositions");
  return findings;
}

export async function currentHistoryFindings(): Promise<string[]> {
  try {
    const archives = await Promise.all([frozenCurrentHistory.inventory, frozenCurrentHistory.dispositions].map(async proof => {
      const bytes = await readFile(resolve(import.meta.dir, proof.path));
      if (sha256(bytes) !== proof.sha256) throw new Error(`frozen current-reference history archive changed: ${proof.path}`);
      return gunzipSync(bytes);
    }));
    return historySnapshotFindings(archives[0]!, archives[1]!);
  } catch (error) { return [`frozen current-reference history unavailable: ${error instanceof Error ? error.message : String(error)}`]; }
}

/** A review report, never a disposition or an execution witness. All old IDs remain in the archive. */
export function historySuccessors(previous: CurrentInventory, current: CurrentInventory): Array<{ id: string; sha256: string; successors: Array<{ id: string; sha256: string }>; relation: string }> {
  const index = (key: "sha256" | "label") => {
    const entries = new Map<string, typeof current.units>();
    for (const unit of current.units) {
      const identity = JSON.stringify([unit.file, unit.kind, unit[key]]);
      entries.set(identity, [...entries.get(identity) ?? [], unit]);
    }
    return entries;
  };
  const byBytes = index("sha256"), byName = index("label");
  return previous.units.map(unit => {
    const same = byBytes.get(JSON.stringify([unit.file, unit.kind, unit.sha256])) ?? [];
    const named = byName.get(JSON.stringify([unit.file, unit.kind, unit.label])) ?? [];
    const successors = same.length ? same : named;
    return { id: unit.id, sha256: unit.sha256, successors: successors.map(next => ({ id: next.id, sha256: next.sha256 })),
      relation: same.length ? "identical-source-bytes" : named.length ? "changed-source-requires-review" : "upstream-removed-or-moved-requires-review" };
  });
}
