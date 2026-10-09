// The throwaway tree pre-push judges: the pushed commit, checked out detached under /tmp, so a seat's half-finished edit in
// the shared checkout can never redden another seat's push. It is removed when the judgement ends, pass or fail, and on an
// interrupt. Node modules are linked from the shared checkout (the pinned ast-grep and the gate's own dependencies); every
// tracked file comes from the commit itself.
import { spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, rmSync, symlinkSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

export interface ThrowawayTree {
  readonly path: string;
  /** Removes the tree and its worktree registration. Safe to call twice. */
  readonly remove: () => void;
}

const SIGNALS = ["SIGINT", "SIGTERM", "SIGHUP"] as const;

function git(root: string, args: readonly string[]): { status: number; stderr: string } {
  const proc = spawnSync("git", [...args], { cwd: root });
  return { status: proc.status ?? 1, stderr: proc.stderr?.toString() ?? "" };
}

/** Checks [sha] out detached in a fresh directory under the temp dir. Throws when git cannot. */
export function createThrowawayTree(repoRoot: string, sha: string, parent: string = tmpdir()): ThrowawayTree {
  const path = mkdtempSync(join(parent, `splice-prepush-${sha.slice(0, 7)}-`));
  const added = git(repoRoot, ["worktree", "add", "--detach", "--force", path, sha]);
  if (added.status !== 0) {
    rmSync(path, { recursive: true, force: true });
    throw new Error(`git worktree add ${sha} failed: ${added.stderr.trim()}`);
  }
  const modules = join(repoRoot, "node_modules");
  if (existsSync(modules)) symlinkSync(modules, join(path, "node_modules"));

  let removed = false;
  const remove = (): void => {
    if (removed) return;
    removed = true;
    for (const signal of SIGNALS) process.off(signal, onSignal);
    process.off("exit", remove);
    git(repoRoot, ["worktree", "remove", "--force", path]);
    rmSync(path, { recursive: true, force: true });
    git(repoRoot, ["worktree", "prune"]);
  };
  // An interrupt removes the tree, then ends the process with the shell's 128+signal status.
  const onSignal = (signal: NodeJS.Signals): void => {
    remove();
    process.exit(128 + (signal === "SIGINT" ? 2 : signal === "SIGHUP" ? 1 : 15));
  };
  for (const signal of SIGNALS) process.on(signal, onSignal);
  process.on("exit", remove);
  return { path, remove };
}
