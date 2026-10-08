// Red/green proof for the git hooks (`bun tools/gate hook pre-commit|pre-push|install`).
//
// The pre-commit arms run the REAL judgement over a scratch repository that holds only the walls. Its staged
// blob is the violation and its worktree copy is clean, so a block can come only from reading the index, which is
// what a commit judges; a pass after the staged blob is fixed shows the same path goes green. The scratch
// repository is a fresh `git init` in tmp with no commits and no copy of the shared checkout, and its
// node_modules is a symlink to the real one, so the pinned ast-grep is the one the gate runs.
//
// The pre-push arm refuses a tip that is not HEAD before any gradle leg, so it builds nothing.
import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, dirname, join } from "node:path";
import {
  failedTasks,
  gradleModules,
  installShims,
  judge,
  moduleOf,
  namedFiles,
  preCommit,
  prePush,
  SHIM_BEGIN,
  shimText,
} from "../src/commands/hook.ts";
import { layout } from "../src/lib/repo.ts";

const { repoRoot } = layout();

// The same L3 wall fixtures the write-time hook test uses: VIOLATION holds `message_stop` outside the sole
// emitter (error severity); CLEAN is clean under every wall. The path is in scope and not the emitter.
const TARGET = "core/src/main/kotlin/splice/core/Probe.kt";
const VIOLATION = 'class Probe {\n    fun endTurn(out: Writer) {\n        out.write("message_stop")\n    }\n}\n';
const CLEAN = "class Probe {\n    fun endTurn(out: Writer) {\n        out.close()\n    }\n}\n";

const scratch: string[] = [];
const dir = (prefix: string): string => {
  const path = mkdtempSync(join(tmpdir(), prefix));
  scratch.push(path);
  return path;
};

function git(cwd: string, args: readonly string[]): void {
  const proc = Bun.spawnSync(["git", ...args], { cwd, stdout: "pipe", stderr: "pipe" });
  if (proc.exitCode !== 0) throw new Error(`git ${args.join(" ")}: ${proc.stderr.toString()}`);
}

/** A scratch repository holding the walls and nothing else: no settings.gradle.kts, so no gradle leg runs. */
function wallsRepo(): string {
  const root = dir("splice-hook-git-");
  git(root, ["init", "-q"]);
  cpSync(join(repoRoot, "sgconfig.yml"), join(root, "sgconfig.yml"));
  cpSync(join(repoRoot, "quality", "rules"), join(root, "quality", "rules"), { recursive: true });
  symlinkSync(join(repoRoot, "node_modules"), join(root, "node_modules"));
  return root;
}

function writeTarget(root: string, content: string): void {
  const path = join(root, TARGET);
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, content);
}

afterAll(() => {
  for (const path of scratch) rmSync(path, { recursive: true, force: true });
});

describe("pre-commit judges the index the commit is writing", () => {
  let red: string;
  beforeAll(() => {
    red = wallsRepo();
  });

  test("RED: a staged violation blocks the commit even when the worktree copy is clean", async () => {
    writeTarget(red, VIOLATION);
    git(red, ["add", TARGET]);
    writeTarget(red, CLEAN); // the worktree now holds the clean form; only the index holds the violation
    expect(await preCommit({ repoRoot: red, buildRoot: red })).toBe(1);
  });

  test("GREEN: the same path, staged in its fixed form, passes", async () => {
    writeTarget(red, CLEAN);
    git(red, ["add", TARGET]);
    expect(await preCommit({ repoRoot: red, buildRoot: red })).toBe(0);
  });

  test("a commit with no Kotlin in it passes without running a scan", async () => {
    const empty = wallsRepo();
    writeFileSync(join(empty, "README.md"), "docs\n");
    git(empty, ["add", "README.md"]);
    expect(await preCommit({ repoRoot: empty, buildRoot: empty })).toBe(0);
  });
});

describe("a failing file blocks unless another seat has it in flight", () => {
  const commit = new Set(["core/A.kt"]);
  const dirty = new Set(["core/A.kt", "core/Seat.kt"]);

  test("a file the commit changes blocks", () => {
    expect(judge(new Set(["core/A.kt"]), commit, dirty)).toEqual({ blocking: ["core/A.kt"], advisory: [] });
  });

  test("a clean file the commit does not touch blocks: the caller a contract change broke", () => {
    expect(judge(new Set(["integrations/B.kt"]), commit, dirty)).toEqual({ blocking: ["integrations/B.kt"], advisory: [] });
  });

  test("a file another seat has uncommitted edits in is printed and does not block", () => {
    expect(judge(new Set(["core/Seat.kt"]), commit, dirty)).toEqual({ blocking: [], advisory: ["core/Seat.kt"] });
  });

  test("a mix names each file on its own side", () => {
    expect(judge(new Set(["core/Seat.kt", "integrations/B.kt"]), commit, dirty)).toEqual({
      blocking: ["integrations/B.kt"],
      advisory: ["core/Seat.kt"],
    });
  });
});

describe("the gradle tasks a run reports as failed", () => {
  test("are read by path, and a compile task is told apart from the rest", () => {
    const output = "> Task :core:compileKotlin FAILED\n> Task :core:detekt FAILED\n> Task :core:test\n";
    expect(failedTasks(output)).toEqual([":core:compileKotlin", ":core:detekt"]);
  });
});

describe("pre-push judges the tip", () => {
  const head = Bun.spawnSync(["git", "rev-parse", "HEAD"], { cwd: repoRoot, stdout: "pipe" }).stdout.toString().trim();
  const notHead = "1111111111111111111111111111111111111111";

  test("a pushed tip that is not the worktree's HEAD is refused before any gate runs", async () => {
    const stdin = `refs/heads/feat/x ${notHead} refs/heads/feat/x 0000000000000000000000000000000000000000\n`;
    expect(await prePush(layout(), stdin)).toBe(1);
  });

  test("a push of deletions only has nothing to judge", async () => {
    const stdin = `(delete) 0000000000000000000000000000000000000000 refs/heads/gone ${head}\n`;
    expect(await prePush(layout(), stdin)).toBe(0);
  });
});

describe("hook install", () => {
  test("writes both shims, executable, byte-for-byte the shim text, and a second install changes nothing", () => {
    const hooks = dir("splice-hooks-");
    writeFileSync(join(hooks, "post-commit"), "# >>> fence >>>\nfence body\n");
    const written = installShims(hooks, "/usr/bin/bun", "/repo/tools/gate/index.ts");
    expect(written.map((p) => basename(p))).toEqual(["pre-commit", "pre-push"]);
    for (const verb of ["pre-commit", "pre-push"]) {
      expect(readFileSync(join(hooks, verb), "utf8")).toBe(shimText("/usr/bin/bun", "/repo/tools/gate/index.ts", verb));
      expect(statSync(join(hooks, verb)).mode & 0o111).not.toBe(0);
    }
    installShims(hooks, "/usr/bin/bun", "/repo/tools/gate/index.ts");
    expect(readFileSync(join(hooks, "pre-commit"), "utf8")).toContain(SHIM_BEGIN);
    expect(readFileSync(join(hooks, "post-commit"), "utf8")).toBe("# >>> fence >>>\nfence body\n");
  });

  test("refuses a hook it did not write, and writes neither shim", () => {
    const hooks = dir("splice-hooks-");
    writeFileSync(join(hooks, "pre-push"), "#!/bin/sh\necho mine\n");
    expect(() => installShims(hooks, "/usr/bin/bun", "/repo/tools/gate/index.ts")).toThrow("not a splice gate shim");
    expect(existsSync(join(hooks, "pre-commit"))).toBe(false);
    expect(readFileSync(join(hooks, "pre-push"), "utf8")).toBe("#!/bin/sh\necho mine\n");
  });

  test("a bun or gate path with a single quote stays one shell word", () => {
    expect(shimText("/opt/bun's/bun", "/r/index.ts", "pre-push")).toContain(`exec '/opt/bun'\\''s/bun' '/r/index.ts' hook pre-push "$@"`);
  });
});

describe("the gradle legs' attribution", () => {
  test("a file belongs to the module with the longest directory prefix", () => {
    const modules = [
      { path: ":app", dir: "app" },
      { path: ":app:core", dir: "app/core" },
    ];
    expect(moduleOf(modules, "app/core/X.kt")).toBe(":app:core");
    expect(moduleOf(modules, "app/Y.kt")).toBe(":app");
    expect(moduleOf(modules, "other/Z.kt")).toBeUndefined();
  });

  test("the files a failure names are the repository's own, relative to the root", () => {
    const root = "/work/repo";
    const output = [
      "e: file:///work/repo/core/src/A.kt:12:5 Unresolved reference 'x'",
      "/work/repo/core/src/B.kt:3:1: Detekt(MaxLineLength)",
      "/elsewhere/C.kt:1:1: outside the repository",
    ].join("\n");
    expect([...namedFiles(output, root)].sort()).toEqual(["core/src/A.kt", "core/src/B.kt"]);
  });

  test("the modules come from settings.gradle.kts's projectDir lines", () => {
    const root = dir("splice-settings-");
    writeFileSync(
      join(root, "settings.gradle.kts"),
      'include(":core")\nproject(":core").projectDir = file("core")\nproject(":features:turns").projectDir = file("features/turns/")\n',
    );
    expect(gradleModules(root)).toEqual([
      { path: ":core", dir: "core" },
      { path: ":features:turns", dir: "features/turns" },
    ]);
  });
});
