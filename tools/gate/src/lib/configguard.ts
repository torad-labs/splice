// Rules that guard the RULES (#924 Phase 0.5, eli C2; checks/config-guard.sh until PR 5). Once
// inline @Suppress is walled, the generator's next drift move is to weaken the CONFIG instead of
// the code: add a detekt baseline, raise maxIssues, drop warningsAsErrors, downgrade a wall's
// severity, or defang the concentration leg in package.json. The config surface is a checked
// boundary, not a soft one. Five guards, each failing by name:
//   1. no detekt baseline — a baseline.xml silently whitelists every finding present when created;
//   2. the zero-tolerance posture (maxIssues 0, warningsAsErrors true) intact;
//   3. every ast-grep rule document a blocking error (structure-derived, see ruledocs.ts);
//   4. the Dependabot Kotlin ignore block scoped to the toolchain, not kotlinx
//      (checks/config/dependabot-kotlin-scope.ts, run as it is until PR 6 moves it into build-logic).
//      It runs with cwd = root, because it resolves its inputs from there and its test arms mirror
//      the tree. The fifth guard, the concentration leg's routing, retired with the checker it
//      routed: ConcentrationLawTest runs inside :quality-architecture:test, which gateOfRecord
//      carries as a Gradle task rather than a ladder row (restructure PR 6).
import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { severityViolations } from "./ruledocs.ts";

const DETEKT = "quality/detekt/detekt.yml";
const SKIP = new Set(["node_modules", ".git", "build", ".gradle", "dist"]);
const BASELINE_NAMES = new Set(["detekt-baseline.xml", "baseline.xml"]);

function baselineFiles(root: string): string[] {
  const out: string[] = [];
  const visit = (dir: string): void => {
    let entries: string[];
    try {
      entries = readdirSync(dir);
    } catch {
      return;
    }
    for (const name of entries) {
      if (SKIP.has(name)) continue;
      const p = join(dir, name);
      let st;
      try {
        st = statSync(p);
      } catch {
        continue;
      }
      if (st.isDirectory()) visit(p);
      else if (BASELINE_NAMES.has(name)) out.push(relative(root, p));
    }
  };
  visit(root);
  return out.sort();
}

/** A checker that still lives in checks/config, run exactly as the shell leg ran it; its output is
 *  part of the problem text so a failure keeps its own diagnosis (the test arms pin phrases in it). */
function subprocess(root: string, label: string, script: string): string[] {
  const proc = Bun.spawnSync(["bun", script], { cwd: root, stdout: "pipe", stderr: "pipe" });
  if (proc.exitCode === 0) return [];
  const output = (proc.stdout.toString() + proc.stderr.toString()).trim();
  return [`${label} failed (exit ${proc.exitCode}):\n${output}`];
}

export function configGuardProblems(root: string): string[] {
  const problems: string[] = [];
  const detektPath = join(root, DETEKT);
  const detekt = existsSync(detektPath) ? readFileSync(detektPath, "utf8") : "";
  if (!detekt) problems.push(`${DETEKT} is missing — the detekt posture cannot be checked`);
  if (/^\s*baseline\s*:/m.test(detekt)) problems.push("detekt.yml declares a baseline — remove it (it suppresses existing findings)");
  for (const f of baselineFiles(root)) problems.push(`a detekt baseline.xml exists (${f}) — delete it (findings must be fixed, not whitelisted)`);
  if (!/maxIssues:\s*0/.test(detekt)) problems.push("detekt.yml build.maxIssues must be 0");
  if (!/warningsAsErrors:\s*true/.test(detekt)) problems.push("detekt.yml config.warningsAsErrors must be true");
  for (const v of severityViolations(root)) problems.push(`  ✗ ${v}`);
  problems.push(...subprocess(root, "dependabot-kotlin-scope", "checks/config/dependabot-kotlin-scope.ts"));
  return problems;
}
