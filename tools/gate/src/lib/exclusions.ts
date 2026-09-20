// The dated exclusion table: the only way a routed rule is allowed to cover less than the whole
// expected source set.
//
// "Every item gets a DISPOSITION — covered / excluded with a written reason / pending; anything
// else fails the build BY NAME. A blank reason is an absence wearing a label." An undated row is
// exactly that, so a missing or malformed `date` is a finding, not a default.
import { existsSync } from "node:fs";

export interface Exclusion {
  /** 1-based position in the file, so a finding can name the row that produced it */
  readonly index: number;
  readonly rules: readonly string[];
  /** Gradle module ids / workspace names; empty means every module */
  readonly modules: readonly string[];
  /** `main` | `test` | `testFixtures` | `src`; empty means every source set */
  readonly sourceSets: readonly string[];
  /** ast-grep-style path globs. When present the row excuses FILES, never a whole source root. */
  readonly files: readonly string[];
  readonly date: string;
  readonly reason: string;
}

export interface ExclusionTable {
  readonly path: string;
  readonly rows: readonly Exclusion[];
  /** structural failures found while reading — undated rows, blank reasons, unnamed rules */
  readonly problems: readonly string[];
}

const DATE = /^\d{4}-\d{2}-\d{2}$/;

export async function readExclusions(path: string): Promise<ExclusionTable> {
  if (!existsSync(path)) {
    return { path, rows: [], problems: [`the coverage exclusion table is missing at ${path}`] };
  }
  const loaded = (await import(path)) as { default?: { exclusion?: unknown[] } };
  const raw = loaded.default?.exclusion ?? [];
  if (!Array.isArray(raw)) return { path, rows: [], problems: [`${path}: [[exclusion]] is not a list`] };

  const rows: Exclusion[] = [];
  const problems: string[] = [];
  raw.forEach((entry, i) => {
    const index = i + 1;
    const row = entry as Record<string, unknown>;
    const rules = list(row.rules).concat(typeof row.rule === "string" ? [row.rule] : []);
    const where = `exclusion #${index}${rules.length ? ` (${rules.join(", ")})` : ""}`;
    if (rules.length === 0) problems.push(`${where} names no rule — a disposition with no subject`);
    if (typeof row.date !== "string" || row.date.length === 0) {
      problems.push(`${where} has NO DATE — an exclusion without a date is not a disposition`);
    } else if (!DATE.test(row.date)) {
      problems.push(`${where} has date "${row.date}", which is not YYYY-MM-DD`);
    }
    if (typeof row.reason !== "string" || row.reason.trim().length === 0) {
      problems.push(`${where} has no reason — a blank reason is an absence wearing a label`);
    }
    rows.push({
      index,
      rules,
      modules: list(row.modules).concat(typeof row.module === "string" ? [row.module] : []),
      sourceSets: list(row.sourceSets).concat(typeof row.sourceSet === "string" ? [row.sourceSet] : []),
      files: list(row.files).concat(typeof row.file === "string" ? [row.file] : []),
      date: typeof row.date === "string" ? row.date : "",
      reason: typeof row.reason === "string" ? row.reason : "",
    });
  });
  return { path, rows, problems };
}

function list(value: unknown): string[] {
  if (value === undefined || value === null) return [];
  if (Array.isArray(value) && value.every((v) => typeof v === "string")) return value as string[];
  return [];
}

export function describe(row: Exclusion): string {
  const scope = row.files.length
    ? `files ${row.files.join(" ")}`
    : `${row.modules.length ? row.modules.join("/") : "every module"} ${
        row.sourceSets.length ? row.sourceSets.join("+") : "every source set"
      }`;
  return `#${row.index} ${row.rules.join(", ")} — ${scope} (${row.date})`;
}
