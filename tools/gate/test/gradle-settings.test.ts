// The settings reader (src/lib/gradle-settings.ts) decides which included builds the coverage proofs count.
import { describe, expect, test } from "bun:test";
import { includedBuildDirs } from "../src/lib/gradle-settings.ts";
import { layout } from "../src/lib/repo.ts";

const { repoRoot } = layout();
const dirs = (settings: string) => includedBuildDirs(repoRoot, settings, "fixture/settings.gradle.kts");

describe("the declared included builds", () => {
  test("the repo's own settings file declares build-logic", async () => {
    const text = await Bun.file(`${repoRoot}/settings.gradle.kts`).text();
    expect(includedBuildDirs(repoRoot, text)).toContain("build-logic");
  });

  test("a call that sits in a comment declares nothing", () => {
    expect(dirs('// includeBuild("ghost")\n')).toEqual([]);
  });

  test("every spelling of a plain directory declares it: qualified, with a block, or through file()", () => {
    const settings = [
      'includeBuild("a")',
      'settings.includeBuild("b")',
      'includeBuild("c") { dependencySubstitution { } }',
      'settings.includeBuild(file("d"))',
      'pluginManagement { includeBuild("e") }',
    ].join("\n");
    expect(dirs(settings)).toEqual(["a", "b", "c", "d", "e"]);
  });

  test("a spelling the reader cannot resolve fails by naming file and line instead of dropping the build", () => {
    expect(() => dirs('val x = 1\nincludeBuild(path)\n')).toThrow(/fixture\/settings\.gradle\.kts:2 includeBuild\(path\)/);
    expect(() => dirs('gradle.includeBuild("logic")\n')).toThrow(/fixture\/settings\.gradle\.kts:1 gradle\.includeBuild/);
  });
});
