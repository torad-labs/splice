// `gate rules` — the ast-grep walls, the proof that they are all routed, and the
// rules that guard the rules. `gate rules --stdin pretooluse` is the SAME walls at write time: the
// hook event arrives on stdin and the decision leaves on stdout (src/lib/hook.ts;
// .claude/settings.json routes PreToolUse to it).
//
// Five legs, in order, with `&&` semantics:
//   1. `ast-grep scan` over the tree and 2. `ast-grep test --skip-snapshot-tests` — the two
//      invocations `npm run gate:rules` used to spell (package.json:15); that script is now this
//      verb. (A third leg once ran the dormant .rules/kotlin pack's own cases; it held 0 rule-tests
//      and PR 1 deleted both.)
//   3. rule ROUTING (src/lib/routing.ts): the walls leg proves the routed rules pass; it cannot
//      prove they are ALL routed. Completeness, not conformance.
//   4. SINGLE-SOURCE (src/lib/single-source.ts): a guarded literal has one declaration, not two.
//   5. the CONFIG GUARD (src/lib/configguard.ts): the surface a generator weakens next when the
//      code is walled — the detekt posture, the rule severities, and the Dependabot Kotlin scope.
import { join } from "node:path";
import { existsSync, readFileSync } from "node:fs";
import { runAstGrep } from "../lib/astgrep.ts";
import { configGuardProblems } from "../lib/configguard.ts";
import { LIFECYCLES, hook, isLifecycle } from "../lib/hook.ts";
import { layout } from "../lib/repo.ts";
import { routingProblems } from "../lib/routing.ts";
import { singleSourceProblems } from "../lib/single-source.ts";

export const usage =
  "rules [--tests]                      ast-grep walls + rule routing + config guard (--tests: the rules' own cases only)\n" +
  "  rules --stdin pretooluse             the same walls over a hook event on stdin (the Claude Code hook)";

/** The ast-grep config the walls run against — implicit, because `ast-grep scan` with no --config
 *  walks up to it. It stays at the repository root: ruleDirs and every files:/ignores: glob
 *  resolve relative to ITS directory. */
export const ROUTED_CONFIG = "sgconfig.yml";

const LEGS: readonly (readonly string[])[] = [
  ["scan"],
  ["test", "--skip-snapshot-tests"],
];

export async function rules(argv: readonly string[]): Promise<number> {
  if (argv[0] === "--stdin") {
    const lifecycle = argv[1];
    if (argv.length !== 2 || !isLifecycle(lifecycle)) {
      console.error(`gate rules --stdin: expected exactly one lifecycle, one of ${LIFECYCLES.join(", ")}`);
      return 2;
    }
    return hook(readFileSync(0, "utf8"));
  }

  // `--tests` is the pre-commit form: a commit that changes a rule runs the rules' own cases and nothing else. The scan over
  // the whole tree, the routing, the single-source and the config guard judge the pushed commit, in pre-push's throwaway tree.
  const testsOnly = argv.length === 1 && argv[0] === "--tests";
  if (argv.length > 0 && !testsOnly) {
    console.error(`gate rules: unknown argument ${argv[0]}`);
    return 2;
  }

  const { repoRoot } = layout();
  // A config that is not there would make both legs a silent no-op. Name it instead.
  if (!existsSync(join(repoRoot, ROUTED_CONFIG))) {
    console.error(`gate rules: ${ROUTED_CONFIG} is missing — a leg with no config passes without checking anything`);
    return 2;
  }

  for (const leg of testsOnly ? LEGS.filter((leg) => leg[0] === "test") : LEGS) {
    const code = runAstGrep(repoRoot, leg);
    if (code !== 0) return code;
  }
  if (testsOnly) return 0;

  const routing = routingProblems({ repoRoot, sgconfigPath: join(repoRoot, ROUTED_CONFIG) });
  if (routing.length) {
    for (const p of routing) console.error(`  ✗ ${p}`);
    console.error("rule-routing: FAIL");
    return 1;
  }
  console.log("rule-routing: PASS");

  const duplicates = singleSourceProblems(repoRoot, join(repoRoot, ROUTED_CONFIG));
  if (duplicates.length) {
    for (const p of duplicates) console.error(`  ✗ ${p}`);
    console.error("single-source: FAIL");
    return 1;
  }
  console.log("single-source: PASS");

  const guard = configGuardProblems(repoRoot);
  if (guard.length) {
    for (const p of guard) console.error(p.startsWith("  ✗") ? p : `  ✗ ${p}`);
    console.error("config-guard: FAIL");
    return 1;
  }
  console.log("config-guard: PASS");
  return 0;
}
