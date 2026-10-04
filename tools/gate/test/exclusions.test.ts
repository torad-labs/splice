// A scope field that is PRESENT but mistyped is a finding by name — never an empty set.
//
// Every field here has two legal spellings, `modules = ["core"]` and `module = "core"`, which is
// exactly what makes the typo `modules = "core"` look right: it is valid TOML, it reads correctly
// in a review, and mapping it to [] does not narrow the row — it WIDENS it. An empty scope means
// every module, every source set, or a row that excuses whole source roots rather than files
// (coverage.ts:93, 204-207), so one wrong character turned a dated exclusion for :app into a global
// waiver and the proof stayed green. Each shape below is the same defect wearing a different type.
import { afterAll, describe, expect, test } from "bun:test";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { readExclusions, type ExclusionTable } from "../src/lib/exclusions.ts";

const dir = mkdtempSync(join(tmpdir(), "gate-exclusions-"));
afterAll(() => rmSync(dir, { recursive: true, force: true }));

let written = 0;
/** A table of its own per case: the reader imports by path, and Bun caches modules by path. */
function table(body: string): Promise<ExclusionTable> {
  const path = join(dir, `table-${written++}.toml`);
  writeFileSync(path, body);
  return readExclusions(path);
}

const DATED = 'date = "2026-09-20"\nreason = "typed as valid TOML scalars"\n';

describe("reading the exclusion table", () => {
  test("the row that reads as every module: three scalars where three lists belong", async () => {
    const read = await table(
      `[[exclusion]]\nrule = "kt-no-println"\nmodules = "core"\nsourceSets = "main"\n` +
        `files = "core/src/main/kotlin/X.kt"\n${DATED}`,
    );
    expect(read.problems).toHaveLength(3);
    for (const field of ["modules", "sourceSets", "files"]) {
      const named = read.problems.filter((p) => p.includes(`has ${field} = the string`));
      expect(named, `${field} must fail BY NAME`).toHaveLength(1);
      expect(named[0]).toContain("exclusion #1");
      expect(named[0]).toContain("kt-no-println");
    }
    // and it excuses nothing: a row whose scope cannot be read is not a disposition
    expect(read.rows).toEqual([]);
  });

  test("every wrong-type shape is refused, and names what it found", async () => {
    const cases: readonly (readonly [string, string])[] = [
      ['modules = "core"', 'has modules = the string "core"'],
      ["module = [\"core\"]", 'has module = the list ["core"]'],
      ['sourceSets = ["main", 3]', 'has sourceSets = the list ["main",3]'],
      ["sourceSets = 3", "has sourceSets = the number 3"],
      ["modules = []", "has modules = [], an empty scope that would read as every module"],
      ["sourceSets = []", "has sourceSets = [], an empty scope that would read as every source set"],
      ["files = []", "has files = [], an empty scope that would read as a row that excuses WHOLE source roots"],
      ["file = true", "has file = the boolean true"],
    ];
    for (const [line, expected] of cases) {
      const read = await table(`[[exclusion]]\nrule = "r"\n${line}\n${DATED}`);
      expect(read.problems, `${line} must be refused`).toHaveLength(1);
      expect(read.problems[0]).toContain(expected);
      expect(read.problems[0]).toContain("exclusion #1 (r)");
      expect(read.rows, `${line} must not be stored`).toEqual([]);
    }
  });

  test("a well-formed row still reads, in both spellings", async () => {
    const read = await table(
      `[[exclusion]]\nrules = ["a", "b"]\nrule = "c"\nmodules = ["core"]\nmodule = "app"\n` +
        `sourceSets = ["main"]\nfiles = ["core/src/main/kotlin/X.kt"]\n${DATED}`,
    );
    expect(read.problems).toEqual([]);
    expect(read.rows).toHaveLength(1);
    expect(read.rows[0]!.rules).toEqual(["a", "b", "c"]);
    expect(read.rows[0]!.modules).toEqual(["core", "app"]);
    expect(read.rows[0]!.sourceSets).toEqual(["main"]);
    expect(read.rows[0]!.files).toEqual(["core/src/main/kotlin/X.kt"]);
  });

  test("an omitted scope still means every module and every source set", async () => {
    const read = await table(`[[exclusion]]\nrule = "r"\n${DATED}`);
    expect(read.problems).toEqual([]);
    expect(read.rows[0]!.modules).toEqual([]);
    expect(read.rows[0]!.sourceSets).toEqual([]);
  });
});
