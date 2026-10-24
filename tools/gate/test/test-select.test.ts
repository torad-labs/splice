// File-level test selection: a changed source runs the test files that import it, and what the graph cannot place runs all.
import { describe, expect, test } from "bun:test";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import type { Leg } from "../src/lib/prepush-scope.ts";
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
