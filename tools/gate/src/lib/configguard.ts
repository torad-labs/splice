// Rules that guard the RULES (#924 Phase 0.5, eli C2; checks/config-guard.sh until PR 5). Once
// inline @Suppress is walled, the generator's next drift move is to weaken the CONFIG instead of
// the code: add a detekt baseline, raise maxIssues, drop warningsAsErrors, downgrade a wall's
// severity, or widen the Dependabot ignore glob to swallow kotlinx. The config surface is a checked
// boundary, not a soft one. Five guards, each failing by name:
//   1. no detekt baseline — a baseline.xml silently whitelists every finding present when created;
//   2. the zero-tolerance posture (maxIssues 0, warningsAsErrors true) intact;
//   3. every ast-grep rule document a blocking error (structure-derived, see ruledocs.ts);
//   4. the Dependabot Kotlin ignore block scoped to the toolchain, not kotlinx (dependabot.ts —
//      in-process since PR 6; it was checks/config/dependabot-kotlin-scope.ts run as a subprocess).
//      The fifth guard, the concentration leg's routing, retired with the checker it routed:
//      ConcentrationLawTest runs inside :quality-architecture:test, which gateOfRecord carries as a
//      Gradle task rather than a ladder row (restructure PR 6).
import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { dependabotScopeProblems } from "./dependabot.ts";
import { severityViolations } from "./ruledocs.ts";

const DETEKT = "quality/detekt/detekt.yml";
const DEPENDABOT = ".github/dependabot.yml";
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
  const dependabotPath = join(root, DEPENDABOT);
  const dependabot = existsSync(dependabotPath) ? readFileSync(dependabotPath, "utf8") : null;
  if (dependabot === null) problems.push(`${DEPENDABOT} is missing — the Kotlin scope cannot be checked`);
  else for (const v of dependabotScopeProblems(dependabot)) problems.push(`dependabot-kotlin-scope: ${v}`);
  return problems;
}
