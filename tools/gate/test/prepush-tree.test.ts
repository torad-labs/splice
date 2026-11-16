// The build trees: the pushed commit (or the gated HEAD), clean, and gone with everything built in them once their gate ends.
import { describe, expect, test } from "bun:test";
import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { GATE_TREE, PRE_PUSH_TREE, prePushHome, preparePrePushTree } from "../src/lib/prepush-tree.ts";

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

/** The tree a gate that was killed left behind at [sha]: made as the gate makes it, and never released. */
function leftBehind(root: string, sha: string): string {
  const path = join(prePushHome(root), PRE_PUSH_TREE);
  sh(root, "worktree", "add", "--detach", "--force", path, sha);
  return path;
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
    writeFileSync(join(root, "a.txt"), "half-finished edit by another session\n");
    writeFileSync(join(root, "untracked.txt"), "not committed\n");
    const tree = preparePrePushTree(root, sha);
    expect(readFileSync(join(tree.path, "a.txt"), "utf8")).toBe("first\n");
    expect(existsSync(join(tree.path, "untracked.txt"))).toBe(false);
    tree.release();
    rmSync(root, { recursive: true, force: true });
  });

  test("is gone once its gate ends, with everything the gate built in it, and the next push gets a clean one", () => {
    const root = repo();
    const first = sh(root, "rev-parse", "HEAD");
    const one = preparePrePushTree(root, first);
    mkdirSync(join(one.path, "build"));
    writeFileSync(join(one.path, "build", "out.class"), "compiled\n");
    one.release();
    expect(existsSync(one.path)).toBe(false);
    expect(sh(root, "worktree", "list", "--porcelain")).not.toContain(one.path);
    const two = preparePrePushTree(root, second(root));
    expect(two.path).toBe(one.path);
    expect(readFileSync(join(two.path, "a.txt"), "utf8")).toBe("second\n");
    expect(existsSync(join(two.path, "build"))).toBe(false);
    two.release();
    two.release();
    expect(existsSync(two.path)).toBe(false);
    rmSync(root, { recursive: true, force: true });
  });

  test("leaves the shared checkout's node modules where they are: the tree only links to them", () => {
    const root = repo();
    const sha = sh(root, "rev-parse", "HEAD");
    mkdirSync(join(root, "node_modules", "kept"), { recursive: true });
    writeFileSync(join(root, "node_modules", "kept", "index.js"), "module.exports = 1;\n");
    const tree = preparePrePushTree(root, sha);
    expect(readFileSync(join(tree.path, "node_modules", "kept", "index.js"), "utf8")).toBe("module.exports = 1;\n");
    tree.release();
    expect(readFileSync(join(root, "node_modules", "kept", "index.js"), "utf8")).toBe("module.exports = 1;\n");
    rmSync(root, { recursive: true, force: true });
  });

  test("a tree a killed gate left behind is moved to the next sha without its untracked source, and goes when that gate ends", () => {
    const root = repo();
    const left = leftBehind(root, sh(root, "rev-parse", "HEAD"));
    writeFileSync(join(left, "stray.kt"), "left over from a push\n");
    mkdirSync(join(left, "build"));
    writeFileSync(join(left, "build", "out.class"), "compiled\n");
    const tree = preparePrePushTree(root, second(root));
    expect(tree.path).toBe(left);
    expect(readFileSync(join(tree.path, "a.txt"), "utf8")).toBe("second\n");
    expect(existsSync(join(tree.path, "stray.kt"))).toBe(false);
    tree.release();
    expect(existsSync(left)).toBe(false);
    rmSync(root, { recursive: true, force: true });
  });

  test("a tree a killed gate left behind loses that run's lint findings, which name files by absolute path", () => {
    const root = repo();
    const left = leftBehind(root, sh(root, "rev-parse", "HEAD"));
    for (const module of ["app", "features/turns", "integrations/dialects/openai-responses"]) {
      mkdirSync(join(left, module, "build", "intermediates", "ktLint"), { recursive: true });
      writeFileSync(join(left, module, "build", "intermediates", "ktLint", "runKtlintCheckOverMainSourceSet_errors.bin"), "/tmp/b3-tree2/app/A.kt:48:1\n");
    }
    const tree = preparePrePushTree(root, second(root));
    for (const module of ["app", "features/turns", "integrations/dialects/openai-responses"]) {
      expect(existsSync(join(tree.path, module, "build", "intermediates", "ktLint"))).toBe(false);
    }
    tree.release();
    rmSync(root, { recursive: true, force: true });
  });

  test("what is left of a tree whose removal was cut short is replaced by a whole one", () => {
    const root = repo();
    const sha = sh(root, "rev-parse", "HEAD");
    const path = join(prePushHome(root), PRE_PUSH_TREE);
    mkdirSync(join(path, "build"), { recursive: true });
    writeFileSync(join(path, "build", "out.class"), "compiled\n");
    const tree = preparePrePushTree(root, sha);
    expect(readFileSync(join(tree.path, "a.txt"), "utf8")).toBe("first\n");
    expect(existsSync(join(tree.path, "build"))).toBe(false);
    tree.release();
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
