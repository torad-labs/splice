// `gate title` — the shapes are derived from the ONE list (never restated here: the
// one-conventional-type-list wall reds this file too if three types appear in a row).
import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { title } from "../src/commands/title.ts";
import { TYPES, conventionalType } from "../src/lib/conventional.ts";

const [first, second] = TYPES.split("|") as [string, string];
const quiet = { log: console.log, error: console.error };
beforeEach(() => {
  console.log = () => {};
  console.error = () => {};
});
afterEach(() => {
  console.log = quiet.log;
  console.error = quiet.error;
});

function repo(...subjects: string[]): string {
  const dir = mkdtempSync(join(tmpdir(), "gate-title-"));
  const git = (...args: string[]) => {
    const proc = Bun.spawnSync(["git", "-C", dir, ...args], { stdout: "pipe", stderr: "pipe" });
    if (proc.exitCode !== 0) throw new Error(`git ${args.join(" ")}: ${proc.stderr.toString()}`);
  };
  git("init", "-q");
  git("config", "user.email", "t@t");
  git("config", "user.name", "t");
  for (const subject of subjects) git("commit", "-q", "--allow-empty", "-m", subject);
  return dir;
}

describe("the one type list", () => {
  test("has the org gate's shape: type, optional scope, optional bang, colon, subject", () => {
    expect(conventionalType(`${first}: subject`)).toBe(first);
    expect(conventionalType(`${second}(scope)!: subject`)).toBe(second);
    expect(conventionalType(`${first}(scope): subject`)).toBe(first);
  });

  test("rejects types that read well but are not on the list, and malformed shapes", () => {
    expect(conventionalType("harden(walls): subject")).toBeNull();
    expect(conventionalType(`${first}subject`)).toBeNull();
    expect(conventionalType(`${first}:subject`)).toBeNull();
    expect(conventionalType(`${first}(): subject`)).toBeNull();
    expect(conventionalType(`${first.toUpperCase()}: subject`)).toBeNull();
  });
});

describe("gate title", () => {
  test("an explicit title is judged anywhere, CI included", async () => {
    const env = { GITHUB_ACTIONS: "true" };
    expect(await title([`${first}(scope): subject`], { env })).toBe(0);
    expect(await title(["harden(walls): subject"], { env })).toBe(1);
  });

  test("with no argument in Actions it defers to the org gate", async () => {
    expect(await title([], { env: { GITHUB_ACTIONS: "true" }, cwd: "/nonexistent" })).toBe(0);
  });

  test("with no argument locally it judges HEAD's subject", async () => {
    const good = repo(`${first}(x): landed`);
    const bad = repo("verify(x): landed");
    try {
      expect(await title([], { env: {}, cwd: good })).toBe(0);
      expect(await title([], { env: {}, cwd: bad })).toBe(1);
    } finally {
      rmSync(good, { recursive: true, force: true });
      rmSync(bad, { recursive: true, force: true });
    }
  });

  test("a local merge tip is skipped — nothing authored to judge", async () => {
    const dir = repo(`${first}: base`);
    const git = (...args: string[]) => Bun.spawnSync(["git", "-C", dir, ...args], { stdout: "pipe", stderr: "pipe" });
    try {
      git("checkout", "-q", "-b", "side");
      git("commit", "-q", "--allow-empty", "-m", "not conventional at all");
      git("checkout", "-q", "-");
      git("commit", "-q", "--allow-empty", "-m", `${second}: main moves on`);
      git("merge", "-q", "--no-ff", "-m", "Merge branch side", "side");
      expect(git("rev-list", "--parents", "-n1", "HEAD").stdout.toString().trim().split(/\s+/).length).toBe(3);
      expect(await title([], { env: {}, cwd: dir })).toBe(0);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  test("no title and no commit to read is a failure, not a pass", async () => {
    const empty = repo();
    try {
      expect(await title([], { env: {}, cwd: empty })).toBe(1);
    } finally {
      rmSync(empty, { recursive: true, force: true });
    }
  });

  test("more than one argument is a usage error", async () => {
    expect(await title([`${first}: a`, "b"])).toBe(2);
  });
});
