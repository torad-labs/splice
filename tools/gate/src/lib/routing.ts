// The wall against a wall that is PRESENT but wired to nothing (HD-11; checks/rule-routing.sh
// until PR 5).
//
// On 2026-07-16 this repo installed a Kotlin rule pack at .rules/kotlin/ and the same day disabled
// the hook that ran it, "superseded by sgconfig route" — a route wired to a DIFFERENT directory.
// ast-grep does not error on a rule directory nobody references; it simply never reads it, so a
// whole rule set sat in the tree producing zero findings for a month while 336 top-level functions
// and 58 companion objects accumulated under a green gate.
//
// A green wall proves nothing unless something reads it. Both directions:
//   forward — every quality/rules/ directory holding ast-grep rule files is REFERENCED by
//             sgconfig.yml (ruleDirs: or testConfigs.testDir:) or carries a dated allowlist entry;
//   inverse — every ruleDirs: entry EXISTS and holds at least one rule file (a typo'd or emptied
//             ruleDir is the same fail-open bug seen from the other side).
// testConfigs.testDir counts as a reference because it IS one: `ast-grep test` reads those files
// every gate run. Prefix, not exact match — MEASURED (2026-08-16, ast-grep 0.45.0): a rule file in
// a SUBdirectory of a routed ruleDir is read.
import { existsSync, readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { ruleFiles } from "./ruledocs.ts";
import { readSgConfig } from "./rules.ts";

/** Directories deliberately NOT routed: `<path>|<YYYY-MM-DD>: <reason>`. An entry covers the path
 *  and everything beneath it. The date is REQUIRED and mechanically checked — an undated exemption
 *  is how the next dormant pack hides. An entry whose path no longer exists is a hard failure, not
 *  a no-op: a stale line is exactly the "referenced by nothing" state, one level up. */
export const UNROUTED_ALLOWLIST: readonly string[] = [];

export interface RoutingInput {
  readonly repoRoot: string;
  readonly sgconfigPath: string;
  /** the rule tree, repo-relative */
  readonly rulesRoot?: string;
  readonly allowlist?: readonly string[];
}

const trimSlash = (p: string) => p.replace(/\/+$/, "");

/** true if dir IS a prefix or lives beneath one */
export function coveredBy(dir: string, prefixes: readonly string[]): boolean {
  return prefixes.some((p) => p !== "" && (dir === p || dir.startsWith(`${p}/`)));
}

function directoriesUnder(root: string): string[] {
  const out: string[] = [];
  const visit = (dir: string): void => {
    out.push(dir);
    let entries: string[];
    try {
      entries = readdirSync(dir).sort();
    } catch {
      return;
    }
    for (const name of entries) {
      const p = join(dir, name);
      try {
        if (statSync(p).isDirectory()) visit(p);
      } catch {
        /* a vanished entry is not a directory */
      }
    }
  };
  if (existsSync(root)) visit(root);
  return out.sort();
}

/** Every routing problem, by name. Empty means both directions hold. */
export function routingProblems(input: RoutingInput): string[] {
  const { repoRoot, sgconfigPath } = input;
  const rulesRoot = input.rulesRoot ?? "quality/rules";
  const allowlist = input.allowlist ?? UNROUTED_ALLOWLIST;
  const sgconfig = relative(repoRoot, sgconfigPath) || sgconfigPath;
  const problems: string[] = [];
  const config = readSgConfig(sgconfigPath);
  const ruleDirs = config.ruleDirs.map((d) => trimSlash(relative(repoRoot, d)));
  const referenced = [...ruleDirs, ...config.testDirs.map((d) => trimSlash(relative(repoRoot, d)))];
  const allowedPaths = allowlist.map((entry) => entry.split("|")[0]!);

  if (ruleDirs.length === 0) problems.push(`${sgconfig} declares no ruleDirs — every ast-grep wall in this repo is dormant.`);

  // --- forward: nothing under the rule tree may be present-but-unreferenced -----------------
  for (const abs of directoriesUnder(join(repoRoot, rulesRoot))) {
    const dir = relative(repoRoot, abs);
    const count = ruleFiles(abs, 1).length;
    if (count === 0) continue;
    if (coveredBy(dir, referenced) || coveredBy(dir, allowedPaths)) continue;
    problems.push(
      `${dir} holds ${count} ast-grep rule file(s) but nothing references it — add it to ruleDirs: in ${sgconfig}, ` +
        "or give it a dated entry in UNROUTED_ALLOWLIST (tools/gate/src/lib/routing.ts). ast-grep never errors on an " +
        "unreferenced rule directory; it silently scans none of those rules, which is exactly how .rules/kotlin ran " +
        "dormant for a month.",
    );
  }

  // --- inverse: every routed directory must exist and actually carry rules ------------------
  for (const dir of ruleDirs) {
    const abs = join(repoRoot, dir);
    if (!existsSync(abs) || !statSync(abs).isDirectory()) {
      problems.push(`${sgconfig} ruleDirs lists '${dir}', which does not exist — ast-grep reads nothing there and still exits 0. Fix the path or drop the entry.`);
      continue;
    }
    if (ruleFiles(abs, 99).length === 0) {
      problems.push(`${sgconfig} ruleDirs lists '${dir}' but it holds 0 ast-grep rule files — an emptied ruleDir is a wall that passes by having nothing to say.`);
    }
  }

  // --- the allowlist itself is checked: dated, and never stale -------------------------------
  for (const entry of allowlist) {
    const bar = entry.indexOf("|");
    const path = bar === -1 ? entry : entry.slice(0, bar);
    const reason = bar === -1 ? "" : entry.slice(bar + 1);
    if (!existsSync(join(repoRoot, path))) {
      problems.push(`UNROUTED_ALLOWLIST names '${path}', which no longer exists — delete the entry (a stale exemption is an unread rule directory one level up).`);
    }
    if (!/^[0-9]{4}-[0-9]{2}-[0-9]{2}: ./.test(reason)) {
      problems.push(`UNROUTED_ALLOWLIST entry for '${path}' has no dated reason — every exemption starts 'YYYY-MM-DD: <why>'.`);
    }
  }
  return problems;
}
