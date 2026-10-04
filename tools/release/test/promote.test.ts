// `release promote` against a REAL git remote and a FAKE gh — no network, no GitHub.
//
// The two arms that matter are the ones #86's review paid for: the version and the push both come
// from ONE pinned SHA, so a main that advances during the confirmation prompt aborts instead of
// promoting something the operator never saw; and every gh failure is named rather than exiting in
// silence (a broken gh made promote.sh exit 1 with no output at all).
import { afterAll, beforeEach, describe, expect, test } from "bun:test";
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { promote } from "../src/commands/promote.ts";
import { SHIM_RELATIVE } from "../src/lib/shim.ts";

const VERSION = "0.3.2";
const workspaces: string[] = [];
afterAll(() => {
  for (const dir of workspaces) rmSync(dir, { recursive: true, force: true });
});

const GIT_ENV = {
  GIT_CONFIG_NOSYSTEM: "1",
  GIT_CONFIG_GLOBAL: "/dev/null",
  GIT_AUTHOR_NAME: "seat",
  GIT_AUTHOR_EMAIL: "seat@example.invalid",
  GIT_COMMITTER_NAME: "seat",
  GIT_COMMITTER_EMAIL: "seat@example.invalid",
};

function git(cwd: string, ...argv: string[]): string {
  const proc = Bun.spawnSync(["git", ...argv], { cwd, env: { ...Bun.env, ...GIT_ENV }, stdout: "pipe", stderr: "pipe" });
  if (proc.exitCode !== 0 && !argv.includes("ls-remote")) {
    throw new Error(`git ${argv.join(" ")} failed: ${proc.stderr.toString()}`);
  }
  return proc.stdout.toString().trim();
}

interface Fixture {
  readonly root: string;
  readonly origin: string;
  readonly stubs: string;
  readonly ghLog: string;
  readonly mainSha: string;
}

function fixture(shim = `#!/usr/bin/env node\nconst SPLICE_GATEWAY_VERSION = "${VERSION}";\nconst SPLICE_SHIM_VERSION = "shim-5";\n`): Fixture {
  const dir = mkdtempSync(join(tmpdir(), "release-promote-"));
  workspaces.push(dir);
  const origin = join(dir, "origin.git");
  const root = join(dir, "work");
  mkdirSync(origin);
  mkdirSync(root);
  git(origin, "init", "--bare", "-b", "main", ".");
  git(root, "init", "-b", "main", ".");
  mkdirSync(join(root, SHIM_RELATIVE, ".."), { recursive: true });
  writeFileSync(join(root, SHIM_RELATIVE), shim);
  git(root, "add", "-A");
  git(root, "commit", "-q", "-m", "the shim");
  git(root, "remote", "add", "origin", origin);
  git(root, "push", "-q", "origin", "main");
  git(root, "fetch", "-q", "origin");

  const stubs = join(dir, "stubs");
  mkdirSync(stubs);
  const ghLog = join(dir, "gh.log");
  writeFileSync(
    join(stubs, "gh"),
    `#!${process.execPath}\n` +
      'import { appendFileSync } from "node:fs";\n' +
      "const argv = process.argv.slice(2);\n" +
      `appendFileSync(${JSON.stringify(ghLog)}, argv.join(" ") + "\\n");\n` +
      'if (process.env.GH_EXIT === "1") process.exit(1);\n' +
      'if (argv[1] === "list") process.stdout.write(process.env.GH_PR_LIST ?? "");\n' +
      'if (argv[1] === "create") process.stdout.write("https://github.com/torad-labs/splice/pull/1\\n");\n',
  );
  chmodSync(join(stubs, "gh"), 0o755);
  return { root, origin, stubs, ghLog, mainSha: git(root, "rev-parse", "HEAD") };
}

/** promote(), with its console output captured and gh resolvable only from `stubs`. */
async function run(f: Fixture, options: { prompt?: (text: string) => string; gh?: boolean; env?: Record<string, string> } = {}) {
  const lines: string[] = [];
  const before = { log: console.log, error: console.error, path: process.env.PATH, yes: process.env.PROMOTE_YES };
  console.log = (...parts: unknown[]) => void lines.push(parts.join(" "));
  console.error = (...parts: unknown[]) => void lines.push(parts.join(" "));
  // `gh: false` is a PATH with git on it and no gh — the box that has never installed the CLI.
  process.env.PATH = options.gh === false ? gitOnlyPath() : `${f.stubs}:${process.env.PATH ?? ""}`;
  for (const [key, value] of Object.entries(options.env ?? {})) process.env[key] = value;
  try {
    const code = await promote([], f.root, { prompt: options.prompt ?? (() => "") });
    return { code, output: lines.join("\n") };
  } finally {
    console.log = before.log;
    console.error = before.error;
    process.env.PATH = before.path;
    if (before.yes === undefined) delete process.env.PROMOTE_YES;
    else process.env.PROMOTE_YES = before.yes;
    for (const key of Object.keys(options.env ?? {})) delete process.env[key];
  }
}

const prodSha = (f: Fixture) => git(f.root, "ls-remote", "origin", "refs/heads/prod").split("\t")[0] ?? "";

/** A PATH carrying git and nothing else — `gh` genuinely absent, the way it is on a fresh box. */
function gitOnlyPath(): string {
  const dir = mkdtempSync(join(tmpdir(), "release-promote-nogh-"));
  workspaces.push(dir);
  const git = Bun.which("git", { PATH: process.env.PATH ?? "" });
  if (!git) throw new Error("git must be on PATH for these tests");
  symlinkSync(git, join(dir, "git"));
  return dir;
}

describe("release promote", () => {
  beforeEach(() => {
    delete process.env.PROMOTE_YES;
    delete process.env.GH_EXIT;
    delete process.env.GH_PR_LIST;
  });

  test("the first promotion creates prod at the pinned SHA", async () => {
    const f = fixture();
    const result = await run(f, { env: { PROMOTE_YES: "1" } });
    expect(result.code).toBe(0);
    expect(result.output).toContain(`prod created at ${f.mainSha} — release.yml is publishing v${VERSION}.`);
    expect(prodSha(f)).toBe(f.mainSha);
  });

  test("a mismatched confirmation aborts, and nothing is pushed", async () => {
    const f = fixture();
    const result = await run(f, { prompt: () => "nope" });
    expect(result.code).toBe(1);
    expect(result.output).toContain("promote: aborted (typed 'nope')");
    expect(prodSha(f)).toBe("");
  });

  test("EOF on the prompt aborts as an ordinary mismatch", async () => {
    const f = fixture();
    const result = await run(f, { prompt: () => "" });
    expect(result.output).toContain("promote: aborted (typed '<eof>')");
    expect(prodSha(f)).toBe("");
  });

  // Review of #86: the prompt is unbounded, so main can advance while it is open. The push uses
  // the PINNED sha, and a race can only abort.
  test("main moving during the confirmation aborts instead of promoting the new tip", async () => {
    const f = fixture();
    const result = await run(f, {
      prompt: () => {
        writeFileSync(join(f.root, "moved.txt"), "later work\n");
        git(f.root, "add", "-A");
        git(f.root, "commit", "-q", "-m", "main moves");
        git(f.root, "push", "-q", "origin", "main");
        return VERSION;
      },
    });
    expect(result.code).toBe(1);
    expect(result.output).toContain("main moved while you were confirming");
    expect(result.output).toContain(`confirmed ${f.mainSha}`);
    expect(prodSha(f)).toBe("");
  });

  test("an existing tag for the promoted version refuses before anything else", async () => {
    const f = fixture();
    git(f.root, "tag", `v${VERSION}`);
    git(f.root, "push", "-q", "origin", `v${VERSION}`);
    const result = await run(f, { env: { PROMOTE_YES: "1" } });
    expect(result.code).toBe(1);
    expect(result.output).toContain(`promote: tag v${VERSION} already exists`);
    expect(result.output).toContain(SHIM_RELATIVE);
    expect(prodSha(f)).toBe("");
  });

  test("a shim with no marker at origin/main is named, never promoted blind", async () => {
    const f = fixture('#!/usr/bin/env node\nconst NOTHING = "0.0.0";\n');
    const result = await run(f, { env: { PROMOTE_YES: "1" } });
    expect(result.code).toBe(1);
    expect(result.output).toContain("could not read SPLICE_GATEWAY_VERSION from origin/main");
    expect(prodSha(f)).toBe("");
  });

  describe("with prod already there", () => {
    function withProd(): Fixture {
      const f = fixture();
      git(f.root, "push", "-q", "origin", `${f.mainSha}:refs/heads/prod`);
      return f;
    }

    test("a missing gh is named, not a silent exit 1", async () => {
      const result = await run(withProd(), { gh: false });
      expect(result.code).toBe(1);
      expect(result.output).toContain("the GitHub CLI (gh) is required to open the promotion PR");
    });

    test("a gh that fails listing is named", async () => {
      const result = await run(withProd(), { env: { GH_EXIT: "1" } });
      expect(result.code).toBe(1);
      expect(result.output).toContain("gh failed listing PRs — check 'gh auth status'");
    });

    test("an open promotion PR is reported and never duplicated", async () => {
      const f = withProd();
      const result = await run(f, { env: { GH_PR_LIST: "https://github.com/torad-labs/splice/pull/9\n" } });
      expect(result.code).toBe(0);
      expect(result.output).toContain("a promotion PR is already open — merge it (merge commit, not squash)");
      expect(readFileSync(f.ghLog, "utf8")).not.toContain("pr create");
    });

    test("otherwise the PR is created with the promoted version in its title and body", async () => {
      const f = withProd();
      const result = await run(f);
      expect(result.code).toBe(0);
      const log = readFileSync(f.ghLog, "utf8");
      expect(log).toContain(`pr create --base prod --head main --title chore(release): promote main to prod (v${VERSION})`);
      expect(log).toContain(`publishes **v${VERSION}**`);
      expect(log).toContain("Merge with a **merge commit**, not squash.");
      expect(result.output).toContain(`promote: promotion PR opened — merging it releases v${VERSION}.`);
    });
  });

  test("it takes no arguments", async () => {
    const f = fixture();
    const lines: string[] = [];
    const before = console.error;
    console.error = (...parts: unknown[]) => void lines.push(parts.join(" "));
    try {
      expect(await promote(["--now"], f.root)).toBe(1);
    } finally {
      console.error = before;
    }
    expect(lines.join("\n")).toContain("takes no arguments");
    expect(existsSync(join(f.root, ".git"))).toBe(true);
  });
});
