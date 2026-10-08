// The pre-push scope, one pushed diff at a time: which ladder rows run and which gradle tasks start. Pure, so each case
// is a list of changed paths against the real module list. The full suite is CI's: these cases pin what a push judges.
import { describe, expect, test } from "bun:test";
import { gradleModules, moduleOf } from "../src/commands/hook.ts";
import { LAW_READS, type Leg, legsWithoutInputs, prePushScope } from "../src/lib/prepush-scope.ts";
import { layout } from "../src/lib/repo.ts";

const { repoRoot } = layout();
const found = gradleModules(repoRoot);
const modules = found.map((m) => m.path);
const moduleFor = (path: string) => moduleOf(found, path);

const LEGS: Leg[] = [
  { task: "census", command: ["bun", ".dev/restructure/census.ts"], inputs: ["**"] },
  { task: "gateTests", command: ["bun", "test", "tools/gate"], inputs: ["tools/gate/**"] },
  { task: "e2eSelftest", command: ["bun", "tools/e2e", "heads", "--selftest"], inputs: ["tools/e2e/**"] },
  { task: "oracleReplay", command: ["bun", "tools/e2e", "oracle"], dependsOn: [":app:shadowJar"], inputs: ["core/**", "features/turns/**"] },
];

const scope = (changed: readonly string[], lawReads: Record<string, readonly string[]> = {}, legs: readonly Leg[] = LEGS) =>
  prePushScope({ legs, modules, moduleOf: moduleFor, lawReads, changed });

const checks = (gradle: readonly string[]) => gradle.filter((task) => task.endsWith(":check"));
/** Every push runs this test: its gradle tasks, when no check of :app already runs it. */
const PUBLIC_SOURCE = [":app:test", "--tests=*PublicSourceNamesNoHostToolTest"];

describe("a pushed diff scopes the gate to what it touches", () => {
  test("a one-module diff checks that module, compiles every module, and runs the rows that read it", () => {
    const s = scope(["features/turns/src/main/kotlin/splice/head/HeadDeps.kt"]);
    expect(checks(s.gradle)).toEqual([":features-turns:check"]);
    expect(s.gradle).toContain(":core:compileKotlin");
    expect(s.gradle).toContain(":features-events:compileTestKotlin");
    expect(s.legs.map((leg) => leg.task)).toEqual(["census", "oracleReplay"]);
    expect(s.gradle).toContain(":oracleReplay");
    expect(s.gradle).not.toContain(":gateTests");
    expect(s.direct.map((leg) => leg.task)).toEqual(["census"]);
  });

  test("a core diff checks core and leaves its dependents' tests alone; every module still compiles", () => {
    const s = scope(["core/src/main/kotlin/splice/core/config/Knob.kt"]);
    expect(checks(s.gradle)).toEqual([":core:check"]);
    expect(s.gradle).not.toContain(":integrations-http:check");
    expect(s.gradle).toContain(":integrations-http:compileKotlin");
    expect(s.gradle).toContain(":integrations-http:compileTestKotlin");
  });

  test("a tools/e2e-only diff runs only the rows that read tools/e2e, and the public-source test", () => {
    const s = scope(["tools/e2e/src/commands/code-mode.ts"]);
    expect(s.gradle).toEqual(PUBLIC_SOURCE);
    expect(s.direct.map((leg) => leg.task)).toEqual(["census", "e2eSelftest"]);
    expect(s.summary).toContain("PublicSourceNamesNoHostToolTest via :app:test");
  });

  test("a docs-only diff runs only the rows that read every path, and the public-source test", () => {
    const s = scope(["docs/README.md"]);
    expect(s.gradle).toEqual(PUBLIC_SOURCE);
    expect(s.direct.map((leg) => leg.task)).toEqual(["census"]);
  });

  test("an app change checks :app, which runs the public-source test itself", () => {
    const s = scope(["app/src/main/kotlin/splice/app/Daemon.kt"]);
    expect(s.gradle).toContain(":app:check");
    expect(s.gradle).not.toContain(":app:test");
    expect(s.summary).toContain("PublicSourceNamesNoHostToolTest in :app:check");
  });

  test("a law suite runs when a path it reads changes, and not otherwise", () => {
    const reads = { ":quality-architecture": ["**/*.kt"] };
    expect(checks(scope(["features/turns/src/main/kotlin/splice/head/HeadDeps.kt"], reads).gradle)).toEqual([
      ":features-turns:check",
      ":quality-architecture:check",
    ]);
    expect(checks(scope(["docs/README.md"], reads).gradle)).toEqual([]);
  });

  test("a law suite runs when a path it reads changes, even when that path needs no compile", () => {
    const s = scope(["README.md"], { ":quality-architecture": ["README.md"] });
    expect(checks(s.gradle)).toEqual([":quality-architecture:check"]);
    expect(s.gradle.some((task) => task.endsWith(":compileKotlin"))).toBe(false);
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
    const s = scope(["tools/e2e/src/commands/conformance.ts"], {}, [gradleLeg]);
    expect(s.gradle).toContain(":fatJar");
    expect(s.direct).toEqual([]);
  });

  test("a diff that no row reads and that no module changes runs only the public-source test", () => {
    const s = scope(["LICENSE"], {}, [LEGS[1]!]);
    expect(s.legs).toEqual([]);
    expect(s.gradle).toEqual(PUBLIC_SOURCE);
    expect(s.summary).toContain("no legs; gradle: no compile, check of no module");
  });
});

describe("the law suites read what LAW_READS says they read", () => {
  test("every law suite it names is a module of this build", () => {
    for (const module of Object.keys(LAW_READS)) expect(modules, module).toContain(module);
  });

  test("a README change runs the suites that read the root README, with no compile", () => {
    const s = scope(["README.md"], LAW_READS);
    expect(checks(s.gradle)).toEqual([":integrations-topology:check", ":quality-architecture:check"]);
    expect(s.gradle.some((task) => task.endsWith(":compileKotlin"))).toBe(false);
  });

  test("a docs file no suite reads runs no suite", () => {
    expect(checks(scope(["docs/README.md"], LAW_READS).gradle)).toEqual([]);
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
