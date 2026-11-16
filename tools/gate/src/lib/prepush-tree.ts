// The pre-push build tree: ONE detached worktree of the repository, made under the git directory for the pushed sha and
// removed when its gate ends. Pre-push judges the pushed commit there, never the shared checkout, so a session's
// half-finished edit cannot redden another session's push. A gate leaves nothing on the disk once it has its verdict:
// kept between pushes, the two trees held gigabytes of build output that only the next gate would read. What a later
// gate can reuse comes from Gradle's build cache, which Gradle trims by itself. A tree a killed gate left behind is
// moved to the next sha, without its untracked source, and goes when that gate ends. One lock serialises users of the
// tree. Node modules are linked from the shared checkout (the pinned ast-grep and the gate's own dependencies); every
// tracked file comes from the commit.
import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, rmSync, symlinkSync } from "node:fs";
import { isAbsolute, join, resolve } from "node:path";
import { takeExclusive } from "./flock.ts";

export interface PrePushTree {
  readonly path: string;
  /** Milliseconds spent moving the tree to the sha, the lock wait excluded. */
  readonly setupMs: number;
  /** Removes the tree, with every file its gate built, and frees its place for the next push. Safe to call twice. */
  readonly release: () => void;
}

const LOCK_WAIT_MS = 30 * 60 * 1000;

function git(cwd: string, args: readonly string[]): { status: number; stdout: string; stderr: string } {
  const proc = spawnSync("git", [...args], { cwd });
  return { status: proc.status ?? 1, stdout: proc.stdout?.toString().trim() ?? "", stderr: proc.stderr?.toString() ?? "" };
}

function must(cwd: string, args: readonly string[]): string {
  const run = git(cwd, args);
  if (run.status !== 0) throw new Error(`git ${args.join(" ")} failed: ${run.stderr.trim()}`);
  return run.stdout;
}

/** Where the ktlint Gradle plugin keeps what its lint step found: every finding named by absolute path, and read back by the
 *  next run for the files that did not change. A tree a killed gate left behind must not carry that run's, because one
 *  that was once restored from another tree's cache entry names that tree's files, and they would be reported in this
 *  tree's verdict. Gradle project directories sit at most three deep. */
const LINT_RESULTS = ["*/build/intermediates/ktLint", "*/*/build/intermediates/ktLint", "*/*/*/build/intermediates/ktLint"];

function dropLintResults(tree: string): void {
  for (const pattern of LINT_RESULTS) {
    for (const found of new Bun.Glob(pattern).scanSync({ cwd: tree, onlyFiles: false })) rmSync(join(tree, found), { recursive: true, force: true });
  }
}

/** The tree pre-push judges a pushed sha in. */
export const PRE_PUSH_TREE = "tree";
/** The tree a local `gate run` judges HEAD in. It is not the pre-push tree, so a half-hour gate never makes a push wait. */
export const GATE_TREE = "gate";

/** Where the build trees, their locks and the push lane live: under the git directory every worktree shares. */
export function prePushHome(repoRoot: string): string {
  const common = must(repoRoot, ["rev-parse", "--git-common-dir"]);
  const home = join(isAbsolute(common) ? common : resolve(repoRoot, common), "splice-prepush");
  mkdirSync(home, { recursive: true });
  return home;
}

/** Makes the tree [name] at [sha] and returns it, locked. Throws when git cannot, or when the lock stays busy. */
export function preparePrePushTree(repoRoot: string, sha: string, waitMs: number = LOCK_WAIT_MS, name: string = PRE_PUSH_TREE): PrePushTree {
  const home = prePushHome(repoRoot);
  const slot = takeExclusive(join(home, `${name}.lock`), waitMs);
  if (slot === undefined) throw new Error(`the ${name === PRE_PUSH_TREE ? "pre-push" : name} build tree stayed busy for ${Math.round(waitMs / 1000)} s`);
  return moveTree(repoRoot, home, name, sha, slot);
}

/** The pre-push tree made at [sha] and locked, when no one holds it now; undefined, at once, when someone does. Throws when git
 *  cannot make it. A caller that waits does so between calls, so its process keeps running while it waits. */
export function takePrePushTreeNow(repoRoot: string, sha: string): PrePushTree | undefined {
  const home = prePushHome(repoRoot);
  const slot = takeExclusive(join(home, `${PRE_PUSH_TREE}.lock`), 0);
  return slot === undefined ? undefined : moveTree(repoRoot, home, PRE_PUSH_TREE, sha, slot);
}

function moveTree(repoRoot: string, home: string, name: string, sha: string, slot: { release(): void }): PrePushTree {
  const started = performance.now();
  const path = join(home, name);
  try {
    if (existsSync(join(path, ".git"))) {
      must(path, ["checkout", "--detach", "--force", "-q", sha]);
      must(path, ["reset", "--hard", "-q", sha]);
      // A gate that was killed left this tree. Its untracked source goes here, and the rest of it when this gate ends.
      must(path, ["clean", "-fdq"]);
      dropLintResults(path);
    } else {
      // What is left of a tree whose removal was cut short is no worktree; it goes before the new one is made.
      rmSync(path, { recursive: true, force: true });
      git(repoRoot, ["worktree", "prune"]);
      must(repoRoot, ["worktree", "add", "--detach", "--force", path, sha]);
    }
    const modules = join(repoRoot, "node_modules");
    if (existsSync(modules) && !existsSync(join(path, "node_modules"))) symlinkSync(modules, join(path, "node_modules"));
  } catch (error) {
    slot.release();
    throw error;
  }
  let gone = false;
  const release = (): void => {
    if (gone) return;
    gone = true;
    try {
      removeTree(repoRoot, path);
    } finally {
      slot.release();
    }
  };
  return { path, setupMs: performance.now() - started, release };
}

/** Removes the tree at [path] and git's record of it. The link to the shared checkout's node modules is unlinked, never
 *  followed. */
function removeTree(repoRoot: string, path: string): void {
  rmSync(path, { recursive: true, force: true });
  git(repoRoot, ["worktree", "prune"]);
}
