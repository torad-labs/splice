// `gate rules [--prove-coverage]` — the ast-grep walls, and the proof that they are all routed.
//
// The two legs below reproduce `npm run gate:rules` (package.json:15) exactly, in order, with
// `&&` semantics: scan the tree, then run the routed rules' red/green cases. (A third leg used to
// run the dormant .rules/kotlin pack's own cases through its own config; that pack held 0
// rule-tests, so the leg tested nothing, and PR 1 of the restructure deleted both.)
//
// `--prove-coverage` adds P1: `ast-grep scan` reports matches, so it is structurally blind to a
// glob that selects nothing, or one that still matches one module while the others lost
// enforcement. See src/lib/coverage.ts.
import { join } from "node:path";
import { existsSync } from "node:fs";
import { runAstGrep } from "../lib/astgrep.ts";
import { proveCoverage } from "../lib/coverage.ts";
import { layout } from "../lib/repo.ts";

export const usage = "rules [--prove-coverage]             the ast-grep walls (= npm run gate:rules), + P1 coverage";

/** The ast-grep config `gate:rules` runs against — implicit in the npm script, because `ast-grep
 *  scan` with no --config walks up to it. It stays at the repository root: ruleDirs and every
 *  files:/ignores: glob resolve relative to ITS directory. */
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
