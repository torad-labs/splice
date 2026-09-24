// Red-green proof for the typecheck wall (src/lib/typecheck.ts).
//
// Every arm builds a fixture repository, asserts its SETUP took (the file is tracked, the error is
// really there), then grades it with the real native compiler this repository pins. An arm that
// grades a mutation it did not make is the failure this wall family exists to catch.
import { describe, expect, test } from "bun:test";
import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { type TypecheckConfig, WallError, loadConfig, trackedSources, wall } from "../src/lib/typecheck.ts";
import { layout } from "../src/lib/repo.ts";

const COMPILER = join(layout().repoRoot, loadConfig(layout().repoRoot).compiler);
const TSCONFIG = JSON.stringify({ compilerOptions: { strict: true, noEmit: true, types: [], lib: ["ESNext"] }, include: ["src/**/*.ts"] });
const CLEAN = "export const n: number = 1;\n";
const BROKEN = 'export const n: number = "one";\n'; // exactly one TS2322

function write(root: string, path: string, text: string) {
  mkdirSync(dirname(join(root, path)), { recursive: true });
  writeFileSync(join(root, path), text);
}

function fixture(files: Record<string, string>): string {
  const root = mkdtempSync(join(tmpdir(), "typecheck-"));
  spawnSync("git", ["-C", root, "init", "-q"]);
  write(root, "tsconfig.json", TSCONFIG);
  for (const [p, t] of Object.entries(files)) write(root, p, t);
  spawnSync("git", ["-C", root, "add", "-A"]);
  return root;
}

function config(over: Partial<TypecheckConfig> = {}): TypecheckConfig {
  return { compiler: COMPILER, programs: ["tsconfig.json"], excused: {}, baseline: {}, ...over };
}

function grade(files: Record<string, string>, over: Partial<TypecheckConfig>, setupOk: (root: string) => boolean) {
  const root = fixture(files);
  try {
    expect(setupOk(root), "SETUP did not take; the arm would grade nothing").toBe(true);
    return wall(root, config(over));
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

const tracks = (...paths: string[]) => (root: string) => paths.every((p) => trackedSources(root).includes(p));

describe("typecheck wall", () => {
  test("clean program, every file covered -> green", () => {
    const r = grade({ "src/a.ts": CLEAN }, {}, tracks("src/a.ts"));
    expect(r.problems).toEqual([]);
  });

  test("a tracked file no program loads -> red by name", () => {
    const r = grade({ "src/a.ts": CLEAN, "other/b.ts": CLEAN }, {}, tracks("other/b.ts"));
    expect(r.problems.join("\n")).toContain("uncovered other/b.ts");
  });

  test("the same file excused with a reason -> green; excused with no reason -> red", () => {
    const files = { "src/a.ts": CLEAN, "other/b.ts": CLEAN };
    expect(grade(files, { excused: { "other/b.ts": "generated" } }, tracks("other/b.ts")).problems).toEqual([]);
    expect(grade(files, { excused: { "other/b.ts": " " } }, tracks("other/b.ts")).problems.join("\n")).toContain("carries no reason");
  });

  test("an excuse for a file a program now loads -> red (stale)", () => {
    const r = grade({ "src/a.ts": CLEAN }, { excused: { "src/a.ts": "was outside" } }, tracks("src/a.ts"));
    expect(r.problems.join("\n")).toContain("stale excuse src/a.ts");
  });

  test("a new type error -> red, with the diagnostic printed", () => {
    const r = grade({ "src/a.ts": BROKEN }, {}, tracks("src/a.ts"));
    const text = r.problems.join("\n");
    expect(text).toContain("new type errors in src/a.ts: 1 (baseline 0)");
    expect(text).toContain("TS2322");
  });

  test("the error at its baseline -> green; one more than the baseline -> red", () => {
    const two = BROKEN + 'export const m: string = 2;\n';
    expect(grade({ "src/a.ts": BROKEN }, { baseline: { "src/a.ts": 1 } }, tracks("src/a.ts")).problems).toEqual([]);
    expect(grade({ "src/a.ts": two }, { baseline: { "src/a.ts": 1 } }, tracks("src/a.ts")).problems.join("\n"))
      .toContain("new type errors in src/a.ts: 2 (baseline 1)");
  });

  test("fewer errors than the baseline -> red until the baseline is lowered (it may only shrink)", () => {
    const r = grade({ "src/a.ts": CLEAN }, { baseline: { "src/a.ts": 1 } }, tracks("src/a.ts"));
    expect(r.problems.join("\n")).toContain("ratchet src/a.ts: 0 errors, baseline 1");
  });

  test("a baseline entry for a file that is gone -> red (stale)", () => {
    const r = grade({ "src/a.ts": CLEAN }, { baseline: { "src/gone.ts": 3 } }, tracks("src/a.ts"));
    expect(r.problems.join("\n")).toContain("stale baseline src/gone.ts");
  });

  test("an untracked scratch file with errors is not the gate's business", () => {
    const root = fixture({ "src/a.ts": CLEAN });
    try {
      write(root, "src/scratch.ts", BROKEN);
      expect(trackedSources(root)).not.toContain("src/scratch.ts");
      expect(wall(root, config()).problems).toEqual([]);
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  });

  test("a census that cannot run is exit 2, never a pass", () => {
    for (const over of [{ programs: ["missing/tsconfig.json"] }, { compiler: "/nonexistent/tsc" }]) {
      const root = fixture({ "src/a.ts": CLEAN });
      try {
        expect(() => wall(root, config(over))).toThrow(WallError);
      } finally {
        rmSync(root, { recursive: true, force: true });
      }
    }
  });

  // Every program's tsc over the whole tree: 4.2 s of CPU and 7.9 s of wall at load 56 (2026-09-24),
  // past bun's 5 s default, so the budget is stated rather than inherited.
  test("this repository: every tracked .ts is covered and no file is above its baseline", () => {
    const r = wall(layout().repoRoot);
    expect(r.problems).toEqual([]);
  }, 60_000);
});
