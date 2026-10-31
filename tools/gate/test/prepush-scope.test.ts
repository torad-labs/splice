// The pre-push scope, one pushed diff at a time: which ladder rows run and which gradle tasks start. Pure, so each case
// is a list of changed paths against the real module list and the committed scope file. The full suite is a path
// tools/gate/config/scope.json marks `full`.
import { describe, expect, test } from "bun:test";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { gradleModules, moduleOf, readModuleGraph } from "../src/commands/hook.ts";
import { withBuildDeps } from "../src/lib/project-deps.ts";
import { JAR_TASK, LAW_SUITES_TASK, type Leg, prePushScope } from "../src/lib/prepush-scope.ts";
import { layout } from "../src/lib/repo.ts";

const { repoRoot } = layout();
const found = gradleModules(repoRoot);
const modules = found.map((m) => m.path);
const moduleFor = (path: string) => moduleOf(found, path);

const LEGS: Leg[] = [
  { task: "walls", command: ["bun", "tools/gate", "rules"], inputs: ["quality/rules/**"] },
  { task: "gateTests", command: ["bun", "test", "tools/gate"], inputs: ["tools/gate/**"] },
  { task: "e2eSelftest", command: ["bun", "tools/e2e", "heads", "--selftest"], inputs: ["tools/e2e/**"] },
  { task: "oracleReplay", command: ["bun", "tools/e2e", "oracle"], dependsOn: [JAR_TASK], inputs: ["tools/e2e/**"] },
  { task: "prTitle", command: ["bun", "tools/gate", "title"], on: "push" },
];

const graph = withBuildDeps(found, readModuleGraph(repoRoot), (module) => {
  const file = join(repoRoot, module.dir, "build.gradle.kts");
  return existsSync(file) ? readFileSync(file, "utf8") : undefined;
});

test("an unlisted module's edges are the main project dependencies its build script declares", () => {
  const app = graph.get(":app");
  expect(app).toContain(":features-turns");
  expect(app).toContain(":core");
  expect(app).toContain(":integrations-http");
  expect(app).not.toContain(":quality-architecture");
  expect(app).not.toContain(":integrations-mcp-hub");
  expect(app).not.toContain(":quality-compiler-plugin");
});
const scope = (changed: readonly string[], legs: readonly Leg[] = LEGS) => prePushScope({ legs, modules, moduleOf: moduleFor, changed, graph });

const checks = (gradle: readonly string[]) => gradle.filter((task) => task.endsWith(":check"));
const decided = (changed: readonly string[], name: string, legs: readonly Leg[] = LEGS) =>
  scope(changed, legs).decisions.find((decision) => decision.name === name);

describe("a pushed diff scopes the gate to what it touches", () => {
  test("a one-module diff checks that module and the modules that depend on it, and not a row whose inputs miss it", () => {
    const s = scope(["features/turns/src/main/kotlin/splice/head/HeadDeps.kt"]);
    expect(checks(s.gradle)).toEqual([":app:check", ":features-turns:check"]);
    expect(s.gradle).not.toContain(":core:check");
    expect(s.legs.map((leg) => leg.task)).toEqual(["prTitle"]);
    expect(s.gradle).toContain(LAW_SUITES_TASK);
    expect(s.gradle).not.toContain(":oracleReplay");
    expect(s.gradle).not.toContain(JAR_TASK);
    expect(decided(["features/turns/src/main/kotlin/splice/head/HeadDeps.kt"], ":app")?.why).toContain(":features-turns");
    expect(decided(["features/turns/src/main/kotlin/splice/head/HeadDeps.kt"], ":core")?.selected).toBe(false);
  });

  test("a core diff checks core and every module that depends on it, and not a module that does not", () => {
    const s = scope(["core/src/main/kotlin/splice/core/config/Knob.kt"]);
    expect(checks(s.gradle)).toContain(":core:check");
    expect(checks(s.gradle)).toContain(":integrations-http:check");
    expect(checks(s.gradle)).toContain(":app:check");
    expect(checks(s.gradle)).not.toContain(":integrations-mcp-hub:check");
    expect(checks(s.gradle)).not.toContain(":quality-architecture:check");
    expect(checks(s.gradle)).not.toContain(":quality-compiler-plugin:check");
    expect(checks(s.gradle).length).toBeLessThan(modules.length);
  });

  test("a leaf module's diff checks that module and nothing that does not depend on it", () => {
    const s = scope(["features/turns/src/main/kotlin/splice/head/HeadDeps.kt"]);
    const dependents = checks(s.gradle);
    expect(dependents).toEqual([":app:check", ":features-turns:check"]);
    expect(dependents.length).toBeLessThan(modules.length);
  });

  test("a tools/e2e-only diff runs the rows that read tools/e2e, including the jar leg, and no module check", () => {
    const s = scope(["tools/e2e/src/commands/code-mode.ts"]);
    expect(checks(s.gradle)).toEqual([]);
    expect(s.gradle).toEqual([":oracleReplay"]);
    expect(s.direct.map((leg) => leg.task)).toEqual(["e2eSelftest", "prTitle"]);
    expect(s.gradle).not.toContain(LAW_SUITES_TASK);
  });

  test("a docs-only diff starts no gradle and runs only a row that judges the push itself", () => {
    const s = scope(["docs/specs/telemetry.md"]);
    expect(s.gradle).toEqual([]);
    expect(s.direct.map((leg) => leg.task)).toEqual(["prTitle"]);
    expect(s.summary).toContain("gradle: none (docs only)");
    expect(decided(["docs/specs/telemetry.md"], ":app")?.selected).toBe(false);
    expect(decided(["docs/specs/telemetry.md"], ":app")?.why).toContain("prose");
  });

  test("prose a law reads still runs the law suites and checks no module", () => {
    for (const path of ["README.md", "docs/PROVENANCE.md"]) {
      const s = scope([path]);
      expect(s.gradle, path).toEqual([LAW_SUITES_TASK]);
      expect(checks(s.gradle), path).toEqual([]);
    }
  });

  test("an app change checks :app, and the scope names the check, never a test task of its own", () => {
    const s = scope(["app/src/main/kotlin/splice/app/Daemon.kt"]);
    expect(checks(s.gradle)).toEqual([":app:check"]);
    expect(s.gradle.filter((task) => task === ":app:test" || task.startsWith("--tests"))).toEqual([]);
    expect(s.gradle).toContain(LAW_SUITES_TASK);
  });

  test("the scope names no test filter, whatever the push changed", () => {
    for (const path of ["README.md", "tools/e2e/src/commands/code-mode.ts", "features/turns/src/main/kotlin/splice/head/HeadDeps.kt", "LICENSE"]) {
      expect(
        scope([path]).gradle.some((task) => task.startsWith("--tests") || task.startsWith("--exclude-task")),
        path,
      ).toBe(false);
    }
  });

  test("the version catalog, the root build and settings check every module, and say why", () => {
    for (const path of ["settings.gradle.kts", "build.gradle.kts", "gradle/libs.versions.toml"]) {
      const s = scope([path]);
      expect(checks(s.gradle).length, path).toBe(modules.length);
      expect(decided([path], ":core")?.why, path).toContain(path);
    }
  });

  test("a module build file, a rule file, package.json and a build-logic test do not check every module", () => {
    const narrow: Record<string, string[]> = {
      "features/turns/build.gradle.kts": [":app:check", ":features-turns:check"],
      "quality/rules/kotlin/kt-no-println.yml": [],
      "package.json": [],
      "build-logic/src/test/kotlin/splice/ladder/LadderTableTest.kt": [],
      "build-logic/src/main/kotlin/splice.gate-ladder.gradle.kts": [],
      "gradle/module-law.txt": [],
    };
    for (const [path, expected] of Object.entries(narrow)) {
      expect(checks(scope([path]).gradle), path).toEqual(expected);
    }
  });

  test("a build-logic change tests build-logic, and a convention plugin every module applies checks every module", () => {
    const gate = scope(["build-logic/src/main/kotlin/splice.gate-ladder.gradle.kts"]);
    expect(gate.gradle).toContain("build-logic:test");
    expect(checks(gate.gradle)).toEqual([]);
    const common = scope(["build-logic/src/main/kotlin/splice.kotlin-common.gradle.kts"]);
    expect(checks(common.gradle).length).toBe(modules.length);
    expect(common.gradle).toContain("build-logic:test");
  });

  test("a row that reads a path the diff touches runs even when it needs gradle, and then gradle starts", () => {
    const gradleLeg: Leg = { task: "fatJar", command: ["bun", "tools/e2e", "conformance"], dependsOn: [":app:shadowJar"], inputs: ["tools/e2e/**"] };
    const s = scope(["tools/e2e/src/commands/conformance.ts"], [gradleLeg]);
    expect(s.gradle).toContain(":fatJar");
    expect(s.direct).toEqual([]);
  });

  test("a diff that no row reads and that no module or law changes runs no gradle", () => {
    const s = scope(["LICENSE"], [LEGS[1]!]);
    expect(s.legs).toEqual([]);
    expect(s.gradle).toEqual([]);
    expect(s.summary).toContain("no legs; gradle: none");
  });

  test("an input of ** selects nothing, and a row that judges the push itself still runs", () => {
    const legs: Leg[] = [
      { task: "walls", command: ["bun", "tools/gate", "rules"], inputs: ["**"] },
      { task: "prTitle", command: ["bun", "tools/gate", "title"], on: "push" },
    ];
    const s = scope(["features/turns/src/main/kotlin/splice/head/HeadDeps.kt"], legs);
    expect(s.legs.map((leg) => leg.task)).toEqual(["prTitle"]);
    expect(decided(["features/turns/src/main/kotlin/splice/head/HeadDeps.kt"], "walls", legs)?.why).toContain("**");
    expect(checks(s.gradle)).toEqual([":app:check", ":features-turns:check"]);
  });
});

describe("jar legs run when their inputs match", () => {
  test("a product change does not run a jar leg, and a change to the leg's inputs does", () => {
    const ladder = JSON.parse(readFileSync(`${repoRoot}/tools/gate/config/ladder.json`, "utf8")) as { legs: Leg[] };
    const jarLegs = ladder.legs.filter((leg) => leg.dependsOn?.includes(JAR_TASK) === true).map((leg) => leg.task);
    expect(jarLegs).toContain("launchOwnerPackagedProof");
    const product = scope(["features/turns/src/main/kotlin/x.kt"], ladder.legs);
    for (const task of jarLegs) expect(product.gradle, task).not.toContain(`:${task}`);
    const e2e = scope(["tools/e2e/src/commands/code-mode.ts"], ladder.legs);
    for (const task of jarLegs) expect(e2e.gradle, task).toContain(`:${task}`);
    for (const path of ["tools/gate/src/commands/hook.ts", "quality/architecture/src/test/kotlin/splice/quality/X.kt"]) {
      for (const task of jarLegs) expect(scope([path], ladder.legs).gradle, `${path} ${task}`).not.toContain(`:${task}`);
    }
  });
});
