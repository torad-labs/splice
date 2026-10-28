import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { gunzipSync } from "node:zlib";
import { resolve } from "node:path";
import { currentHistoryFindings, frozenCurrentHistory, historySnapshotFindings, historySuccessors } from "./current-history";
import type { CurrentInventory } from "./current-inventory";

describe("current source refresh obligation retention", () => {
  const original = gunzipSync(readFileSync(resolve(import.meta.dir, frozenCurrentHistory.inventory.path)));
  const dispositions = gunzipSync(readFileSync(resolve(import.meta.dir, frozenCurrentHistory.dispositions.path)));
  test("the immutable original census and unresolved dispositions remain available", async () => {
    expect(await currentHistoryFindings()).toEqual([]);
  });
  test("removing even one original obligation is rejected", () => {
    const smaller = JSON.parse(original.toString()); smaller.units.pop();
    expect(historySnapshotFindings(Buffer.from(JSON.stringify(smaller)), dispositions)).toContain("frozen 14,615-unit source census was changed or shrunk");
  });
  test("changing an unresolved original disposition cannot erase the remaining work", () => {
    const rewritten = JSON.parse(dispositions.toString()); rewritten.units.find((unit: any) => unit.disposition === "unsupported").disposition = "context";
    expect(historySnapshotFindings(original, Buffer.from(JSON.stringify(rewritten)))).toContain("frozen current-reference dispositions were changed; unresolved history cannot be erased by a source refresh");
  });
  test("an upstream offset move gets a successor while deleted IDs stay in the history", () => {
    const previous = { units: [{ id: "file#function_item@10", file: "file", kind: "function_item", label: "operation", sha256: "original" }, { id: "file#function_item@20", file: "file", kind: "function_item", label: "removed", sha256: "deleted" }] } as CurrentInventory;
    const current = { units: [{ id: "file#function_item@12", file: "file", kind: "function_item", label: "operation", sha256: "original" }] } as CurrentInventory;
    expect(historySuccessors(previous, current)).toEqual([
      { id: "file#function_item@10", sha256: "original", successors: [{ id: "file#function_item@12", sha256: "original" }], relation: "identical-source-bytes" },
      { id: "file#function_item@20", sha256: "deleted", successors: [], relation: "upstream-removed-or-moved-requires-review" },
    ]);
  });
});
