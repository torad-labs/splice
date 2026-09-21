// Reading the ast-grep rule set the same way ast-grep reads it: sgconfig.yml names the ruleDirs,
// each *.yml in them is one rule, and `files:`/`ignores:` are the path scoping that decides which
// source a rule is allowed to see. Those two lists are what P1's coverage proof grades.
import { readdirSync, readFileSync } from "node:fs";
import { isAbsolute, join, resolve } from "node:path";

export interface Rule {
  readonly id: string;
  readonly language: string;
  readonly severity: string;
  /** path globs, ast-grep semantics, relative to the scan root. Empty means "every file". */
  readonly files: readonly string[];
  readonly ignores: readonly string[];
  /** the file this rule was read from, so every finding can name it */
  readonly path: string;
}

export interface SgConfig {
  readonly path: string;
  readonly ruleDirs: readonly string[];
  readonly testDirs: readonly string[];
}

export function readSgConfig(configPath: string): SgConfig {
  const parsed = Bun.YAML.parse(readFileSync(configPath, "utf8")) as {
    ruleDirs?: string[];
    testConfigs?: { testDir: string }[];
  };
  const base = resolve(configPath, "..");
  const at = (p: string) => (isAbsolute(p) ? p : join(base, p));
  return {
    path: configPath,
    ruleDirs: (parsed.ruleDirs ?? []).map(at),
    testDirs: (parsed.testConfigs ?? []).map((t) => at(t.testDir)),
  };
}

export function readRules(ruleDirs: readonly string[]): Rule[] {
  const rules: Rule[] = [];
  for (const dir of ruleDirs) {
    for (const name of readdirSync(dir).sort()) {
      if (!name.endsWith(".yml") && !name.endsWith(".yaml")) continue;
      const path = join(dir, name);
      const doc = Bun.YAML.parse(readFileSync(path, "utf8")) as Record<string, unknown> | undefined;
      if (!doc || typeof doc !== "object") throw new Error(`gate: ${path} is not a rule document`);
      const id = doc.id;
      const language = doc.language;
      if (typeof id !== "string" || typeof language !== "string") {
        throw new Error(`gate: ${path} has no id/language — ast-grep would refuse it too`);
      }
      rules.push({
        id,
        language,
        severity: typeof doc.severity === "string" ? doc.severity : "error",
        files: asStrings(doc.files, path, "files"),
        ignores: asStrings(doc.ignores, path, "ignores"),
        path,
      });
    }
  }
  const seen = new Map<string, string>();
  for (const rule of rules) {
    const first = seen.get(rule.id);
    if (first) throw new Error(`gate: duplicate rule id ${rule.id} in ${first} and ${rule.path}`);
    seen.set(rule.id, rule.path);
  }
  return rules;
}

function asStrings(value: unknown, path: string, field: string): string[] {
  if (value === undefined || value === null) return [];
  if (!Array.isArray(value) || value.some((v) => typeof v !== "string")) {
    throw new Error(`gate: ${path} has a non-list ${field}:`);
  }
  return value as string[];
}

/**
 * ast-grep's path scoping, applied to one repo-relative path.
 *
 * Verified against ast-grep itself for every routed rule by `rules --prove-coverage`, which runs
 * the real `files:`/`ignores:` through ast-grep with an always-matching probe rule and compares the
 * file sets. A matcher that merely looks right is the failure this proof exists to prevent.
 */
export function selects(rule: Pick<Rule, "files" | "ignores">, relPath: string): boolean {
  const included = rule.files.length === 0 || rule.files.some((g) => globMatch(g, relPath));
  if (!included) return false;
  return !rule.ignores.some((g) => globMatch(g, relPath));
}

const globCache = new Map<string, Bun.Glob>();

export function globMatch(pattern: string, relPath: string): boolean {
  let glob = globCache.get(pattern);
  if (!glob) {
    glob = new Bun.Glob(pattern);
    globCache.set(pattern, glob);
  }
  return glob.match(relPath);
}
