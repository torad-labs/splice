// The throwaway tree pre-push judges: the pushed commit, clean, removed when the judgement ends.
import { describe, expect, test } from "bun:test";
import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createThrowawayTree } from "../src/lib/throwaway.ts";

const sh = (root: string, ...args: string[]): string => {
  const proc = spawnSync("git", ["-c", "user.name=t", "-c", "user.email=t@t", ...args], { cwd: root });
  return proc.stdout.toString().trim();
};

function repoWithCommit(): { root: string; sha: string } {
  const root = mkdtempSync(join(tmpdir(), "splice-throwaway-test-"));
  sh(root, "init", "-q");
  writeFileSync(join(root, "a.txt"), "committed\n");
  sh(root, "add", "a.txt");
  sh(root, "commit", "-q", "-m", "chore(test): base");
  return { root, sha: sh(root, "rev-parse", "HEAD") };
}

describe("the throwaway tree", () => {
  test("holds the committed bytes, not the shared checkout's uncommitted edits, and is gone after remove", () => {
    const { root, sha } = repoWithCommit();
    writeFileSync(join(root, "a.txt"), "half-finished edit by another seat\n");
    writeFileSync(join(root, "untracked.txt"), "not committed\n");
    const tree = createThrowawayTree(root, sha);
    expect(readFileSync(join(tree.path, "a.txt"), "utf8")).toBe("committed\n");
    expect(existsSync(join(tree.path, "untracked.txt"))).toBe(false);
    tree.remove();
    expect(existsSync(tree.path)).toBe(false);
    expect(sh(root, "worktree", "list")).not.toContain(tree.path);
    tree.remove();
    rmSync(root, { recursive: true, force: true });
  });

  test("links the shared node_modules so the pinned tools resolve", () => {
    const { root, sha } = repoWithCommit();
    mkdirSync(join(root, "node_modules"));
    writeFileSync(join(root, "node_modules", "marker"), "shared\n");
    const tree = createThrowawayTree(root, sha);
    expect(readFileSync(join(tree.path, "node_modules", "marker"), "utf8")).toBe("shared\n");
    tree.remove();
    rmSync(root, { recursive: true, force: true });
  });

  test("a commit git cannot find is an error and leaves no directory behind", () => {
    const { root } = repoWithCommit();
    const before = sh(root, "worktree", "list");
    expect(() => createThrowawayTree(root, "1111111111111111111111111111111111111111")).toThrow(/worktree add/);
    expect(sh(root, "worktree", "list")).toBe(before);
    rmSync(root, { recursive: true, force: true });
  });
});
