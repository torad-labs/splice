// `gate rules [--prove-coverage]` — the ast-grep walls, the proof that they are all routed, and the
// rules that guard the rules.
//
// Four legs, in order, with `&&` semantics:
//   1. `ast-grep scan` over the tree and 2. `ast-grep test --skip-snapshot-tests` — the two
//      invocations `npm run gate:rules` used to spell (package.json:15); that script is now this
//      verb. (A third leg once ran the dormant .rules/kotlin pack's own cases; it held 0 rule-tests
//      and PR 1 deleted both.)
//   3. rule ROUTING (src/lib/routing.ts): the walls leg proves the routed rules pass; it cannot
//      prove they are ALL routed. Completeness, not conformance.
//   4. the CONFIG GUARD (src/lib/configguard.ts): the surface a generator weakens next when the
//      code is walled — detekt posture, rule severities, the dependabot scope, the concentration
//      leg's routing.
// `--prove-coverage` adds P1: `ast-grep scan` reports matches, so it is structurally blind to a
// glob that selects nothing, or one that still matches one module while the others lost
// enforcement. See src/lib/coverage.ts.
import { join } from "node:path";
import { existsSync } from "node:fs";
import { runAstGrep } from "../lib/astgrep.ts";
import { configGuardProblems } from "../lib/configguard.ts";
import { proveCoverage } from "../lib/coverage.ts";
import { layout } from "../lib/repo.ts";
import { routingProblems } from "../lib/routing.ts";

export const usage = "rules [--prove-coverage]             ast-grep walls + rule routing + config guard (+ P1 coverage)";

/** The ast-grep config the walls run against — implicit, because `ast-grep scan` with no --config
 *  walks up to it. It stays at the repository root: ruleDirs and every files:/ignores: glob
 *  resolve relative to ITS directory. */
export const ROUTED_CONFIG = "sgconfig.yml";
export const EXCLUSIONS = "tools/gate/config/rule-coverage-exclusions.toml";

const LEGS: readonly (readonly string[])[] = [
  ["scan"],
  ["test", "--skip-snapshot-tests"],
];

export async function rules(argv: readonly string[]): Promise<number> {
  let prove = false;
  for (const arg of argv) {
    if (arg === "--prove-coverage") prove = true;
    else {
      console.error(`gate rules: unknown argument ${arg}`);
      return 2;
    }
  }

  const { repoRoot, buildRoot } = layout();
  // A config that is not there would make both legs a silent no-op. Name it instead.
  if (!existsSync(join(repoRoot, ROUTED_CONFIG))) {
    console.error(`gate rules: ${ROUTED_CONFIG} is missing — a leg with no config passes without checking anything`);
    return 2;
  }

  for (const leg of LEGS) {
    const code = runAstGrep(repoRoot, leg);
    if (code !== 0) return code;
  }

  const routing = routingProblems({ repoRoot, sgconfigPath: join(repoRoot, ROUTED_CONFIG) });
  if (routing.length) {
    for (const p of routing) console.error(`  ✗ ${p}`);
    console.error("rule-routing: FAIL");
    return 1;
  }
  console.log("rule-routing: PASS");

  const guard = configGuardProblems(repoRoot);
  if (guard.length) {
    for (const p of guard) console.error(p.startsWith("  ✗") ? p : `  ✗ ${p}`);
    console.error("config-guard: FAIL");
    return 1;
  }
  console.log("config-guard: PASS");

  if (!prove) return 0;

  const report = await proveCoverage({
    repoRoot,
    buildRoot,
    sgconfigPath: join(repoRoot, ROUTED_CONFIG),
    exclusionsPath: join(repoRoot, EXCLUSIONS),
  });
  console.log(
    `\ncoverage: ${report.rules} routed rules over ${report.units} source roots ` +
      `(${report.modules} gradle modules + ${report.surfaces} workspace surface) — ` +
      `${report.coveredPairs} rule×source-root pairs covered, ${report.exclusions} dated exclusions`,
  );
  console.log(
    `cross-check: ${report.crossCheckAgreed}/${report.crossChecked} rules' files:/ignores: agree with ast-grep's own selection`,
  );
  if (report.ok) {
    console.log("OK — every routed rule reaches every expected source root, or a dated row says why.");
    return 0;
  }
  for (const finding of report.findings) console.error(`  ${finding.kind}: ${finding.message}`);
  console.error(`\nFAILED — ${report.findings.length} coverage finding(s).`);
  return 1;
}
