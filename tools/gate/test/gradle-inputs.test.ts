// The probes that need the real gradle wrapper: each runs gradle, through the slot, in a scratch repository, to prove a declaration
// gradle reads (the architecture suite's tracked-paths fingerprint, the law suites' tracked-file inputs). They check gradle, not the
// git hooks, so they live apart from git-hooks.test.ts: a push that changes the hook runs the hook's tests against a stub gradle and
// starts no real gradle (load cut 5 of the temple plan of 2026-10-19). They run when this file or what it imports changes, and in
// the full gate.
import { afterAll, describe, expect, test } from "bun:test";
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { resolveJdk21 } from "../src/lib/jdk.ts";
import { layout } from "../src/lib/repo.ts";

const { repoRoot } = layout();

const MODULE_SETTINGS = 'rootProject.name = "scratch"\ninclude(":core")\nproject(":core").projectDir = file("core")\n';

const scratch: string[] = [];
const dir = (prefix: string): string => {
  const path = mkdtempSync(join(tmpdir(), prefix));
  scratch.push(path);
  return path;
};

// Fixtures read no ambient git config: not the operator's global hooks, filters or identity, and not the system's.
// Git's own switches point at an empty file and no system config for this run, and are put back after it.
const ambientGit = { GIT_CONFIG_GLOBAL: Bun.env.GIT_CONFIG_GLOBAL, GIT_CONFIG_NOSYSTEM: Bun.env.GIT_CONFIG_NOSYSTEM };
const EMPTY_GIT_CONFIG = join(dir("splice-probe-config-"), "gitconfig");
writeFileSync(EMPTY_GIT_CONFIG, "");
Bun.env.GIT_CONFIG_GLOBAL = EMPTY_GIT_CONFIG;
Bun.env.GIT_CONFIG_NOSYSTEM = "1";

afterAll(() => {
  for (const path of scratch) rmSync(path, { recursive: true, force: true });
  for (const [key, value] of Object.entries(ambientGit)) {
    if (value === undefined) delete Bun.env[key];
    else Bun.env[key] = value;
  }
});

function git(cwd: string, args: readonly string[]): void {
  const proc = Bun.spawnSync(["git", ...args], { cwd, stdout: "pipe", stderr: "pipe" });
  if (proc.exitCode !== 0) throw new Error(`git ${args.join(" ")}: ${proc.stderr.toString()}`);
}

/** A commit with the fixture's own identity and no signing. It runs the hooks the fixture has, and no others. */
function commit(root: string, subject: string): void {
  git(root, ["-c", "user.name=test", "-c", "user.email=test@test", "-c", "commit.gpgsign=false", "commit", "-q", "-m", subject]);
}

/** A scratch repository with the walls, one gradle module (`:core`), and the real ast-grep, as git-hooks.test.ts builds it. Its
 *  scaffold is one base commit, and the symlinks and gradle's output are excluded, so every dirty path a probe sees is its own. */
function wallsRepo(): string {
  const root = dir("splice-probe-git-");
  git(root, ["init", "-q"]);
  writeFileSync(join(root, ".git", "info", "exclude"), "node_modules\ngradlew\ngradle\n.gradle\nbuild\n");
  cpSync(join(repoRoot, "sgconfig.yml"), join(root, "sgconfig.yml"));
  cpSync(join(repoRoot, "quality", "rules"), join(root, "quality", "rules"), { recursive: true });
  symlinkSync(join(repoRoot, "node_modules"), join(root, "node_modules"));
  writeFileSync(join(root, "settings.gradle.kts"), MODULE_SETTINGS);
  mkdirSync(join(root, "gradle"), { recursive: true });
  writeFileSync(join(root, "gradle", "module-law.txt"), ":core ->\n");
  git(root, ["add", "--", "sgconfig.yml", "quality", "settings.gradle.kts"]);
  git(root, ["add", "-f", "--", "gradle/module-law.txt"]);
  commit(root, "chore(test): scratch scaffold");
  git(root, ["update-ref", "refs/remotes/origin/main", "HEAD"]);
  return root;
}

/** The real gradle wrapper, reachable from a scratch repository by symlink (its jar and distribution are the repo's). */
function linkWrapper(root: string): void {
  symlinkSync(join(repoRoot, "gradlew"), join(root, "gradlew"));
  rmSync(join(root, "gradle"), { recursive: true, force: true });
  symlinkSync(join(repoRoot, "gradle"), join(root, "gradle"));
}

function writeFile(root: string, path: string, content: string): void {
  mkdirSync(dirname(join(root, path)), { recursive: true });
  writeFileSync(join(root, path), content);
}

/** The real gradle wrapper, run through the gradle slot in a scratch repository: the declaration under test is what gradle reads
 *  there. The slot is runUnderSlot, the function `bun tools/gate slot` calls. The verb finds its layout from its own location, so a
 *  child process runs the function on the scratch layout, and the slot's lock and holder file are the scratch repository's own, so
 *  this run never queues behind or overwrites a seat's build. */
function gradleHere(root: string): (tasks: readonly string[]) => Promise<{ status: number; output: string }> {
  return async (tasks) => {
    const jdk = resolveJdk21();
    if ("error" in jdk) throw new Error(jdk.error);
    const runner = join(dir("splice-probe-slot-"), "run-slot.ts");
    writeFileSync(
      runner,
      [
        `import { runUnderSlot } from ${JSON.stringify(join(repoRoot, "tools", "gate", "src", "lib", "slot.ts"))};`,
        `const status = await runUnderSlot({`,
        `  layout: { repoRoot: ${JSON.stringify(root)}, buildRoot: ${JSON.stringify(root)} },`,
        `  label: "root-script-test",`,
        `  args: ${JSON.stringify(tasks)},`,
        `  env: { JAVA_HOME: ${JSON.stringify(jdk.javaHome)}, GRADLE_SLOT_LOCK: ${JSON.stringify(join(root, ".gradle-slot.lock"))} },`,
        `});`,
        `process.exit(status);`,
      ].join("\n"),
    );
    const proc = Bun.spawn([process.execPath, runner], { cwd: root, stdout: "pipe", stderr: "pipe" });
    const [out, err] = await Promise.all([new Response(proc.stdout).text(), new Response(proc.stderr).text()]);
    return { status: await proc.exited, output: out + err };
  };
}

describe("the architecture suite fingerprints WHICH paths are tracked, not git's index bytes", () => {
  /** A real gradle run in a scratch repository whose task declares the very `trackedPathsDigest` the architecture suite
   *  declares (read out of quality/architecture/build.gradle.kts, so a changed declaration is the one tested). */
  function trackedPathsRepo(rawIndex = false): string {
    const declaration = /val trackedPathsDigest = [\s\S]*?\n\}\n/.exec(
      readFileSync(join(repoRoot, "quality", "architecture", "build.gradle.kts"), "utf8"),
    )?.[0];
    if (declaration === undefined) throw new Error("quality/architecture/build.gradle.kts declares no trackedPathsDigest");
    const root = wallsRepo();
    linkWrapper(root);
    writeFile(root, "settings.gradle.kts", 'rootProject.name = "scratch"\n');
    writeFile(
      root,
      "build.gradle.kts",
      [
        "import java.security.MessageDigest",
        "val repoRoot = layout.projectDirectory",
        declaration,
        'tasks.register("probe") {',
        rawIndex ? '    inputs.files(repoRoot.file(".git/index"))' : '    inputs.property("trackedPaths", trackedPathsDigest)',
        '    val stamp = layout.buildDirectory.file("probe.stamp")',
        "    outputs.file(stamp)",
        '    doLast { stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ran") }',
        "}",
      ].join("\n"),
    );
    writeFile(root, "docs/tracked.md", "one\n");
    git(root, ["add", "--", "settings.gradle.kts", "build.gradle.kts", "docs/tracked.md"]);
    commit(root, "chore(test): tracked fixture");
    return root;
  }

  async function probe(root: string): Promise<string> {
    const run = await gradleHere(root)(["probe"]);
    expect(run.status).toBe(0);
    return run.output;
  }

  test("RED: staging a content change to an already tracked file leaves the suite UP-TO-DATE; adding or removing a tracked path reruns it", async () => {
    const root = trackedPathsRepo();
    expect(await probe(root)).not.toContain(":probe UP-TO-DATE");
    expect(await probe(root)).toContain(":probe UP-TO-DATE");

    // `git add` rewrites the index but git's tracked-path list is byte-identical.
    writeFile(root, "docs/tracked.md", "two\n");
    git(root, ["add", "docs/tracked.md"]);
    expect(await probe(root)).toContain(":probe UP-TO-DATE");

    writeFile(root, "docs/new.md", "new\n");
    git(root, ["add", "docs/new.md"]);
    expect(await probe(root)).not.toContain(":probe UP-TO-DATE");

    git(root, ["rm", "-q", "--cached", "docs/new.md"]);
    expect(await probe(root)).not.toContain(":probe UP-TO-DATE");
  }, 600_000);
});

describe("a text-scanning law's content inputs are every tracked file, declared from git's tracked list", () => {
  /** build-logic's classes, compiled by gradle from a scratch copy of build-logic's sources, so nothing outside the
   *  copy moves while the probes run. Never the live build folder: the pre-push runs this suite beside its gradle leg in
   *  the same tree, and that leg recompiles build-logic whenever one of its inputs changes, a version-catalog bump
   *  included. A class file rewritten between two probes reran both tasks where the second had to be UP-TO-DATE
   *  (d0b487075's red gateTests). Kotlin empties the output folder before it writes, so a copy taken during that compile
   *  held no ReadSet and the probe's script failed on "Unresolved reference 'splice'" (a647799d3's red gateTests). A copy
   *  taken before it would have tested the previous sha's classes. */
  async function buildLogicClasses(): Promise<string> {
    const scratch = dir("build-logic-copy-");
    // The copy reads the version catalog at ../gradle/libs.versions.toml, as build-logic/settings.gradle.kts does.
    linkWrapper(scratch);
    const copy = join(scratch, "build-logic");
    for (const entry of ["src", "build.gradle.kts", "settings.gradle.kts"]) {
      cpSync(join(repoRoot, "build-logic", entry), join(copy, entry), { recursive: true });
    }
    cpSync(join(repoRoot, "gradle.properties"), join(copy, "gradle.properties"));
    symlinkSync(join(repoRoot, "gradlew"), join(copy, "gradlew"));
    // The real compile checks build-logic's downloads against the root's verification metadata, so the copy does too.
    // Only that file: a gradle/libs.versions.toml beside it would be imported as the catalog a second time.
    mkdirSync(join(copy, "gradle"), { recursive: true });
    symlinkSync(join(repoRoot, "gradle", "verification-metadata.xml"), join(copy, "gradle", "verification-metadata.xml"));
    const run = await gradleHere(copy)(["classes"]);
    expect(run.status, run.output).toBe(0);
    return join(copy, "build", "classes", "kotlin", "main");
  }

  /** A real gradle run in a scratch repository: the build-logic classes the quality build uses (ReadSet) declare
   *  every tracked file as the inputs of a stamped task. */
  async function candidatesRepo(): Promise<string> {
    const classes = await buildLogicClasses();
    const root = wallsRepo();
    linkWrapper(root);
    writeFile(root, "settings.gradle.kts", 'rootProject.name = "scratch"\n');
    writeFile(
      root,
      "build.gradle.kts",
      [
        "buildscript { dependencies { classpath(files(" + JSON.stringify(classes) + ")) } }",
        "val tracked = splice.lawsuite.ReadSet.trackedProvider(project)",
        'val probe = tasks.register("probe") {',
        '    val stamp = layout.buildDirectory.file("probe.stamp")',
        "    outputs.file(stamp)",
        '    doLast { stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ran") }',
        "}",
        'splice.lawsuite.ReadSet.declareInputs(project, probe, tracked, "candidates")',
      ].join("\n"),
    );
    writeFile(root, "docs/read.md", "one\n");
    writeFile(root, "assets/logo.png", "png one\n");
    writeFile(root, "build/kept.md", "a tracked file under a build directory\n");
    git(root, ["add", "-f", "--", "settings.gradle.kts", "build.gradle.kts", "docs/read.md", "assets/logo.png", "build/kept.md"]);
    commit(root, "chore(test): candidates fixture");
    return root;
  }

  async function probe(root: string): Promise<string> {
    const run = await gradleHere(root)(["probe"]);
    expect(run.status, run.output).toBe(0);
    return run.output;
  }

  test("RED: a content change to ANY tracked file reruns the task, a binary or a build-directory file included; an untracked change does not", async () => {
    const root = await candidatesRepo();
    expect(await probe(root)).not.toContain(":probe UP-TO-DATE");
    expect(await probe(root)).toContain(":probe UP-TO-DATE");

    writeFile(root, "untracked.md", "not tracked\n");
    expect(await probe(root)).toContain(":probe UP-TO-DATE");

    for (const [path, text] of [["docs/read.md", "two\n"], ["assets/logo.png", "png two\n"], ["build/kept.md", "kept two\n"]] as const) {
      writeFile(root, path, text);
      git(root, ["add", "-f", path]);
      expect(await probe(root)).not.toContain(":probe UP-TO-DATE");
      expect(await probe(root)).toContain(":probe UP-TO-DATE");
    }
  }, 600_000);
});
