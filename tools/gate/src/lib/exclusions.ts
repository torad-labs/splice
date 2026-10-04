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
  /** Gradle module ids / workspace names; OMITTED in the table means every module */
  readonly modules: readonly string[];
  /** `main` | `test` | `testFixtures` | `src`; OMITTED in the table means every source set */
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
    const rules = names(row, "rules", "rule");
    const modules = names(row, "modules", "module", "every module");
    const sourceSets = names(row, "sourceSets", "sourceSet", "every source set");
    const files = names(row, "files", "file", "a row that excuses WHOLE source roots, not files");
    const fields = [rules, modules, sourceSets, files];
    const where = `exclusion #${index}${rules.values.length ? ` (${rules.values.join(", ")})` : ""}`;

    for (const fault of fields.flatMap((field) => field.faults)) problems.push(`${where} ${fault}`);
    if (rules.values.length === 0 && rules.faults.length === 0) {
      problems.push(`${where} names no rule — a disposition with no subject`);
    }
    if (typeof row.date !== "string" || row.date.length === 0) {
      problems.push(`${where} has NO DATE — an exclusion without a date is not a disposition`);
    } else if (!DATE.test(row.date)) {
      problems.push(`${where} has date "${row.date}", which is not YYYY-MM-DD`);
    }
    if (typeof row.reason !== "string" || row.reason.trim().length === 0) {
      problems.push(`${where} has no reason — a blank reason is an absence wearing a label`);
    }
    // A row whose scope could not be READ is not stored, so it can waive nothing. Reporting the
    // fault and keeping the row would still leave the widened row in the table for this run, and
    // the fault a reader has to connect to the loss it silently absorbed.
    if (fields.some((field) => field.faults.length > 0)) return;
    rows.push({
      index,
      rules: rules.values,
      modules: modules.values,
      sourceSets: sourceSets.values,
      files: files.values,
      date: typeof row.date === "string" ? row.date : "",
      reason: typeof row.reason === "string" ? row.reason : "",
    });
  });
  return { path, rows, problems };
}

interface Names {
  readonly values: string[];
  /** message tails, each naming the field and what was found there */
  readonly faults: string[];
}

/**
 * One scope field, in both spellings the table accepts: `modules = ["core"]` or `module = "core"`.
 *
 * A PRESENT field of any other shape is a fault, never an empty set. `modules = "core"` is valid
 * TOML and reading it as `[]` does not narrow the row, it WIDENS it: an empty scope means every
 * module, every source set, or a row that excuses whole source roots (coverage.ts:93, 204-207). A
 * typo would turn one module's dated exclusion into a global waiver, and the proof would stay green
 * while reporting a number nobody could question. `whole` names what the empty set would mean —
 * omitted for `rules`, where no rule is "a disposition with no subject" rather than every rule.
 */
function names(row: Record<string, unknown>, plural: string, singular: string, whole?: string): Names {
  const values: string[] = [];
  const faults: string[] = [];
  const many = row[plural];
  if (many !== undefined && many !== null) {
    if (!Array.isArray(many)) {
      faults.push(`has ${plural} = ${show(many)}, which is not a list — one name is ${singular} = "…"`);
    } else if (many.length === 0) {
      if (whole) faults.push(`has ${plural} = [], an empty scope that would read as ${whole} — omit the field to mean that`);
    } else if (!many.every((v) => typeof v === "string" && v.length > 0)) {
      faults.push(`has ${plural} = ${show(many)}, whose entries must all be non-empty strings`);
    } else {
      values.push(...(many as string[]));
    }
  }
  const one = row[singular];
  if (one !== undefined && one !== null) {
    if (typeof one !== "string" || one.length === 0) {
      faults.push(`has ${singular} = ${show(one)}, which is not a non-empty string — several names are ${plural} = ["…"]`);
    } else {
      values.push(one);
    }
  }
  return { values, faults };
}

/** What was found, with its TYPE — the difference between `"core"` and `["core"]` is the finding. */
function show(value: unknown): string {
  if (typeof value === "string") return `the string ${JSON.stringify(value)}`;
  if (Array.isArray(value)) return `the list ${JSON.stringify(value)}`;
  return `the ${typeof value} ${JSON.stringify(value)}`;
}

export function describe(row: Exclusion): string {
  const scope = row.files.length
    ? `files ${row.files.join(" ")}`
    : `${row.modules.length ? row.modules.join("/") : "every module"} ${
        row.sourceSets.length ? row.sourceSets.join("+") : "every source set"
      }`;
  return `#${row.index} ${row.rules.join(", ")} — ${scope} (${row.date})`;
}
