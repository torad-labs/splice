// `gate module-law` — the module law (gradle/module-law.txt) read straight from the build files, without gradle.
// build-logic's splice.module-law.gradle.kts stays the law's enforcer: it fails gradle's configuration on a forbidden
// project dependency. That failure used to surface only at pre-push, behind the build gate's line: a920eeef1's push
// waited 2,344 s for the build lock and then failed in 15 s (Oct 13, 2026). This leg reads the same table and the same
// main configurations (api, implementation, compileOnly, runtimeOnly) as text, so pre-commit refuses the breach at the
// seat's own commit, in well under the fence's minute. A dependency spelled in a form this reader does not parse still
// meets the gradle enforcer at pre-push.
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../lib/repo.ts";
import { parseModuleGraph } from "../lib/selector.ts";
import { gradleModules } from "./hook.ts";

export const usage = "module-law                          the module law over every build.gradle.kts, read without gradle";

const LAW = "gradle/module-law.txt";
const PROJECT_DEPENDENCY = /\b(api|implementation|compileOnly|runtimeOnly)\s*\(\s*project\(\s*"([^"]+)"\s*\)\s*\)/g;

/** Each forbidden project dependency under [root], as the gradle enforcer words it. */
export function breaches(root: string): string[] {
  const law = parseModuleGraph(readFileSync(join(root, LAW), "utf8"));
  const found: string[] = [];
  for (const module of gradleModules(root)) {
    const allowed = law.get(module.path);
    const script = join(root, module.dir, "build.gradle.kts");
    if (allowed === undefined || !existsSync(script)) continue;
    for (const m of readFileSync(script, "utf8").matchAll(PROJECT_DEPENDENCY)) {
      const dep = m[2] ?? "";
      if (dep === module.path || allowed.includes(dep)) continue;
      found.push(
        `MODULE LAW: ${module.path} may not depend on ${dep} (${m[1]}, ${module.dir}/build.gradle.kts; allowed: ` +
          `[${[...allowed].sort().join(", ")}]). The graph is the architecture: see ${LAW} before touching it.`,
      );
    }
  }
  return found;
}

export function moduleLaw(argv: readonly string[]): number {
  if (argv.length > 0) {
    console.error(`gate module-law: unknown argument(s) ${argv.join(" ")} — it takes none`);
    return 2;
  }
  const found = breaches(layout().repoRoot);
  for (const line of found) console.error(`  ✗ ${line}`);
  if (found.length > 0) {
    console.error(`FAIL: module law — ${found.length} forbidden project dependenc${found.length === 1 ? "y" : "ies"}`);
    return 1;
  }
  console.log("module law: every project dependency is one the law allows");
  return 0;
}
