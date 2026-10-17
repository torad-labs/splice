// One declaration per guarded literal. A single-source rule exempts the place its literal is declared by shape,
// `not: inside: matches: single-source-declaration` (quality/rules/utils), instead of ignoring that file by path. The
// exemption proves nothing about how many declarations exist, so this leg reruns each such rule with the exemption
// turned into the match, which finds every declaration of the guarded literal, and fails when one literal is declared
// more than once. A second `const val` of the same name then fails the proof even though every use is routed.
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { astGrepBin } from "./astgrep.ts";
import { readRules, readSgConfig } from "./rules.ts";

const UTIL = "single-source-declaration";

type Node = Record<string, unknown>;

const isNode = (value: unknown): value is Node => typeof value === "object" && value !== null && !Array.isArray(value);

const namesUtil = (node: unknown): boolean => isNode(node) && isNode(node.inside) && node.inside.matches === UTIL;

/** [rule] with every `not: { inside: { matches: single-source-declaration } }` turned into its `inside` match. */
export function declarationsOnly(rule: unknown): unknown {
  if (Array.isArray(rule)) return rule.map(declarationsOnly);
  if (!isNode(rule)) return rule;
  if (namesUtil(rule.not)) return rule.not;
  return Object.fromEntries(Object.entries(rule).map(([key, value]) => [key, declarationsOnly(value)]));
}

interface Hit {
  readonly file: string;
  readonly line: number;
  readonly text: string;
}

function declarationsOf(repoRoot: string, ruleDoc: Node, utilDoc: Node): Hit[] {
  const dir = mkdtempSync(join(tmpdir(), "gate-single-source-"));
  try {
    const file = join(dir, "declarations.yml");
    const variant = { ...ruleDoc, rule: declarationsOnly(ruleDoc.rule), utils: { [UTIL]: utilDoc.rule } };
    writeFileSync(file, Bun.YAML.stringify(variant, null, 2));
    const proc = Bun.spawnSync([astGrepBin(repoRoot), "scan", "--rule", file, "--json=compact", "."], {
      cwd: repoRoot,
      stdout: "pipe",
      stderr: "pipe",
    });
    if (proc.exitCode !== 0 && proc.exitCode !== 1) {
      throw new Error(`gate: ast-grep could not rerun ${String(ruleDoc.id)} (exit ${proc.exitCode}): ${proc.stderr.toString().trim()}`);
    }
    const hits = JSON.parse(proc.stdout.toString() || "[]") as { file: string; range: { start: { line: number } }; text: string }[];
    return hits.map((h) => ({ file: h.file, line: h.range.start.line + 1, text: h.text }));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

/** One finding per guarded literal that more than one declaration spells, naming each declaration. */
export function singleSourceProblems(repoRoot: string, sgconfigPath: string): string[] {
  const config = readSgConfig(sgconfigPath);
  const util = Bun.YAML.parse(readFileSync(join(repoRoot, "quality/rules/utils", `${UTIL}.yml`), "utf8")) as Node;
  const problems: string[] = [];
  for (const rule of readRules(config.ruleDirs)) {
    const doc = Bun.YAML.parse(readFileSync(rule.path, "utf8")) as Node;
    if (!JSON.stringify(doc.rule).includes(`"${UTIL}"`)) continue;
    const byLiteral = new Map<string, Hit[]>();
    for (const hit of declarationsOf(repoRoot, doc, util)) {
      byLiteral.set(hit.text, [...(byLiteral.get(hit.text) ?? []), hit]);
    }
    for (const [literal, hits] of byLiteral) {
      if (hits.length < 2) continue;
      const where = hits.map((h) => `${h.file}:${h.line}`).sort().join(", ");
      problems.push(`${rule.id}: ${literal} is declared ${hits.length} times (${where}); a single-source literal has one declaration`);
    }
  }
  return problems;
}
