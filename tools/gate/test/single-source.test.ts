// The single-source leg (src/lib/single-source.ts) counts the declarations of a guarded literal.
import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import { cpSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { singleSourceProblems } from "../src/lib/single-source.ts";
import { layout } from "../src/lib/repo.ts";

const { repoRoot } = layout();

const RULE = `id: kt-no-guarded
language: kotlin
severity: error
message: guarded
files:
  - "src/**/*.kt"
rule:
  all:
    - kind: string_literal
    - has:
        kind: string_content
        regex: '^guarded$'
    - not:
        inside:
          matches: single-source-declaration
          stopBy: end
`;

let root: string;

const write = (path: string, text: string) => {
  mkdirSync(dirname(join(root, path)), { recursive: true });
  writeFileSync(join(root, path), text);
};

beforeEach(() => {
  root = mkdtempSync(join(tmpdir(), "single-source-test-"));
  write("sgconfig.yml", "ruleDirs:\n  - rules\nutilDirs:\n  - quality/rules/utils\n");
  write("rules/kt-no-guarded.yml", RULE);
  mkdirSync(join(root, "quality/rules"), { recursive: true });
  cpSync(join(repoRoot, "quality/rules/utils"), join(root, "quality/rules/utils"), { recursive: true });
});

afterEach(() => rmSync(root, { recursive: true, force: true }));

const problems = () => singleSourceProblems(root, join(root, "sgconfig.yml"));

describe("a guarded literal's declarations", () => {
  test("one const declaration, however often the constant is used, passes", () => {
    write("src/A.kt", 'const val GUARDED: String = "guarded"\n');
    write("src/B.kt", "val use = GUARDED\n");
    expect(problems()).toEqual([]);
  });

  test("a second const declaration fails, naming both", () => {
    write("src/A.kt", 'const val GUARDED: String = "guarded"\n');
    write("src/B.kt", 'internal const val OTHER = "guarded"\n');
    const found = problems();
    expect(found).toHaveLength(1);
    expect(found[0]).toMatch(/declared 2 times \(src\/A\.kt:1, src\/B\.kt:1\)/);
  });
});
