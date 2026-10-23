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

export type MirrorDefinitionHit = Hit;

export const MIRROR_DEFINITION_RULE = "kt-l2-single-mirror-definition";
export const CANONICAL_MIRROR_FILE = "features/turns/src/main/kotlin/splice/head/reasoning/Mirror.kt";

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

/** The single-mirror exception's candidates, from the same owner shape the ast-grep wall exempts. */
export function mirrorDefinitionsOf(
  repoRoot: string,
  cwd: string,
  targets: readonly string[],
): MirrorDefinitionHit[] {
  return scanMirrorDefinitions(repoRoot, join(repoRoot, "sgconfig.yml"), cwd, targets);
}

/** One canonical package/class exception must have exactly one definition in its actual source file. */
export function mirrorDefinitionProblems(repoRoot: string, sgconfigPath: string): string[] {
  const definitions = scanMirrorDefinitions(repoRoot, sgconfigPath, repoRoot, ["."]);
  if (definitions.length === 1 && definitions[0]!.file.replace(/^\.\//, "") === CANONICAL_MIRROR_FILE) return [];
  const found = definitions.map((definition) => `${definition.file}:${definition.line}`).join(", ") || "none";
  return [`${MIRROR_DEFINITION_RULE}: expected one definition in ${CANONICAL_MIRROR_FILE}; found ${found}`];
}

function scanMirrorDefinitions(
  repoRoot: string,
  sgconfigPath: string,
  cwd: string,
  targets: readonly string[],
): MirrorDefinitionHit[] {
  const config = readSgConfig(sgconfigPath);
  const rule = readRules(config.ruleDirs).find((candidate) => candidate.id === MIRROR_DEFINITION_RULE);
  if (!rule) throw new Error(`gate: missing ${MIRROR_DEFINITION_RULE}`);
  const doc = Bun.YAML.parse(readFileSync(rule.path, "utf8")) as Node;
  const body = doc.rule;
  if (!isNode(body) || !isNode(body.not) || !isNode(body.not.inside)) {
    throw new Error(`gate: ${MIRROR_DEFINITION_RULE} has no owner exception to verify`);
  }
  const declarationRule = {
    ...doc,
    id: `${MIRROR_DEFINITION_RULE}-owner-declarations`,
    rule: {
      ...Object.fromEntries(Object.entries(body).filter(([key]) => key !== "not")),
      inside: body.not.inside,
    },
  };
  const dir = mkdtempSync(join(tmpdir(), "gate-mirror-definitions-"));
  try {
    const file = join(dir, "definitions.yml");
    writeFileSync(file, Bun.YAML.stringify(declarationRule, null, 2));
    const proc = Bun.spawnSync(
      [astGrepBin(repoRoot), "scan", "--rule", file, "--json=compact", ...targets],
      { cwd, stdout: "pipe", stderr: "pipe" },
    );
    if (proc.exitCode !== 0 && proc.exitCode !== 1) {
      throw new Error(`gate: ast-grep could not verify ${MIRROR_DEFINITION_RULE} (exit ${proc.exitCode}): ${proc.stderr.toString().trim()}`);
    }
    const hits = JSON.parse(proc.stdout.toString() || "[]") as {
      file: string;
      range: { start: { line: number } };
      text: string;
    }[];
    return hits.map((hit) => ({
      file: hit.file.replace(/^\.\//, "").replaceAll("\\", "/"),
      line: hit.range.start.line + 1,
      text: hit.text,
    }));
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
