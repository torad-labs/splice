// harness-own-module: the harness is a module of its own (docs/specs/harness.md, decision 11). :features-harness,
// package splice.harness, holds the capabilities, their rule and their workers; :features-turns knows it only
// through a port it owns and never depends on it; the module law names the harness's edges and the layer map
// classifies it. The old package, splice.head.harness under features/turns, is gone. What every capability check
// proved before the move, its own check still proves after it: those checks are this item's other checks.

import { describe, expect, test } from "bun:test";
import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { ROOT } from "../server/lib/harness.ts";

const read = (p: string) => readFileSync(join(ROOT, p), "utf8");

function kotlin(dir: string): string[] {
  const at = join(ROOT, dir);
  if (!existsSync(at)) return [];
  const out: string[] = [];
  for (const name of readdirSync(at)) {
    const p = join(at, name);
    if (statSync(p).isDirectory()) out.push(...kotlin(relative(ROOT, p)));
    else if (name.endsWith(".kt")) out.push(relative(ROOT, p));
  }
  return out;
}

/** The project dependencies the module law allows `module`, or null when it names none. */
function law(module: string): string[] | null {
  for (const line of read("gradle/module-law.txt").split("\n")) {
    const m = line.match(/^\s*(:[\w-]+)\s*->(.*)$/);
    if (m && m[1] === module) return m[2]!.trim().split(/\s+/).filter(Boolean).sort();
  }
  return null;
}

const EDGES = [
  ":core",
  ":features-turns",
  ":features-sessions",
  ":features-launch",
  ":integrations-claude-code",
  ":integrations-http",
  ":integrations-upstream",
  ":integrations-mcp",
].sort();

describe("harness-own-module", () => {
  test(":features-harness is a module of the build, and the layer map classifies it as a feature", () => {
    expect(read("settings.gradle.kts")).toMatch(/["']:?features-harness["']/);
    expect(existsSync(join(ROOT, "features/harness/build.gradle.kts"))).toBe(true);
    const map = read("quality/architecture/src/test/resources/layer-map.toml");
    const feature = map.slice(map.indexOf("[feature]"), map.indexOf("[composition]"));
    expect(feature).toContain('":features-harness"');
  });

  test("the module law lists the harness's edges, and :features-turns has no edge to it", () => {
    expect(law(":features-harness")).toEqual(EDGES);
    expect(law(":features-turns") ?? []).not.toContain(":features-harness");
    expect(read("features/turns/build.gradle.kts")).not.toContain("features-harness");
  });

  test("the harness's code is in package splice.harness under features/harness", () => {
    const files = kotlin("features/harness/src/main/kotlin");
    expect(files.length).toBeGreaterThan(0);
    const packages = new Set(files.map((f) => read(f).match(/^package\s+([\w.]+)/m)?.[1] ?? ""));
    for (const p of packages) expect(p === "splice.harness" || p.startsWith("splice.harness.")).toBe(true);
    const all = files.map(read).join("\n");
    for (const name of ["HttpWorker", "CapabilityRules"]) expect(all).toContain(name);
  });

  test("nothing under features/turns is in the harness, names its workers or its rule, or imports it", () => {
    const files = kotlin("features/turns/src/main/kotlin");
    expect(files.length).toBeGreaterThan(0);
    const offenders: string[] = [];
    for (const f of files) {
      const text = read(f);
      if (/^package\s+splice\.(head\.)?harness\b/m.test(text)) offenders.push(`${f}: in the harness's package`);
      if (/^import\s+splice\.harness\b/m.test(text)) offenders.push(`${f}: imports splice.harness`);
      if (/\b(HttpWorker|CapabilityRules|ContextGetter|McpWorker)\b/.test(text)) offenders.push(`${f}: names a worker or the rule`);
    }
    expect(offenders).toEqual([]);
    expect(existsSync(join(ROOT, "features/turns/src/main/kotlin/splice/head/harness"))).toBe(false);
  });
});
