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
    for (const path of ["settings.gradle.kts", "build-logic/build.gradle.kts", "build-logic/src/main/kotlin/splice.kotlin-common.gradle.kts", "quality/detekt/detekt.yml", ".editorconfig"]) {
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

  test("the version catalog selects the modules whose scripts read its changed entries, and every module when what changed was not read", () => {
    const catalog = "gradle/libs.versions.toml";
    const unread = pick(catalog);
    expect(unread.full).toBe(true);
    expect(unread.modules).toEqual([...modules].sort());
    expect(unread.why).toContain(catalog);
    expect(unread.why).toContain("not read");

    const read = select({ changed: [catalog], modules, moduleOf: (file) => moduleOf(found, file), graph, catalog: { kind: "modules", modules: new Map([[":core", "`guava` in gradle/libs.versions.toml changed, and core/build.gradle.kts reads it"]]) } });
    expect(read.full).toBe(false);
    expect(read.modules).toContain(":core");
    expect(read.modules).toContain(":integrations-http");
    expect(read.modules).not.toContain(":quality-compiler-plugin");
    expect(read.reasons?.get(":core")).toContain("`guava`");

    const wide = select({ changed: [catalog], modules, moduleOf: (file) => moduleOf(found, file), graph, catalog: { kind: "full", why: "`kotlin` in gradle/libs.versions.toml changed, and build-logic/build.gradle.kts reads it, as every module's build does" } });
    expect(wide.full).toBe(true);
    expect(wide.why).toContain("`kotlin`");

    const comment = select({ changed: [catalog], modules, moduleOf: (file) => moduleOf(found, file), graph, catalog: { kind: "none" } });
    expect(comment).toEqual({ modules: [], full: false, docsOnly: false });

    // a catalog entry and a source in one push: the source's module and the entry's readers, with their dependents
    const both = select({ changed: [catalog, "features/turns/src/main/kotlin/splice/head/HeadDeps.kt"], modules, moduleOf: (file) => moduleOf(found, file), graph, catalog: { kind: "modules", modules: new Map([[":core", "`guava` changed"]]) } });
    expect(both.modules).toContain(":features-turns");
    expect(both.modules).toContain(":core");
    expect(both.full).toBe(false);
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
