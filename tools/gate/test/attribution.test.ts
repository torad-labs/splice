// `gate attribution` — each shape the 2026-09-24 rewrite removed goes red BY SHA, prose that only
// mentions a trailer passes, and a shallow clone is deepened before it is judged (the CI shape).
import { afterAll, afterEach, beforeEach, describe, expect, test } from "bun:test";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { attribution, scan } from "../src/commands/attribution.ts";

const quiet = { log: console.log, error: console.error };
let errors: string[] = [];
beforeEach(() => {
  errors = [];
  console.log = () => {};
  console.error = (...parts: unknown[]) => void errors.push(parts.join(" "));
});
afterEach(() => {
  console.log = quiet.log;
  console.error = quiet.error;
});
const made: string[] = [];
afterAll(() => made.forEach((dir) => rmSync(dir, { recursive: true, force: true })));

function scratch(prefix: string): string {
  const dir = mkdtempSync(join(tmpdir(), prefix));
  made.push(dir);
  return dir;
}

function git(dir: string, ...args: string[]): string {
  const proc = Bun.spawnSync(["git", "-C", dir, ...args], { stdout: "pipe", stderr: "pipe" });
  if (proc.exitCode !== 0) throw new Error(`git ${args.join(" ")}: ${proc.stderr.toString()}`);
  return proc.stdout.toString().trim();
}

function repo(...messages: string[]): string {
  const dir = scratch("gate-attribution-");
  git(dir, "init", "-q");
  git(dir, "config", "user.email", "t@t");
  git(dir, "config", "user.name", "t");
  for (const message of messages) git(dir, "commit", "-q", "--allow-empty", "-m", message);
  return dir;
}

const SHAPES: Record<string, string> = {
  "co-authored-by": "fix: a\n\nCo-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>",
  "lowercase co-authored-by": "fix: a\n\nCo-authored-by: Claude Fable 5.1 <noreply@anthropic.com> ",
  "session trailer": "fix: a\n\nClaude-Session: https://claude.ai/code/session_01AbCdEfGhIjKlMnOpQrStUv",
  "session trailer without a url": "fix: a\n\nClaude-Session: session_01AbCdEfGhIjKlMnOpQrStUv",
  "bare session url": "fix: a\n\nhttps://claude.ai/code/session_01AbCdEfGhIjKlMnOpQrStUv",
  "generated with": "fix: a\n\n\u{1f916} Generated with [Claude Code](https://claude.com/claude-code)",
};

describe("gate attribution", () => {
  test("a clean history passes", async () => {
    expect(await attribution([], { cwd: repo("feat: one", "fix: two\n\nbody") })).toBe(0);
  });

  for (const [name, message] of Object.entries(SHAPES)) {
    test(`${name} in an OLDER commit fails, naming its sha`, async () => {
      const dir = repo("feat: first", message, "fix: clean tip");
      const offender = git(dir, "rev-parse", "HEAD~1");
      expect(await attribution([], { cwd: dir })).toBe(1);
      expect(errors.join("\n")).toContain(offender.slice(0, 12));
    });
  }

  test("prose that only mentions a trailer is not attribution", async () => {
    const dir = repo("fix: v\n\ne6499735 carries 'Co-Authored-By: Claude Opus 5 '. That is wrong - the tag reads it.");
    expect(await attribution([], { cwd: dir })).toBe(0);
  });

  test("a human co-author passes", async () => {
    expect(await attribution([], { cwd: repo("feat: q\n\nCo-authored-by: Someone <someone@example.com>") })).toBe(0);
  });

  test("a shallow clone is deepened, so an offender below the graft is still found", async () => {
    const source = repo("feat: first", SHAPES["session trailer"] as string, "fix: two", "fix: tip");
    const clone = scratch("gate-attribution-shallow-");
    Bun.spawnSync(["git", "clone", "-q", "--depth=1", `file://${source}`, clone]);
    expect(git(clone, "rev-parse", "--is-shallow-repository")).toBe("true");
    expect(await attribution([], { cwd: clone })).toBe(1);
    expect(git(clone, "rev-parse", "--is-shallow-repository")).toBe("false");
  });

  test("a shallow clone whose history cannot be fetched FAILS rather than passing unscanned", async () => {
    const source = repo("feat: first", "fix: tip");
    const clone = scratch("gate-attribution-orphan-");
    Bun.spawnSync(["git", "clone", "-q", "--depth=1", `file://${source}`, clone]);
    git(clone, "remote", "set-url", "origin", join(tmpdir(), "gate-attribution-no-such-remote"));
    expect(await attribution([], { cwd: clone })).toBe(1);
    expect(errors.join("\n")).toContain("not scanned");
  });

  test("scan reads the record format the verb writes", () => {
    const log = "aaaa\x1ffix: a\x1ffix: a\n\nClaude-Session: https://claude.ai/code/session_x\n\x1e\nbbbb\x1ffix: b\x1ffix: b\n\x1e\n";
    expect(scan(log).map((o) => o.sha)).toEqual(["aaaa"]);
  });
});
