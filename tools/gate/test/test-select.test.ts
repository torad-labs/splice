// File-level test selection: a changed source runs the test files that import it, and what the graph cannot place runs all.
import { describe, expect, test } from "bun:test";
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { readLadder } from "../src/commands/hook.ts";
import type { Leg } from "../src/lib/prepush-scope.ts";
import { layout } from "../src/lib/repo.ts";
import { narrowBunTest } from "../src/lib/test-select.ts";

const LEG: Leg = { task: "gateTests", command: ["bun", "test", "tools/gate"], inputs: ["tools/gate/**", "**/*.ts"] };

function tree(): string {
  const root = mkdtempSync(join(tmpdir(), "splice-test-select-"));
  const put = (path: string, text: string): void => {
    mkdirSync(dirname(join(root, path)), { recursive: true });
    writeFileSync(join(root, path), text);
  };
  put("tools/gate/src/lib/a.ts", "export const a = 1;\n");
  put("tools/gate/src/lib/b.ts", 'import { a } from "./a.ts";\nexport const b = a;\n');
  put("tools/gate/src/lib/c.ts", "export const c = 1;\n");
  put("tools/gate/test/a.test.ts", 'import { a } from "../src/lib/a.ts";\n');
  put("tools/gate/test/b.test.ts", 'import { b } from "../src/lib/b.ts";\n');
  put("tools/gate/test/c.test.ts", 'import { c } from "../src/lib/c.ts";\n');
  put("tools/e2e/src/x.ts", "export const x = 1;\n");
  return root;
}

describe("narrowBunTest", () => {
  test("a changed source runs the test files that import it, directly or through another source", () => {
    const root = tree();
    expect(narrowBunTest(root, LEG, ["tools/gate/src/lib/a.ts"])?.command).toEqual(["bun", "test", "tools/gate/test/a.test.ts", "tools/gate/test/b.test.ts"]);
    expect(narrowBunTest(root, LEG, ["tools/gate/src/lib/c.ts"])?.command).toEqual(["bun", "test", "tools/gate/test/c.test.ts"]);
  });

  test("a changed test file runs itself", () => {
    expect(narrowBunTest(tree(), LEG, ["tools/gate/test/b.test.ts"])?.command).toEqual(["bun", "test", "tools/gate/test/b.test.ts"]);
  });

  test("a path the graph cannot place keeps the whole directory", () => {
    expect(narrowBunTest(tree(), LEG, ["tools/gate/config/ladder.json"])).toBe(LEG);
  });

  test("a source no test imports leaves the leg nothing to run", () => {
    expect(narrowBunTest(tree(), LEG, ["tools/e2e/src/x.ts"])).toBeUndefined();
  });

  test("a leg that is not one bun test run is returned as it is", () => {
    const other: Leg = { task: "x", command: ["bun", "tools/gate", "audit"], inputs: ["**"] };
    expect(narrowBunTest(tree(), other, ["tools/gate/src/lib/a.ts"])).toBe(other);
  });
});

// Load cut 5 (temple plan 2026-10-19, approved by Marcos): the hook's tests judge the hook against a stub gradle. The probes that
// need the real wrapper check gradle's own declarations, not the hook, so a push that changes the hook, or anything the hook
// imports, runs the hook's tests and starts no real gradle. The probes still run when their own file changes, and in the full gate.
describe("a change to the hook runs its tests on a stub gradle, never the real one", () => {
  const { repoRoot } = layout();
  const gateTests = readLadder(repoRoot).find((leg) => leg.task === "gateTests");
  /** The test files the pre-push gateTests leg runs for a push that changes [changed]. */
  const selected = (changed: readonly string[]): string[] => {
    if (gateTests === undefined) throw new Error("the ladder has no gateTests row");
    return (narrowBunTest(repoRoot, gateTests, changed)?.command ?? []).slice(2);
  };
  /** A scratch repository reaches the repository's real gradle only through a link to its wrapper. */
  const runsRealGradle = (file: string): boolean => /join\(repoRoot, ["']gradlew["']\)/.test(readFileSync(join(repoRoot, file), "utf8"));

  test("a change to the hook or to what it imports selects the hook's tests, and no test file that runs real gradle", () => {
    for (const source of ["tools/gate/src/commands/hook.ts", "tools/gate/src/lib/prepush-tree.ts", "tools/gate/src/lib/slot.ts"]) {
      const files = selected([source]);
      expect(files, source).toContain("tools/gate/test/git-hooks.test.ts");
      expect(files.filter(runsRealGradle), source).toEqual([]);
    }
  });

  test("the probes that run real gradle still run when their own file changes", () => {
    const probes = selected(["tools/gate/test/gradle-inputs.test.ts"]);
    expect(probes).toEqual(["tools/gate/test/gradle-inputs.test.ts"]);
    expect(probes.filter(runsRealGradle)).toEqual(probes);
  });
});
