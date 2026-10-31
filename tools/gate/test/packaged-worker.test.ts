// :app:codeModePackagedTest reruns the code-mode suites against the shipped fat jar. A push runs it only when something it
// changes can change what the code-mode worker loads from that jar (load cut 4 of the temple plan of 2026-10-19, approved by
// Marcos). The paths that decide were signed off by splice-lead on 2026-10-26:
//   - anything in a module the worker runs: the module law's closure of :integrations-codemode and :integrations-providers-codex;
//   - the build's own definition: every *.gradle.kts, gradle/, every gradle.properties, build-logic/, the compiler plugin, and the
//     gate's JDK pick;
//   - an app test the packaged run executes, by the names its filter lists, or every app test when that list cannot be read;
//   - a main resource the fat jar carries, except one in another package's splice/<x>/ folder and the console's own files;
//   - a main or testFixtures source whose package is one the worker's modules ship under splice/, or one outside splice.<x>.
// Everything else (the console, a feature module's code and tests, the gate, docs) leaves the worker as it was.
import { describe, expect, test } from "bun:test";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { gradleModules, readModuleGraph } from "../src/commands/hook.ts";
import { packagedTestReason } from "../src/lib/packaged-worker.ts";
import { layout } from "../src/lib/repo.ts";

const { repoRoot } = layout();
const modules = gradleModules(repoRoot);
const graph = readModuleGraph(repoRoot);

/** The paths that keep the packaged test, each with the text it holds when the judgement has to read it. */
const KEEPS: Readonly<Record<string, string | undefined>> = {
  "integrations/codemode/src/main/kotlin/splice/codemode/AnyWorkerFile.kt": undefined,
  "integrations/codemode/src/test/kotlin/splice/codemode/AnyRuntimeTest.kt": undefined,
  "core/src/main/kotlin/splice/core/AnyCoreFile.kt": undefined,
  "integrations/upstream/src/main/resources/splice/upstream/codemode/any.js": undefined,
  "integrations/providers/codex/src/main/kotlin/splice/provider/codex/AnyCodexFile.kt": undefined,
  "integrations/dialects/openai-responses/src/main/kotlin/splice/dialect/AnyDialectFile.kt": undefined,
  "settings.gradle.kts": undefined,
  "build.gradle.kts": undefined,
  "features/turns/build.gradle.kts": undefined,
  "gradle/libs.versions.toml": undefined,
  "gradle.properties": undefined,
  "features/turns/gradle.properties": undefined,
  "build-logic/src/main/kotlin/splice.kotlin-common.gradle.kts": undefined,
  "quality/compiler-plugin/src/main/kotlin/splice/firchecks/AnyCheck.kt": undefined,
  "tools/gate/src/lib/jdk.ts": undefined,
  "app/src/test/kotlin/splice/app/CodeModeLanguagesTest.kt": undefined,
  "app/src/test/kotlin/splice/app/Slf4jProviderTest.kt": undefined,
  "app/src/test/kotlin/splice/app/RenamedFile.kt": "package splice.app\n\nclass CodeModeWorkerBootTest {\n}\n",
  "app/src/main/resources/logback.xml": undefined,
  "app/src/main/resources/META-INF/services/org.slf4j.spi.SLF4JServiceProvider": undefined,
  "features/lifecycle/src/main/resources/splice/codemode/any.js": undefined,
  "features/turns/src/main/kotlin/splice/head/WorkerShadow.kt": "package splice.codemode\n\nclass WorkerSession\n",
  "integrations/providers/grok/src/main/kotlin/splice/provider/grok/AnyGrokFile.kt": "package splice.provider.grok\n",
  "app/src/main/kotlin/splice/app/OutsideSplice.kt": "/* a header */\npackage org.slf4j.impl\n",
  "app/src/main/java/splice/app/JavaInWorkerPackage.java": "package splice.upstream;\n\nclass X {}\n",
  "features/turns/src/testFixtures/kotlin/splice/head/FixtureInCore.kt": "package splice.core.fake\n",
  "features/turns/src/main/kotlin/splice/head/NoPackage.kt": "class NoPackage\n",
  "features/turns/src/main/kotlin/splice/head/Unreadable.kt": undefined,
};

/** The paths that leave the worker as it was. */
const SKIPS: Readonly<Record<string, string | undefined>> = {
  "docs/specs/v0.4.0.md": undefined,
  "tools/gate/src/commands/hook.ts": undefined,
  "quality/rules/kotlin/any-rule.yml": undefined,
  "app/src/main/resources/console/settings.js": undefined,
  "app/src/main/resources/manager-home/index.html": undefined,
  "app/src/main/resources/splice/mcp/any.json": undefined,
  "features/lifecycle/src/main/resources/splice/lifecycle/any.service": undefined,
  "features/turns/src/main/kotlin/splice/head/turn/AnyTurnFile.kt": "package splice.head.turn\n",
  "app/src/main/kotlin/splice/app/AnyAppFile.kt": "// the daemon\n@file:JvmName(\"AnyAppFile\")\n\npackage splice.app\n",
  "app/src/test/kotlin/splice/app/AnyDaemonTest.kt": "package splice.app\n\nclass AnyDaemonTest\n",
  "features/turns/src/test/kotlin/splice/head/AnyHeadTest.kt": "package splice.codemode\n",
  "integrations/http/src/main/java/splice/http/AnyJavaFile.java": "package splice.http;\n",
};

/** None of the example paths is in the tree: each is judged by the text it held before the push, as a deleted file is. */
const before = (path: string): string | undefined => KEEPS[path] ?? SKIPS[path];

const reason = (changed: readonly string[]): string | undefined =>
  packagedTestReason({ root: repoRoot, modules, graph, changed, before });

describe("a push runs the packaged code-mode test only when it can change what the worker loads", () => {
  test("each path that can change the worker keeps the test, and the reason names it", () => {
    for (const path of Object.keys(KEEPS)) expect(reason([path]), path).toContain(path);
  });

  test("each path that cannot change the worker skips the test", () => {
    for (const path of Object.keys(SKIPS)) expect(reason([path]), path).toBeUndefined();
  });

  test("a push keeps the test exactly when one of its paths does, whatever else it changes and in any order", () => {
    // Property over generated pushes: the verdict is one path's verdict OR the next's, so no path hides another.
    const pool = [...Object.keys(KEEPS), ...Object.keys(SKIPS)];
    let seed = 0x5eed;
    const random = (): number => {
      seed = (seed + 0x6d2b79f5) | 0;
      let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
    for (let run = 0; run < 300; run++) {
      const push = pool.filter(() => random() < 0.08).sort(() => random() - 0.5);
      const keeping = push.filter((path) => path in KEEPS);
      const said = reason(push);
      if (keeping.length === 0) expect(said, push.join(" ")).toBeUndefined();
      else expect(keeping.some((path) => said?.includes(path)), `${push.join(" ")} -> ${said}`).toBe(true);
    }
  });
});

describe("what the judgement cannot read keeps the packaged test", () => {
  /** A tree holding only [files], judged with the real module list and law unless given others. */
  const tree = (files: Record<string, string>): string => {
    const root = mkdtempSync(join(tmpdir(), "splice-packaged-"));
    for (const [path, text] of Object.entries(files)) {
      mkdirSync(dirname(join(root, path)), { recursive: true });
      writeFileSync(join(root, path), text);
    }
    return root;
  };

  test("an app test keeps it when the packaged run's test list is not a list of class names", () => {
    const root = tree({ "app/build.gradle.kts": 'val codeModePackagedTest = tasks.register<Test>("codeModePackagedTest") {\n    filter {\n        includeTestsMatching("splice.app.*")\n    }\n}\n' });
    const path = "app/src/test/kotlin/splice/app/AnyDaemonTest.kt";
    expect(packagedTestReason({ root, modules, graph, changed: [path], before })).toContain(path);
  });

  test("an app test keeps it when the build declares no packaged run to read", () => {
    const root = tree({ "app/build.gradle.kts": "plugins {\n    application\n}\n" });
    const path = "app/src/test/kotlin/splice/app/AnyDaemonTest.kt";
    expect(packagedTestReason({ root, modules, graph, changed: [path], before })).toContain(path);
  });

  test("any push keeps it when the law does not list a module the worker runs, since such a module may depend on anything", () => {
    const unlisted = new Map([...graph].filter(([module]) => module !== ":integrations-upstream"));
    expect(packagedTestReason({ root: repoRoot, modules, graph: unlisted, changed: ["docs/specs/v0.4.0.md"], before })).toContain(":integrations-upstream");
  });

  test("any push keeps it when the settings map no directory for a module the worker runs", () => {
    const unmapped = modules.filter((module) => module.path !== ":integrations-codemode");
    expect(packagedTestReason({ root: repoRoot, modules: unmapped, graph, changed: ["docs/specs/v0.4.0.md"], before })).toContain(":integrations-codemode");
  });
});
