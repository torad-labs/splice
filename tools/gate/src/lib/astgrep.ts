// Running ast-grep, and asking ast-grep itself which files a rule's path scoping selects.
//
// The second half is the point: P1's proof rests on a matcher for `files:`/`ignores:`, and a
// hand-written glob matcher that merely looks right is the same defect as a glob that matches
// nothing — a check that agrees with itself. So every routed rule's real `files:`/`ignores:` are
// handed back to ast-grep under an always-matching probe rule, and the two file sets are compared.
import { existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { Rule } from "./rules.ts";

/** The tree-sitter ROOT node kind per language — an always-match probe, one node per file. */
const ROOT_KIND: Readonly<Record<string, string>> = {
  kotlin: "source_file",
  typescript: "program",
  tsx: "program",
  css: "stylesheet",
};

/** npm puts node_modules/.bin first on PATH; `npm run gate:rules` therefore prefers the pinned CLI. */
export function astGrepBin(repoRoot: string): string {
  const local = join(repoRoot, "node_modules", ".bin", "ast-grep");
  if (existsSync(local)) return local;
  const onPath = Bun.which("ast-grep");
  if (onPath) return onPath;
  throw new Error("gate: ast-grep is not installed (no node_modules/.bin/ast-grep and none on PATH)");
}

export function runAstGrep(repoRoot: string, args: readonly string[]): number {
  const proc = Bun.spawnSync([astGrepBin(repoRoot), ...args], {
    cwd: repoRoot,
    stdio: ["inherit", "inherit", "inherit"],
  });
  return proc.exitCode ?? 1;
}

export interface ProbeResult {
  readonly byRule: ReadonlyMap<string, ReadonlySet<string>>;
  readonly skipped: readonly string[];
}

/**
 * The file set ast-grep selects for each rule's `files:`/`ignores:`, from ONE scan.
 *
 * `--format github` is what makes one scan affordable: it prints one short line per match carrying
 * the file and the rule id, where `--json` would carry every matched node's full text — and the
 * probe matches each file's root node, so the JSON form is the whole tree twice over.
 */
export function probeFileSets(repoRoot: string, rules: readonly Rule[]): ProbeResult {
  const dir = mkdtempSync(join(tmpdir(), "gate-coverage-probe-"));
  try {
    const rulesDir = join(dir, "rules");
    mkdirSync(rulesDir, { recursive: true });
    const byRule = new Map<string, ReadonlySet<string>>();
    const skipped: string[] = [];
    const probed: Rule[] = [];
    for (const rule of rules) {
      const kind = ROOT_KIND[rule.language];
      if (!kind) {
        skipped.push(rule.id);
        continue;
      }
      probed.push(rule);
      byRule.set(rule.id, new Set());
      writeFileSync(
        join(rulesDir, `${rule.id}.yml`),
        Bun.YAML.stringify({
          id: rule.id,
          language: rule.language,
          severity: "error",
          ...(rule.files.length ? { files: [...rule.files] } : {}),
          ...(rule.ignores.length ? { ignores: [...rule.ignores] } : {}),
          rule: { kind },
          message: "coverage probe",
        }),
      );
    }
    writeFileSync(join(dir, "sgconfig.yml"), Bun.YAML.stringify({ ruleDirs: ["rules"] }));
    const proc = Bun.spawnSync([astGrepBin(repoRoot), "scan", "--config", join(dir, "sgconfig.yml"), "--format", "github"], {
      cwd: repoRoot,
    });
    const out = proc.stdout.toString();
    if (proc.exitCode !== 0 && out.length === 0) {
      throw new Error(`gate: the coverage probe scan failed: ${proc.stderr.toString().slice(0, 2000)}`);
    }
    for (const line of out.split("\n")) {
      const m = /^::error file=(.+?),line=\d+,endLine=\d+,title=(.+?)::/.exec(line);
      if (!m) continue;
      (byRule.get(m[2]!) as Set<string> | undefined)?.add(m[1]!);
    }
    if (probed.length > 0 && [...byRule.values()].every((s) => s.size === 0)) {
      throw new Error("gate: the coverage probe matched nothing at all — ast-grep did not run the probe rules");
    }
    return { byRule, skipped };
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}
