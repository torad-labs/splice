// signin-e2e breaks: the sign-in check fails, naming the provider and the step, on a tree broken on purpose in each of
// the three ways the feature names. Each break is a patch kept beside the check's tests
// (tools/e2e/test/signin-breaks/<name>.patch), never in the product: it is applied to a throwaway copy of this tree
// (its commits and its uncommitted edits alike), that copy builds the jar, and this tree's own sign-in check runs on
// that jar for the provider the break is aimed at. A patch touches product code only, never the check, its stand-ins
// or any test, so the check cannot be made to fail by breaking the check. It needs Java, Bun and Playwright's
// Chromium: it runs on a Mac with Java (`rig remote run --needs system=Darwin/arm64 --needs java -- bun test <this file>`).

import { afterAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { spawnSync } from "node:child_process";
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { ROOT, runCheck } from "./lib/report.ts";

// Three builds of the jar and three runs of one kind; no test here is a claim about speed.
setDefaultTimeout(60 * 60_000);

const BREAKS: { name: string; what: string; kind: string; step: RegExp }[] = [
  { name: "console-no-tab", what: "the console opens no tab on the press", kind: "chatgpt-oauth", step: /^1$/ },
  {
    name: "credential-elsewhere",
    what: "a sign-in writes its credential where the gateway does not read it",
    kind: "grok-oauth",
    step: /^[23]$/,
  },
  { name: "other-credential", what: "the gateway sends a turn with another login's credential", kind: "kimi-oauth", step: /^3$/ },
];

const trees: string[] = [];
afterAll(() => {
  for (const t of trees) spawnSync("git", ["worktree", "remove", "--force", t], { cwd: ROOT });
  spawnSync("git", ["worktree", "prune"], { cwd: ROOT });
  for (const t of trees) rmSync(dirname(t), { recursive: true, force: true });
});

function git(args: string[], cwd: string, input?: string | Buffer): string {
  const r = spawnSync("git", args, { cwd, input, encoding: "utf8", maxBuffer: 1 << 30 });
  if (r.status !== 0) throw new Error(`git ${args.join(" ")} failed in ${cwd}:\n${r.stdout}${r.stderr}`);
  return r.stdout;
}

/** The paths a patch touches, as git reads it. */
function touched(patch: string): string[] {
  return git(["apply", "--numstat", patch], ROOT)
    .split("\n")
    .filter(Boolean)
    .map((l) => l.split("\t")[2]!)
    .map((p) => (p.includes(" => ") ? p.replace(/\{?([^{}]*) => ([^{}]*)\}?/, "$2") : p));
}

/** A copy of this tree as it is on disk (HEAD, then its uncommitted edits and the files git does not ignore). */
function copyOfTree(): string {
  const tree = join(mkdtempSync(join(tmpdir(), "signin-e2e-break-")), "tree");
  trees.push(tree);
  git(["worktree", "add", "--detach", tree, "HEAD"], ROOT);
  const diff = spawnSync("git", ["diff", "HEAD", "--binary"], { cwd: ROOT, maxBuffer: 1 << 30 }).stdout;
  if (diff.length > 0) git(["apply", "--binary", "--whitespace=nowarn"], tree, diff);
  for (const f of git(["ls-files", "-o", "--exclude-standard", "-z"], ROOT).split("\0").filter(Boolean)) {
    mkdirSync(dirname(join(tree, f)), { recursive: true });
    copyFileSync(join(ROOT, f), join(tree, f));
  }
  return tree;
}

describe("signin-e2e breaks", () => {
  for (const b of BREAKS) {
    test(`the check fails on ${b.kind}, naming the step, when ${b.what}`, () => {
      const patch = join(ROOT, "tools/e2e/test/signin-breaks", `${b.name}.patch`);
      expect(existsSync(patch), `${patch} holds the break`).toBe(true);
      expect(readFileSync(patch, "utf8").trim(), `${b.name}.patch is not empty`).not.toBe("");
      const paths = touched(patch);
      expect(paths.length, `${b.name}.patch changes something`).toBeGreaterThan(0);
      for (const p of paths) {
        const offLimits =
          p.startsWith("tools/") || p.startsWith(".campaign/") || p.startsWith(".github/") || p.includes("/src/test/") || /\.test\.[jt]s$/.test(p);
        expect(offLimits, `${b.name}.patch breaks product code only, and ${p} is not product code`).toBe(false);
      }

      const tree = copyOfTree();
      git(["apply", patch], tree);
      const build = spawnSync("./gradlew", [":app:shadowJar"], { cwd: tree, encoding: "utf8", maxBuffer: 256 << 20 });
      expect(build.status, `the broken tree builds:\n${`${build.stdout}${build.stderr}`.slice(-6000)}`).toBe(0);
      const jar = join(tree, "app/build/libs/app-all.jar");
      expect(existsSync(jar), "the broken tree's jar was built").toBe(true);

      const r = runCheck(["--kind", b.kind, "--jar", jar]);
      expect(r.code, `the check fails on the broken tree:\n${r.out.slice(-6000)}`).not.toBe(0);
      const line = r.out.split("\n").find((l) => l.startsWith(`signin: FAIL ${b.kind}, step `));
      expect(line, `the check names ${b.kind} and the step:\n${r.out.slice(-6000)}`).toBeDefined();
      const step = /^signin: FAIL [^,]+, step (\d+) \(/.exec(line!)?.[1] ?? "";
      expect(step, `${b.what}: caught at the right step, not ${line}`).toMatch(b.step);
      const k = r.report?.kinds.find((x) => x.kind === b.kind);
      expect(k?.ok, `the report says ${b.kind} failed`).toBe(false);
      const first = k?.steps.find((s) => !s.ok);
      expect(String(first?.step ?? ""), "the report's first failing step is the line's").toBe(step);
    });
  }
});
