// NEW: Oct 10, 2026 — the commit's own ktlint and detekt: which files they take, what they print, and that the real
// linters, at the catalog's versions, refuse a staged file the module checks would refuse.
import { describe, expect, test } from "bun:test";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { catalogVersion, findingLines, lintable, stagedLinters } from "../src/lib/lint-staged.ts";
import { layout } from "../src/lib/repo.ts";

describe("which staged files the linters take", () => {
  test("module Kotlin and module scripts, not a root script or other files", () => {
    expect(lintable("core/src/main/kotlin/A.kt")).toBe(true);
    expect(lintable("build-logic/src/main/kotlin/splice.kotlin-common.gradle.kts")).toBe(true);
    expect(lintable("build.gradle.kts")).toBe(false);
    expect(lintable("README.md")).toBe(false);
  });

  test("the version a catalog names is read from its versions table", () => {
    const catalog = 'detekt = "1.23.8"\nktlint = "1.8.0"\nktlint-gradle = "14.2.0"\n';
    expect(catalogVersion(catalog, "ktlint")).toBe("1.8.0");
    expect(catalogVersion(catalog, "detekt")).toBe("1.23.8");
    expect(catalogVersion(catalog, "missing")).toBeUndefined();
  });

  test("a finding line loses the mirror's directory and a summary line is not a finding", () => {
    const out = "/tmp/m/core/A.kt:3:1: Exceeded max line length (120) (standard:max-line-length)\nSummary error count:\n  x: 1\n";
    expect(findingLines("ktlint", out, "/tmp/m")).toEqual([
      { tool: "ktlint", line: "core/A.kt:3:1: Exceeded max line length (120) (standard:max-line-length)" },
    ]);
  });
});

describe("the real linters over a mirrored file", () => {
  const root = layout().repoRoot;
  const mirrored = (source: string): { dir: string; file: string } => {
    const dir = mkdtempSync(join(tmpdir(), "splice-lint-test-"));
    const file = "core/src/main/kotlin/splice/core/Probe.kt";
    mkdirSync(dirname(join(dir, file)), { recursive: true });
    writeFileSync(join(dir, file), source);
    return { dir, file };
  };

  test("a clean file passes both, a long line and a blank-line run are named", async () => {
    const clean = mirrored("package splice.core\n\npublic class Probe {\n    public fun ok(value: Int): Int = value + 1\n}\n");
    const bad = mirrored(`package splice.core\n\n\n\npublic class Probe {\n    public val long: String = "${"x".repeat(120)}"\n}\n`);
    try {
      const lint = stagedLinters(root);
      expect(await lint(clean.dir, [clean.file])).toEqual({ findings: [] });
      const verdict = await lint(bad.dir, [bad.file]);
      if ("error" in verdict) throw new Error(verdict.error);
      const text = verdict.findings.map((f) => `${f.tool} ${f.line}`).join("\n");
      expect(text).toContain("max-line-length");
      expect(text).toContain("no-consecutive-blank-lines");
      expect(text).toContain("MaxLineLength");
    } finally {
      rmSync(clean.dir, { recursive: true, force: true });
      rmSync(bad.dir, { recursive: true, force: true });
    }
  }, 60_000);
});
