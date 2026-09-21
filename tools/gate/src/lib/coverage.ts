// P1 — glob COVERAGE, not liveness.
//
// The hazard (restructure plan §6.1 P1): the architecture rules bind path globs in `files:`, and ast-grep is
// silent about a glob that selects nothing. A glob narrowed from `gateway/*/src/main/**/*.kt` to
// `app/src/main/**/*.kt` still matches everything it names, so `ast-grep scan` stays green
// while fifteen modules lose enforcement. Nothing in a scan can see that, because the only thing a
// scan reports is a match.
//
// So this proof does not read the rule's own glob for its denominator. The expected set comes from
// the SOURCE: the Gradle source roots named by settings.gradle.kts and the workspace surfaces named
// by package.json. Every source root a rule fails to reach must carry a dated row in
// config/rule-coverage-exclusions.toml. And because the whole proof rests on one glob matcher, that
// matcher is cross-checked against ast-grep's own file selection for every routed rule.
import { describe, readExclusions, type Exclusion } from "./exclusions.ts";
import { probeFileSets } from "./astgrep.ts";
import { globMatch, readRules, readSgConfig, selects, type Rule } from "./rules.ts";
import {
  LANGUAGE_EXTENSIONS,
  sourceUnits,
  unitFilesFor,
  workingTreeFiles,
  type SourceUnit,
} from "./sources.ts";

export type FindingKind =
  | "rule-matches-nothing"
  | "source-root-lost"
  | "file-lost"
  | "exclusion-invalid"
  | "exclusion-stale"
  | "cross-check";

export interface Finding {
  readonly kind: FindingKind;
  readonly rule?: string;
  readonly message: string;
}

export interface CoverageReport {
  readonly ok: boolean;
  readonly rules: number;
  readonly units: number;
  readonly modules: number;
  readonly surfaces: number;
  readonly exclusions: number;
  readonly crossChecked: number;
  readonly crossCheckAgreed: number;
  readonly coveredPairs: number;
  readonly findings: readonly Finding[];
}

export interface ProveOptions {
  readonly repoRoot: string;
  readonly buildRoot: string;
  readonly sgconfigPath: string;
  readonly exclusionsPath: string;
  /** off only where ast-grep is genuinely unavailable; the proof says so in the report */
  readonly crossCheck?: boolean;
}

const MAX_NAMED = 5;

export async function proveCoverage(options: ProveOptions): Promise<CoverageReport> {
  const { repoRoot, buildRoot, sgconfigPath, exclusionsPath } = options;
  const config = readSgConfig(sgconfigPath);
  const rules = readRules(config.ruleDirs);
  const files = workingTreeFiles(repoRoot);
  const units = sourceUnits(repoRoot, buildRoot, files);
  const table = await readExclusions(exclusionsPath);

  const findings: Finding[] = table.problems.map((message) => ({ kind: "exclusion-invalid" as const, message }));
  const ruleIds = new Set(rules.map((r) => r.id));
  for (const row of table.rows) {
    for (const id of row.rules) {
      if (!ruleIds.has(id)) {
        findings.push({
          kind: "exclusion-invalid",
          rule: id,
          message: `exclusion ${describe(row)} names "${id}", which no rule in ${config.path} declares`,
        });
      }
    }
  }

  const used = new Set<number>();
  let coveredPairs = 0;

  for (const rule of rules) {
    const relevant = units
      .map((unit) => ({ unit, expected: unitFilesFor(unit, rule.language) }))
      .filter((u) => u.expected.length > 0);
    const rows = table.rows.filter((row) => row.rules.includes(rule.id));
    const unitRows = rows.filter((row) => row.files.length === 0);
    const fileRows = rows.filter((row) => row.files.length > 0);

    const matchedTotal = relevant.reduce((n, u) => n + u.expected.filter((f) => selects(rule, f)).length, 0);
    if (matchedTotal === 0) {
      findings.push({
        kind: "rule-matches-nothing",
        rule: rule.id,
        message:
          `${rule.id} matches NO file in the expected source set — its files: ${rule.files.join(", ")} ` +
          `select nothing across ${relevant.length} ${rule.language} source roots (${rule.path})`,
      });
      continue;
    }

    for (const { unit, expected } of relevant) {
      const matched = expected.filter((f) => selects(rule, f));
      if (matched.length === 0) {
        const row = unitRows.find((r) => coversUnit(r, unit));
        if (row) used.add(row.index);
        else {
          findings.push({
            kind: "source-root-lost",
            rule: rule.id,
            message:
              `${rule.id} reaches 0 of ${expected.length} files in ${unit.key} ` +
              `(module ${unit.module}, source set ${unit.sourceSet}) and no dated exclusion covers it`,
          });
        }
        continue;
      }
      coveredPairs++;
      const lost = expected.filter((f) => !selects(rule, f));
      const orphans: string[] = [];
      for (const file of lost) {
        const row = fileRows.find((r) => r.files.some((glob) => globMatch(glob, file)));
        if (row) used.add(row.index);
        else orphans.push(file);
      }
      if (orphans.length > 0) {
        findings.push({
          kind: "file-lost",
          rule: rule.id,
          message:
            `${rule.id} loses ${orphans.length} file(s) inside covered ${unit.key} with no dated exclusion: ` +
            `${orphans.slice(0, MAX_NAMED).join(", ")}${orphans.length > MAX_NAMED ? ` (+${orphans.length - MAX_NAMED} more)` : ""}`,
        });
      }
    }
  }

  for (const row of table.rows) {
    if (used.has(row.index)) continue;
    findings.push({
      kind: "exclusion-stale",
      rule: row.rules[0],
      message:
        `exclusion ${describe(row)} excuses nothing — every source root and file it names is already ` +
        `covered, or the rule does not exist. A stale disposition is a finding, not a default.`,
    });
  }

  let crossChecked = 0;
  let crossCheckAgreed = 0;
  if (options.crossCheck !== false) {
    const { byRule, skipped } = probeFileSets(repoRoot, rules);
    for (const id of skipped) {
      findings.push({
        kind: "cross-check",
        rule: id,
        message: `${id}: no always-match probe is defined for ast-grep language "${
          rules.find((r) => r.id === id)?.language
        }" — its glob matcher is unverified`,
      });
    }
    for (const rule of rules) {
      const theirs = byRule.get(rule.id);
      if (!theirs) continue;
      crossChecked++;
      const mine = new Set(candidates(files, rule).filter((f) => selects(rule, f)));
      const extra = [...mine].filter((f) => !theirs.has(f));
      const missing = [...theirs].filter((f) => !mine.has(f));
      if (extra.length === 0 && missing.length === 0) {
        crossCheckAgreed++;
        continue;
      }
      findings.push({
        kind: "cross-check",
        rule: rule.id,
        message:
          `${rule.id}: this CLI's glob matcher disagrees with ast-grep — ` +
          `${extra.length} file(s) only here (${extra.slice(0, 3).join(", ")}), ` +
          `${missing.length} only in ast-grep (${missing.slice(0, 3).join(", ")})`,
      });
    }
  }

  return {
    ok: findings.length === 0,
    rules: rules.length,
    units: units.length,
    modules: new Set(units.filter((u) => u.kind === "gradle").map((u) => u.module)).size,
    surfaces: new Set(units.filter((u) => u.kind === "workspace").map((u) => u.module)).size,
    exclusions: table.rows.length,
    crossChecked,
    crossCheckAgreed,
    coveredPairs,
    findings,
  };
}

function coversUnit(row: Exclusion, unit: SourceUnit): boolean {
  const moduleOk = row.modules.length === 0 || row.modules.includes(unit.module);
  const setOk = row.sourceSets.length === 0 || row.sourceSets.includes(unit.sourceSet);
  return moduleOk && setOk;
}

function candidates(files: readonly string[], rule: Rule): string[] {
  const extensions = LANGUAGE_EXTENSIONS[rule.language];
  if (!extensions) return [];
  return files.filter((f) => extensions.some((ext) => f.endsWith(ext)));
}
