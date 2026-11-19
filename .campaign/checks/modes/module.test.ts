// modes-module: a folder under modes/ that holds a mode.toml is a Gradle module of its own, found and governed with no
// line added anywhere. A person copies modes/template to modes/echo and renames it in its manifest and package; then
// the copy builds as :modes-echo, its tests run, it ships in splice's jar with its ServiceLoader entry, the
// architecture laws and the gate know it, and the module law refuses it a dependency on a feature, in the build and
// in the gate alike.

import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { Tree, jarEntry, requireTemplate } from "./lib/modes.ts";

// A Gradle build runs until it finishes: these tests have no time limit of their own beyond an hour.
const LONG = 3_600_000;

let tree: Tree;

beforeAll(() => {
  requireTemplate();
  tree = Tree.make();
  tree.copyMode("template", "echo");
}, LONG);

afterAll(() => tree?.dispose());

describe("modes-module", () => {
  test("copying the template touches nothing outside its own new folder", () => {
    const changed = tree.git(["status", "--porcelain", "--untracked-files=all"]).out.split("\n").filter(Boolean);
    expect(changed.length).toBeGreaterThan(0);
    for (const line of changed) expect(line.slice(3)).toStartWith("modes/echo/");
  });

  test("the copy is the module :modes-echo, and its own tests run and pass", () => {
    const r = tree.gradle([":modes-echo:test"]);
    expect(r.code, `${r.out}\n${r.err}`.slice(-6000)).toBe(0);
    const report = tree.testReport("modes/echo");
    expect(report.tests, "the copied mode's tests ran").toBeGreaterThan(0);
    expect(report.failures, report.said).toBe(0);
  }, LONG);

  test("the jar carries every mode, each by its ServiceLoader entry", () => {
    const jar = tree.jar();
    const entries = jarEntry(jar, "META-INF/services/splice.modes.api.Mode").split("\n").map((l) => l.trim()).filter(Boolean);
    expect(entries).toContain("splice.modes.template.TemplateMode");
    expect(entries).toContain("splice.modes.echo.EchoMode");
  }, LONG);

  test("the architecture laws hold with the new mode in, with no line added for it", () => {
    const r = tree.gradle([":quality-architecture:test"]);
    expect(r.code, `${r.out}\n${r.err}`.slice(-6000)).toBe(0);
  }, LONG);

  test("the gate reads the mode as a module and finds no breach of the law", async () => {
    const law = tree.gate(["module-law"]);
    expect(law.code, `${law.out}\n${law.err}`).toBe(0);
    const sources = (await import(tree.path("tools/gate/src/lib/sources.ts"))) as { readGradleModules(repoRoot: string, buildRoot: string): { id: string; dir: string }[] };
    const read = sources.readGradleModules(tree.dir, tree.dir);
    expect(read).toContainEqual({ id: "modes-echo", dir: "modes/echo" });
    expect(read).toContainEqual({ id: "modes-template", dir: "modes/template" });
    expect(read).toContainEqual({ id: "modes-api", dir: "modes/api" });
    const hook = (await import(tree.path("tools/gate/src/commands/hook.ts"))) as { gradleModules(root: string): { path: string; dir: string }[] };
    expect(hook.gradleModules(tree.dir)).toContainEqual({ path: ":modes-echo", dir: "modes/echo" });
  }, LONG);

  test("a mode that depends on a feature breaks the module law, in the gate and in the build", () => {
    tree.edit("modes/echo/build.gradle.kts", (t) => `${t}\ndependencies {\n    implementation(project(":features-turns"))\n}\n`);
    try {
      const law = tree.gate(["module-law"]);
      expect(law.code).toBe(1);
      expect(`${law.out}${law.err}`).toContain(":modes-echo");
      expect(`${law.out}${law.err}`).toContain(":features-turns");
      const build = tree.gradle([":modes-echo:compileKotlin"]);
      expect(build.code).not.toBe(0);
      expect(`${build.out}${build.err}`).toContain("MODULE LAW");
      expect(`${build.out}${build.err}`).toContain(":modes-echo");
    } finally {
      tree.restore();
      tree.copyMode("template", "echo");
    }
  }, LONG);
});
