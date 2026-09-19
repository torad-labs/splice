/** Inject every campaign's laws through the shared SessionStart module runner.
 *
 *  The text is `bun dev/campaigns/manifest.ts laws`: with no ledger path the CLI prints each
 *  dev/campaigns/*.toml's laws, in ledger-name order, each law once (V4-143 D2).
 *
 *  A FAILURE IS SAID OUT LOUD, NEVER RETURNED AS NOTHING. This module used to return null when the
 *  CLI file was missing, and its only other guard was "non-zero exit or empty output" — so deleting
 *  the CLI stopped every seat's laws with no error anywhere, and a CLI that answered with its usage
 *  text at exit 0 (measured on 2026-09-18: 75 lines of help for a pathless `laws`) would have been
 *  injected into every seat AS the laws. Now each failure injects one line naming it, so a seat
 *  starts knowing its laws are missing rather than believing it has them.
 *
 *  THE PAYLOAD IS CHECKED FOR SHAPE: every non-blank line must be a `# LAW` line. That is the cheap
 *  predicate that stops the next wrong payload, not only the one already found.
 */
import { existsSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { join } from "node:path";

import { HookResult, hookResult } from "../../orchestrator/result";
import { findProjectRoot } from "../../lib/astgrep_gate";

export const MODULE_NAME = "10_laws";

const CLI = "dev/campaigns/manifest.ts";

type HookEvent = Record<string, unknown>;

export function applies(_data: HookEvent): boolean {
  return true;
}

const notLoaded = (why: string): HookResult =>
  hookResult(
    "inject",
    `CAMPAIGN LAWS NOT LOADED — \`bun ${CLI} laws\` ${why}. Tell the orchestrator; do not work a campaign row without its laws.`,
    MODULE_NAME,
  );

export function run(data: HookEvent): HookResult | null {
  const projectRoot = findProjectRoot(
    (data.cwd as string | undefined) ?? (data.project_dir as string | undefined) ?? ".",
  );
  if (projectRoot === null) return null; // not this repo: there are no campaign laws to miss
  const cli = join(projectRoot, CLI);
  if (!existsSync(cli)) return notLoaded(`cannot run: ${CLI} does not exist`);

  const result = spawnSync(process.execPath, [cli, "laws"], {
    cwd: projectRoot,
    encoding: "utf8",
    maxBuffer: 64 * 1024 * 1024,
  });
  if (result.error) return notLoaded(`could not start: ${result.error.message}`);
  if (result.status !== 0) {
    const said = (result.stderr || "").trim().split("\n")[0] ?? "";
    return notLoaded(`exited ${result.status}${said ? `: ${said.slice(0, 200)}` : ""}`);
  }
  const payload = (result.stdout || "").trim();
  if (!payload) return notLoaded("printed no laws");
  const stray = payload.split("\n").filter((line) => line.trim() !== "" && !line.startsWith("# LAW"));
  if (stray.length > 0) {
    return notLoaded(
      `printed ${stray.length} line(s) that are not laws, the first: ${JSON.stringify(stray[0].slice(0, 120))}`,
    );
  }
  return hookResult("inject", payload, MODULE_NAME);
}
