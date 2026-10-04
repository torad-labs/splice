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
  /** dispositions actually graded: the rows' cross-product cells, always >= exclusions */
  readonly exclusionAtoms: number;
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

  // Staleness is keyed on the ATOM, never on the row and never on (row, rule). See atomsOf: a row's
  // scope is a CROSS PRODUCT, and one live cell used to mark the whole row used, hiding every dead
  // sibling. Two measured escapes, both on this table: keyed on the ROW, six rules widened to reach
  // a newly extracted module left six dead entries and the proof stayed GREEN (2026-09-21); keyed on
  // (row, RULE), a row naming 14 modules still reports nothing when 13 of them stop excusing
  // anything, because the 14th is alive. Each retreat looked complete because the mutation arm that
  // should have caught it only ever mutated the shape already covered — a single-rule row, then a
  // single-member one.
  const used = new Set<string>();
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
        // the cell that did the work: the row's own member that matched, wildcard where it omits
        // the dimension entirely.
        if (row) {
          used.add(
            atomKey(row.index, rule.id, row.modules.length ? unit.module : ANY, row.sourceSets.length ? unit.sourceSet : ANY, ANY),
          );
        }
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
        const row = fileRows.find((r) => coversUnit(r, unit) && r.files.some((glob) => globMatch(glob, file)));
        // Credit every matching glob in the selected row, including overlapping siblings.
        if (row) {
          for (const glob of row.files) {
            if (globMatch(glob, file)) {
              used.add(
                atomKey(row.index, rule.id, row.modules.length ? unit.module : ANY, row.sourceSets.length ? unit.sourceSet : ANY, glob),
              );
            }
          }
        } else orphans.push(file);
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
    for (const atom of atomsOf(row)) {
      // an id no rule declares is already reported by name as exclusion-invalid above; reporting it
      // a second time here would say nothing the reader does not have.
      if (!ruleIds.has(atom.rule)) continue;
      if (used.has(atomKey(row.index, atom.rule, atom.module, atom.sourceSet, atom.file))) continue;
      findings.push({
        kind: "exclusion-stale",
        rule: atom.rule,
        message:
          `exclusion ${describe(row)} lists "${atom.rule}" for ${atomScope(atom)}, which excuses ` +
          `nothing — that scope is already covered for that rule. A stale disposition is a finding, ` +
          `not a default.`,
      });
    }
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
    exclusionAtoms: table.rows.reduce((n, row) => n + atomsOf(row).length, 0),
    crossChecked,
    crossCheckAgreed,
    coveredPairs,
    findings,
  };
}

/** The wildcard cell: a dimension the row OMITS. */
const ANY = "*";

interface Atom {
  readonly rule: string;
  readonly module: string;
  readonly sourceSet: string;
  readonly file: string;
}

function atomKey(rowIndex: number, rule: string, module: string, sourceSet: string, file: string): string {
  return [rowIndex, rule, module, sourceSet, file].join("\u0000");
}

/** Each declared scope cell must excuse a real loss. Omitted dimensions remain one wildcard,
 *  not an expansion over today's tree: an explicit global waiver is deliberately broader. */
function atomsOf(row: Exclusion): Atom[] {
  const files = row.files.length ? row.files : [ANY];
  const modules = row.modules.length ? row.modules : [ANY];
  const sourceSets = row.sourceSets.length ? row.sourceSets : [ANY];
  const atoms: Atom[] = [];
  for (const rule of row.rules) {
    for (const module of modules) {
      for (const sourceSet of sourceSets) {
        for (const file of files) atoms.push({ rule, module, sourceSet, file });
      }
    }
  }
  return atoms;
}

/** The cell, named the way the table spells it, so a finding says which line to delete. */
function atomScope(atom: Atom): string {
  const module = atom.module === ANY ? "every module" : `module ${atom.module}`;
  const sourceSet = atom.sourceSet === ANY ? "every source set" : `source set ${atom.sourceSet}`;
  const file = atom.file === ANY ? "" : ` / files glob ${atom.file}`;
  return `${module} / ${sourceSet}${file}`;
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
