// Pre-commit runs the ladder legs a commit's own paths can turn red. A leg declares those paths as `commit` globs; a leg that
// needs gradle's graph never runs there. The shipped ladder is read so a row that loses its glob is a red test.
import { describe, expect, test } from "bun:test";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { commitLegsLeg } from "../src/commands/hook.ts";
import { commitLegs, JAR_TASK, type Leg } from "../src/lib/prepush-scope.ts";
import { layout } from "../src/lib/repo.ts";

const rules: Leg = { task: "gateRules", command: ["bun", "tools/gate", "rules"], inputs: ["**"], commit: ["quality/rules/**", "sgconfig.yml"] };
const tasks = (changed: readonly string[], legs: readonly Leg[]) => commitLegs(legs, changed).map((leg) => leg.task);

describe("which legs a commit runs", () => {
  test("a leg runs when a changed path matches its commit globs, and only then", () => {
    expect(tasks(["quality/rules/kotlin/kt-no-any-parameters.yml"], [rules])).toEqual(["gateRules"]);
    expect(tasks(["core/src/main/kotlin/splice/core/A.kt"], [rules])).toEqual([]);
  });

  test("a leg with no commit globs never runs at commit, whatever its inputs read", () => {
    expect(tasks(["quality/rules/kotlin/kt-no-any-parameters.yml"], [{ ...rules, commit: undefined }])).toEqual([]);
  });

  test("a leg that needs gradle's graph never runs at commit", () => {
    expect(tasks(["sgconfig.yml"], [{ ...rules, task: "onJar", dependsOn: [JAR_TASK] }])).toEqual([]);
  });

});

describe("the commit leg", () => {
  /** A scratch repository with one staged path and a ladder of one leg that exits [exit]. */
  const staged = (path: string) => {
    const root = mkdtempSync(join(tmpdir(), "splice-commit-legs-"));
    const git = (...args: string[]) => Bun.spawnSync(["git", ...args], { cwd: root, env: { ...process.env, GIT_CONFIG_GLOBAL: "/dev/null", GIT_CONFIG_NOSYSTEM: "1" } });
    git("init", "-q");
    const file = join(root, path);
    Bun.spawnSync(["mkdir", "-p", join(file, "..")]);
    writeFileSync(file, "x\n");
    git("add", "--", path);
    return root;
  };
  const leg = (exit: number): Leg => ({ task: "probeLeg", command: ["bash", "-c", `exit ${exit}`], inputs: ["**"], commit: ["watched/**"] });
  const run = async (root: string, legs: readonly Leg[]) => {
    const lines: string[] = [];
    const original = console.error;
    console.error = (...args: unknown[]) => void lines.push(args.join(" "));
    try {
      return { code: await commitLegsLeg({ ...layout(), repoRoot: root }, { legs }), text: lines.join("\n") };
    } finally {
      console.error = original;
      rmSync(root, { recursive: true, force: true });
    }
  };

  test("RED: a failing leg whose glob matches a staged path refuses the commit, by name", async () => {
    const { code, text } = await run(staged("watched/a.txt"), [leg(1)]);
    expect(code).toBe(1);
    expect(text).toContain("probeLeg");
  });

  test("a failing leg whose glob matches no staged path is not run", async () => {
    expect((await run(staged("elsewhere/a.txt"), [leg(1)])).code).toBe(0);
  });

  test("GREEN: a passing leg lets the commit through", async () => {
    expect((await run(staged("watched/a.txt"), [leg(0)])).code).toBe(0);
  });
});
