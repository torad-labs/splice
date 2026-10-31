// The selector against the real module law: what a changed file makes the gate check.
import { describe, expect, test } from "bun:test";
import { gradleModules, moduleOf, readModuleGraph } from "../src/commands/hook.ts";
import { layout } from "../src/lib/repo.ts";
import { select } from "../src/lib/selector.ts";

const { repoRoot } = layout();
const found = gradleModules(repoRoot);
const modules = found.map((m) => m.path);
const graph = readModuleGraph(repoRoot);
const pick = (...changed: string[]) => select({ changed, modules, moduleOf: (file) => moduleOf(found, file), graph });

describe("a changed file selects the checks it affects", () => {
  test("a Kotlin file selects its module and every module that depends on it, never the ones that do not", () => {
    const s = pick("core/src/main/kotlin/splice/core/config/Knob.kt");
    expect(s.modules).toContain(":core");
    expect(s.modules).toContain(":integrations-http");
    expect(s.full).toBe(false);
    const leaf = pick("features/turns/src/main/kotlin/splice/head/HeadDeps.kt");
    expect(leaf.modules).toContain(":features-turns");
    expect(leaf.modules).not.toContain(":core");
  });

  test("a path the scope file marks full selects every module, and a rule, a lockfile or one module's build file does not", () => {
    for (const path of ["settings.gradle.kts", "gradle/libs.versions.toml", "build-logic/build.gradle.kts", "build-logic/src/main/kotlin/splice.kotlin-common.gradle.kts", "quality/detekt/detekt.yml", ".editorconfig"]) {
      const s = pick(path);
      expect(s.full, path).toBe(true);
      expect(s.modules, path).toEqual([...modules].sort());
      expect(s.why, path).toContain(path);
    }
    for (const path of ["quality/rules/x.yml", "package.json", "bun.lock", "features/turns/build.gradle.kts", "build-logic/src/test/kotlin/splice/ladder/LadderTableTest.kt"]) {
      const s = pick(path);
      expect(s.full, path).toBe(false);
      expect(s.modules.length, path).toBeLessThan(modules.length);
    }
  });

  test("a module the law does not list is not a dependent of every module", () => {
    const leaf = pick("features/turns/src/main/kotlin/splice/head/HeadDeps.kt");
    expect(leaf.modules).not.toContain(":app");
    expect(leaf.modules).toEqual([":features-turns"]);
  });

  test("prose no law reads selects no module and counts as docs-only; prose a law reads does not", () => {
    expect(pick("docs/specs/telemetry.md")).toEqual({ modules: [], full: false, docsOnly: true });
    expect(pick("README.md").docsOnly).toBe(false);
    expect(pick("docs/PROVENANCE.md").docsOnly).toBe(false);
  });

  test("a tools-only change selects no gradle module", () => {
    expect(pick("tools/gate/src/commands/hook.ts").modules).toEqual([]);
  });
});
