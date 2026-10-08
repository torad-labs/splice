// The census a push runs judges the pushed tip, never the checkout. The checkout's index holds other seats' staged files
// and their unclaimed rows, so a census that reads it refuses every push while one seat has a file in flight. These
// tests run census.ts in a scratch repository: the tip census (--rev HEAD) reads the commit's tree and rows only.
import { afterAll, describe, expect, test } from "bun:test";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";

const CENSUS = join(import.meta.dir, "..", "..", "..", ".dev", "restructure", "census.ts");
const HEADER = "source\tdisposition\tdestination\treason";
const scratch: string[] = [];

afterAll(() => {
  for (const dir of scratch) rmSync(dir, { recursive: true, force: true });
});

function git(root: string, args: readonly string[]): void {
  const r = Bun.spawnSync(["git", "-C", root, ...args], { stdout: "pipe", stderr: "pipe" });
  if (r.exitCode !== 0) throw new Error(`git ${args.join(" ")}: ${r.stderr.toString()}`);
}

function write(root: string, path: string, content: string): void {
  mkdirSync(join(root, dirname(path)), { recursive: true });
  writeFileSync(join(root, path), content);
}

/** A scratch repository whose one committed product file, core/A.kt, has its row, committed with the census script. */
function repo(): string {
  const root = mkdtempSync(join(tmpdir(), "splice-census-tip-"));
  scratch.push(root);
  git(root, ["init", "-q"]);
  git(root, ["config", "user.email", "census@test.invalid"]);
  git(root, ["config", "user.name", "census test"]);
  write(root, "core/A.kt", "class A\n");
  write(root, ".dev/restructure/capabilities.tsv", `${HEADER}\ncore/A.kt\tcreated\t\tsynthetic claim\n`);
  git(root, ["add", "-A"]);
  git(root, ["commit", "-q", "-m", "base"]);
  return root;
}

function census(root: string, args: readonly string[]): { status: number; out: string } {
  const r = Bun.spawnSync(["bun", CENSUS, "--root", root, ...args], { cwd: root, stdout: "pipe", stderr: "pipe" });
  return { status: r.exitCode ?? -1, out: r.stdout.toString() + r.stderr.toString() };
}

describe("the census a push runs judges the pushed tip, not the checkout", () => {
  test("RED: a file another seat staged, with no row, does not block the tip census", () => {
    const root = repo();
    write(root, "core/B.kt", "class B\n");
    git(root, ["add", "core/B.kt"]);
    const tip = census(root, ["--rev", "HEAD"]);
    expect(tip.out).not.toContain("core/B.kt");
    expect(tip.status).toBe(0);
    // The checkout's index still names it: this is the refusal every push hit while the file was in flight.
    expect(census(root, []).out).toContain("unclaimed: core/B.kt");
  });

  test("RED: an untracked product file with no row does not block the tip census", () => {
    const root = repo();
    write(root, "core/D.kt", "class D\n");
    const tip = census(root, ["--rev", "HEAD"]);
    expect(tip.status).toBe(0);
    expect(tip.out).not.toContain("core/D.kt");
  });

  test("a file committed with no row fails the tip census by name", () => {
    const root = repo();
    write(root, "core/C.kt", "class C\n");
    git(root, ["add", "core/C.kt"]);
    git(root, ["commit", "-q", "-m", "a file with no row"]);
    const tip = census(root, ["--rev", "HEAD"]);
    expect(tip.status).toBe(1);
    expect(tip.out).toContain("unclaimed: core/C.kt");
  });

  test("the tip census reads the rows the commit holds, not an uncommitted edit to them", () => {
    const root = repo();
    write(root, "core/C.kt", "class C\n");
    git(root, ["add", "core/C.kt"]);
    git(root, ["commit", "-q", "-m", "a file with no row"]);
    write(root, ".dev/restructure/capabilities.tsv", `${HEADER}\ncore/A.kt\tcreated\t\tsynthetic claim\ncore/C.kt\tcreated\t\tclaimed only in the worktree\n`);
    const tip = census(root, ["--rev", "HEAD"]);
    expect(tip.status).toBe(1);
    expect(tip.out).toContain("unclaimed: core/C.kt");
  });
});
