/** §manifest-single-channel — campaign ledgers are updated via the ledger CLI, never raw-edited.
 *
 *  Concept #945 §1b: .dev/campaigns/manifest.ts is the ONE sanctioned channel for
 *  touching .dev/campaigns/*.toml. Raw Edit/Write on an existing manifest collides
 *  with concurrent agents (the CLI takes an flock), risks stripping the # comment
 *  notes where decisions and resume pointers live, and dodges the tomllib
 *  well-formedness gate that rolls back broken writes.
 *
 *  Creating a NEW campaign file via Write is allowed (that's how campaigns start);
 *  everything after birth goes through the CLI. Style/discipline gate — fail-open.
 *
 *  SCOPE IS THE LEDGERS, NOT THE SUBTREE (narrowed 2026-07-27, review round 2): this used to match
 *  any .toml anywhere under .dev/campaigns/, which caught a campaign's own ASSETS — the wall census
 *  (proxy-hardening/walls/wall_registry.toml) and the oracle's expectations.toml. Those are not
 *  ledgers: they carry no [[items]], the CLI has no verb that can touch them, and the flock and
 *  tomllib-rollback rationale above does not apply to them. So the guard blocked the only channel
 *  they have while offering a CLI alternative that does not exist — a wall with no door, which is
 *  how `ast-grep-ignore` habits start. A ledger is a DIRECT child of .dev/campaigns/, which is
 *  exactly the set the CLI operates on.
 */
import { existsSync } from "node:fs";
import { resolve as pathResolve } from "node:path";

import { HookResult, hookResult } from "../../orchestrator/result";
import { filePathOf, isWriteOrEdit } from "../../lib/tool_input";
import { findProjectRoot } from "../../lib/project_root";

export const MODULE_NAME = "08_manifest_single_channel";

type HookEvent = Record<string, unknown>;

export function applies(data: HookEvent): boolean {
  return isWriteOrEdit(data) && filePathOf(data).endsWith(".toml");
}

export function run(data: HookEvent): HookResult | null {
  const filePath = filePathOf(data);
  const root = findProjectRoot(filePath, data.cwd as string | undefined);
  if (root === null) return null;

  const abs = pathResolve(filePath.startsWith("/") ? filePath : `${root}/${filePath}`);
  const rootResolved = pathResolve(root);
  if (abs !== rootResolved && !abs.startsWith(rootResolved + "/")) return null;
  const rel = abs === rootResolved ? "" : abs.slice(rootResolved.length + 1);

  // A ledger is a DIRECT child of .dev/campaigns/ — campaign assets nested below it are not.
  const parent = rel.split("/").slice(0, -1).join("/");
  if (parent !== ".dev/campaigns") return null;

  if (data.tool_name === "Write" && !existsSync(abs)) {
    return null; // birth of a new campaign manifest — allowed
  }

  return hookResult(
    "block",
    "§manifest-single-channel — raw edit of a campaign ledger blocked\n\n" +
      `  target: ${rel}\n\n` +
      "The manifest is updated ONLY via the CLI (#945 §1b):\n" +
      "  bun .dev/campaigns/manifest.ts get <ID> | note <ID> \"text\" | " +
      "set-status <ID> <todo|in_flight|done|verified> | add --id ... \n\n" +
      "Raw edits collide with concurrent agents (the CLI flocks), can strip the " +
      "# comment notes that carry decisions/resume pointers, and skip the tomllib " +
      "rollback gate. If you need an operation the CLI lacks, extend the CLI " +
      "(that file is enforcement-layer, grant-gated) — do not hand-edit the ledger.",
    MODULE_NAME,
  );
}
