// The pre-push build tree: ONE persistent detached worktree of the repository, kept under the git directory and moved to
// each pushed sha. Pre-push judges the pushed commit there, never the shared checkout, so a seat's half-finished edit
// cannot redden another seat's push. The tree is persistent on purpose: untracked source is removed on every move, but
// the ignored build outputs, Kotlin's incremental state and gradle's caches stay, so the next push compiles and tests only
// what its commit changed. One lock serialises users of the tree. Node modules are linked from the shared checkout (the
// pinned ast-grep and the gate's own dependencies); every tracked file comes from the commit.
import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, symlinkSync } from "node:fs";
import { isAbsolute, join, resolve } from "node:path";
import { takeExclusive } from "./flock.ts";

export interface PrePushTree {
  readonly path: string;
  /** Milliseconds spent moving the tree to the sha, the lock wait excluded. */
  readonly setupMs: number;
  /** Frees the tree for the next push. Safe to call twice. */
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

/** The tree pre-push judges a pushed sha in. */
export const PRE_PUSH_TREE = "tree";
/** The tree a local `gate run` judges HEAD in. It is not the pre-push tree, so a half-hour gate never makes a push wait. */
export const GATE_TREE = "gate";

/** Moves the persistent tree [name] to [sha] and returns it, locked. Throws when git cannot, or when the lock stays busy. */
export function preparePrePushTree(repoRoot: string, sha: string, waitMs: number = LOCK_WAIT_MS, name: string = PRE_PUSH_TREE): PrePushTree {
  const common = must(repoRoot, ["rev-parse", "--git-common-dir"]);
  const home = join(isAbsolute(common) ? common : resolve(repoRoot, common), "splice-prepush");
  mkdirSync(home, { recursive: true });
  const slot = takeExclusive(join(home, `${name}.lock`), waitMs);
  if (slot === undefined) throw new Error(`the ${name === PRE_PUSH_TREE ? "pre-push" : name} build tree stayed busy for ${Math.round(waitMs / 1000)} s`);
  const started = performance.now();
  const path = join(home, name);
  try {
    if (existsSync(join(path, ".git"))) {
      must(path, ["checkout", "--detach", "--force", "-q", sha]);
      must(path, ["reset", "--hard", "-q", sha]);
      // Untracked source goes; ignored build output and caches stay.
      must(path, ["clean", "-fdq"]);
    } else {
      git(repoRoot, ["worktree", "prune"]);
      must(repoRoot, ["worktree", "add", "--detach", "--force", path, sha]);
    }
    const modules = join(repoRoot, "node_modules");
    if (existsSync(modules) && !existsSync(join(path, "node_modules"))) symlinkSync(modules, join(path, "node_modules"));
  } catch (error) {
    slot.release();
    throw error;
  }
  return { path, setupMs: performance.now() - started, release: () => slot.release() };
}
