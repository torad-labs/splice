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

  test("a spelling the reader cannot resolve fails by naming the call instead of dropping the build", () => {
    expect(() => dirs('includeBuild(file("logic"))\n')).toThrow(/fixture\/settings\.gradle\.kts:1 includeBuild\(/);
  });
});
