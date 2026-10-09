// The persistent build trees: the pushed commit (or the gated HEAD), clean, with build output kept between runs.
import { describe, expect, test } from "bun:test";
import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { GATE_TREE, preparePrePushTree } from "../src/lib/prepush-tree.ts";

const sh = (root: string, ...args: string[]): string => {
  const proc = spawnSync("git", ["-c", "user.name=t", "-c", "user.email=t@t", ...args], { cwd: root });
  return proc.stdout.toString().trim();
};

function repo(): string {
  const root = mkdtempSync(join(tmpdir(), "splice-prepush-tree-test-"));
  sh(root, "init", "-q");
  writeFileSync(join(root, ".gitignore"), "build/\nnode_modules/\n");
  writeFileSync(join(root, "a.txt"), "first\n");
  sh(root, "add", ".gitignore", "a.txt");
  sh(root, "commit", "-q", "-m", "chore(test): first");
  return root;
}

function second(root: string): string {
  writeFileSync(join(root, "a.txt"), "second\n");
  sh(root, "commit", "-q", "-am", "chore(test): second");
  return sh(root, "rev-parse", "HEAD");
}

describe("the pre-push build tree", () => {
  test("holds the committed bytes, not the shared checkout's uncommitted edits", () => {
    const root = repo();
    const sha = sh(root, "rev-parse", "HEAD");
    writeFileSync(join(root, "a.txt"), "half-finished edit by another seat\n");
    writeFileSync(join(root, "untracked.txt"), "not committed\n");
    const tree = preparePrePushTree(root, sha);
    expect(readFileSync(join(tree.path, "a.txt"), "utf8")).toBe("first\n");
    expect(existsSync(join(tree.path, "untracked.txt"))).toBe(false);
    tree.release();
    rmSync(root, { recursive: true, force: true });
  });

  test("moves to the next sha, drops untracked source, and keeps the build output", () => {
    const root = repo();
    const first = sh(root, "rev-parse", "HEAD");
    const one = preparePrePushTree(root, first);
    mkdirSync(join(one.path, "build"));
    writeFileSync(join(one.path, "build", "out.class"), "compiled\n");
    writeFileSync(join(one.path, "stray.kt"), "left over from a push\n");
    one.release();
    const next = second(root);
    const two = preparePrePushTree(root, next);
    expect(two.path).toBe(one.path);
    expect(readFileSync(join(two.path, "a.txt"), "utf8")).toBe("second\n");
    expect(existsSync(join(two.path, "stray.kt"))).toBe(false);
    expect(readFileSync(join(two.path, "build", "out.class"), "utf8")).toBe("compiled\n");
    two.release();
    rmSync(root, { recursive: true, force: true });
  });

  test("one push at a time: a second user waits, then is refused when the tree stays busy", () => {
    const root = repo();
    const sha = sh(root, "rev-parse", "HEAD");
    const held = preparePrePushTree(root, sha);
    expect(() => preparePrePushTree(root, sha, 150)).toThrow(/stayed busy/);
    held.release();
    preparePrePushTree(root, sha, 150).release();
    rmSync(root, { recursive: true, force: true });
  });

  test("the gate's tree is a different tree with its own lock, so a running gate never makes a push wait", () => {
    const root = repo();
    const sha = sh(root, "rev-parse", "HEAD");
    const gate = preparePrePushTree(root, sha, 150, GATE_TREE);
    const push = preparePrePushTree(root, sha, 150);
    expect(gate.path).not.toBe(push.path);
    expect(readFileSync(join(gate.path, "a.txt"), "utf8")).toBe("first\n");
    expect(() => preparePrePushTree(root, sha, 150, GATE_TREE)).toThrow(/gate build tree stayed busy/);
    push.release();
    gate.release();
    rmSync(root, { recursive: true, force: true });
  });

  test("a commit git cannot find is an error and frees the tree", () => {
    const root = repo();
    const sha = sh(root, "rev-parse", "HEAD");
    expect(() => preparePrePushTree(root, "1111111111111111111111111111111111111111")).toThrow(/worktree add/);
    preparePrePushTree(root, sha, 150).release();
    rmSync(root, { recursive: true, force: true });
  });
});
