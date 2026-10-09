// The pre-push scope, one pushed diff at a time: which ladder rows run and which gradle tasks start. Pure, so each case
// is a list of changed paths against the real module list. The full suite is CI's: these cases pin what a push judges.
import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { gradleModules, moduleOf, readModuleGraph } from "../src/commands/hook.ts";
import { JAR_TASK, LAW_SUITES_TASK, type Leg, legsWithoutInputs, prePushScope } from "../src/lib/prepush-scope.ts";
import { layout } from "../src/lib/repo.ts";

const { repoRoot } = layout();
const found = gradleModules(repoRoot);
const modules = found.map((m) => m.path);
const moduleFor = (path: string) => moduleOf(found, path);

const LEGS: Leg[] = [
  { task: "census", command: ["bun", ".dev/restructure/census.ts"], inputs: ["**"] },
  { task: "gateTests", command: ["bun", "test", "tools/gate"], inputs: ["tools/gate/**"] },
  { task: "e2eSelftest", command: ["bun", "tools/e2e", "heads", "--selftest"], inputs: ["tools/e2e/**"] },
  { task: "oracleReplay", command: ["bun", "tools/e2e", "oracle"], dependsOn: [JAR_TASK], inputs: ["tools/e2e/**"] },
];

const graph = readModuleGraph(repoRoot);
const scope = (changed: readonly string[], legs: readonly Leg[] = LEGS) => prePushScope({ legs, modules, moduleOf: moduleFor, changed, graph });

const checks = (gradle: readonly string[]) => gradle.filter((task) => task.endsWith(":check"));
/** Every push requests the law suites and the jar legs; gradle decides which of them run. lawSuites runs the whole :app:test,
 *  so the public-source test runs on every push inside it. */
const EVERY_PUSH = [LAW_SUITES_TASK];

describe("a pushed diff scopes the gate to what it touches", () => {
  test("a one-module diff checks that module and its dependents (the unrestricted :app among them), and runs the rows that read it", () => {
    const s = scope(["features/turns/src/main/kotlin/splice/head/HeadDeps.kt"]);
    expect(checks(s.gradle)).toEqual([":app:check", ":features-turns:check"]);
    expect(s.gradle).not.toContain(":core:compileKotlin");
    expect(s.gradle).not.toContain(":features-events:check");
    expect(s.legs.map((leg) => leg.task)).toEqual(["census"]);
    expect(s.gradle).not.toContain(":oracleReplay");
    expect(s.gradle).not.toContain(":gateTests");
    expect(s.direct.map((leg) => leg.task)).toEqual(["census"]);
  });

  test("a core diff checks core and every module that depends on it", () => {
    const s = scope(["core/src/main/kotlin/splice/core/config/Knob.kt"]);
    expect(checks(s.gradle)).toContain(":core:check");
    expect(checks(s.gradle)).toContain(":integrations-http:check");
  });

  test("a leaf module's diff checks that module and nothing that does not depend on it", () => {
    const s = scope(["features/turns/src/main/kotlin/splice/head/HeadDeps.kt"]);
    const dependents = checks(s.gradle);
    expect(dependents).toContain(":features-turns:check");
    expect(dependents).not.toContain(":core:check");
    expect(dependents.length).toBeLessThan(modules.length);
  });

  test("a tools/e2e-only diff runs only the rows that read tools/e2e, and the public-source test", () => {
    const s = scope(["tools/e2e/src/commands/code-mode.ts"]);
    expect(s.gradle).toEqual([...EVERY_PUSH, ":oracleReplay"]);
    expect(s.direct.map((leg) => leg.task)).toEqual(["census", "e2eSelftest"]);
    expect(s.summary).toContain("PublicSourceNamesNoHostToolTest in lawSuites");
  });

  test("a docs-only diff starts no gradle and runs only the rows that read every path", () => {
    const s = scope(["docs/specs/telemetry.md"]);
    expect(s.gradle).toEqual([]);
    expect(s.direct.map((leg) => leg.task)).toEqual(["census"]);
  });

  test("prose a law reads still runs the law suites", () => {
    expect(scope(["README.md"]).gradle).toContain(LAW_SUITES_TASK);
    expect(scope(["docs/PROVENANCE.md"]).gradle).toContain(LAW_SUITES_TASK);
  });

  test("an app change checks :app, and lawSuites still runs the whole :app:test once", () => {
    const s = scope(["app/src/main/kotlin/splice/app/Daemon.kt"]);
    expect(s.gradle).toContain(":app:check");
    expect(s.gradle.filter((task) => task === ":app:test" || task.startsWith("--tests"))).toEqual([]);
    expect(s.gradle).toContain(LAW_SUITES_TASK);
  });

  test("no push narrows :app:test with a --tests filter, because lawSuites shares that task instance", () => {
    for (const path of ["README.md", "tools/e2e/src/commands/code-mode.ts", "features/turns/src/main/kotlin/splice/head/HeadDeps.kt", "LICENSE"]) {
      expect(scope([path]).gradle.some((task) => task.startsWith("--tests")), path).toBe(false);
    }
  });

  test("the settings, the root build script and the catalog check every module", () => {
    for (const path of ["settings.gradle.kts", "build.gradle.kts", "gradle/libs.versions.toml"]) {
      const s = scope([path]);
      expect(checks(s.gradle).length, path).toBe(modules.length);
    }
  });

  test("a build-logic change tests build-logic and checks every module", () => {
    const s = scope(["build-logic/src/main/kotlin/splice.gate-ladder.gradle.kts"]);
    expect(s.gradle).toContain("build-logic:test");
    expect(checks(s.gradle).length).toBe(modules.length);
  });

  test("a row that reads a path the diff touches runs even when it needs gradle, and then gradle starts", () => {
    const gradleLeg: Leg = { task: "fatJar", command: ["bun", "tools/e2e", "conformance"], dependsOn: [":app:shadowJar"], inputs: ["tools/e2e/**"] };
    const s = scope(["tools/e2e/src/commands/conformance.ts"], [gradleLeg]);
    expect(s.gradle).toContain(":fatJar");
    expect(s.direct).toEqual([]);
  });

  test("a diff that no row reads and that no module changes runs only lawSuites", () => {
    const s = scope(["LICENSE"], [LEGS[1]!]);
    expect(s.legs).toEqual([]);
    expect(s.gradle).toEqual(EVERY_PUSH);
    expect(s.summary).toContain("no legs; gradle: check of no module, lawSuites");
  });
});

describe("the law suites and the jar legs are requested on every push, and gradle decides which run", () => {
  test("every push requests lawSuites, whatever it changed", () => {
    for (const path of ["README.md", "features/turns/src/main/kotlin/splice/head/HeadDeps.kt", "LICENSE"]) {
      expect(scope([path]).gradle, path).toContain(LAW_SUITES_TASK);
    }
  });

  test("a jar leg runs when its own inputs change and not on a Kotlin push", () => {
    const ladder = JSON.parse(readFileSync(`${repoRoot}/tools/gate/config/ladder.json`, "utf8")) as { legs: Leg[] };
    const jarLegs = ladder.legs.filter((leg) => leg.dependsOn?.includes(JAR_TASK) === true).map((leg) => leg.task);
    expect(jarLegs.length).toBeGreaterThan(0);
    for (const task of jarLegs) expect(scope(["features/turns/src/main/kotlin/x.kt"], ladder.legs).gradle, task).not.toContain(`:${task}`);
    const e2e = scope(["tools/e2e/src/commands/code-mode.ts"], ladder.legs);
    for (const task of jarLegs) expect(e2e.gradle, task).toContain(`:${task}`);
  });

  test("the jar's own inputs are not copied into the rows: a jar leg declares only the paths it reads itself", () => {
    const ladder = JSON.parse(readFileSync(`${repoRoot}/tools/gate/config/ladder.json`, "utf8")) as { legs: Leg[] };
    for (const leg of ladder.legs.filter((row) => row.dependsOn?.includes(JAR_TASK) === true)) {
      for (const glob of leg.inputs ?? []) expect(glob.startsWith("core/") || glob.startsWith("features/") || glob.startsWith("integrations/"), `${leg.task}: ${glob}`).toBe(false);
    }
  });
});

describe("a row without inputs cannot be scoped", () => {
  test("the rows that declare no inputs are named", () => {
    const bare: Leg = { task: "bareRow", command: ["bun", "tools/gate", "title"] };
    const empty: Leg = { task: "emptyRow", command: ["bun", "tools/gate", "title"], inputs: [] };
    expect(legsWithoutInputs([...LEGS, bare, empty])).toEqual(["bareRow", "emptyRow"]);
    expect(legsWithoutInputs(LEGS)).toEqual([]);
  });
});
