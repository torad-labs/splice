/** Inject campaign laws through the shared SessionStart module runner.
 *
 *  DEPENDENCY, stated because it is not this file's to fix: the laws text comes from
 *  `.dev/campaigns/manifest.py laws`, and that CLI is still Python (burn-down row V4-143). This
 *  module therefore still spawns an interpreter, and it stays in the no-python invoker census for
 *  that reason — converting the module does not convert the tool it calls. When V4-143 lands, this
 *  spawn becomes `bun .dev/campaigns/manifest.ts laws` and the census entry goes with it.
 */
import { existsSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { join } from "node:path";

import { HookResult, hookResult } from "../../orchestrator/result";
import { findProjectRoot } from "../../lib/astgrep_gate";

export const MODULE_NAME = "10_laws";

type HookEvent = Record<string, unknown>;

export function applies(_data: HookEvent): boolean {
  return true;
}

export function run(data: HookEvent): HookResult | null {
  const projectRoot = findProjectRoot(
    (data.cwd as string | undefined) ?? (data.project_dir as string | undefined) ?? ".",
  );
  if (projectRoot === null) return null;
  const manifest = join(projectRoot, ".dev/campaigns/manifest.py");
  if (!existsSync(manifest)) return null;

  const result = spawnSync("python3", [manifest, "laws"], {
    cwd: projectRoot,
    encoding: "utf8",
    maxBuffer: 64 * 1024 * 1024,
  });
  const payload = (result.stdout || "").trim();
  if (result.status !== 0 || !payload) return null;
  return hookResult("inject", payload, MODULE_NAME);
}
