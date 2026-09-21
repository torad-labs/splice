#!/usr/bin/env bun
/** Red/green proof for the one-committer Bash guard (module 09).
 *
 *  The guard blocks a Bash command opening with git add/commit/push unless LEDGER_ORCHESTRATOR=1
 *  is an inline env PREFIX on the command as written, or the console-handover carve-out admits it
 *  (every affected path inside splice-design's own fence). Three things this suite is built to pin:
 *
 *    1. the grant parse runs on the COMMAND as written — a var set earlier in the shell (or as a
 *       separate command before `&&`, or passed as an argument) never satisfies it;
 *    2. the handover carve-out is path-exact — one path outside the set refuses BY NAME, so a
 *       "one webui path plus one src path" near-miss is refused, not waved through;
 *    3. the guard is WIRED (law 19): the runner dispatch drives the real module, so a renamed or
 *       deleted module (or a disabled hook chain) fails the wired cases, not just the direct ones.
 *
 *  RUN IT AS `bun .claude/hooks/tests/test_one_committer.ts`. That rules out `bun:test` — its
 *  primitives throw outside the `bun test` runner — so this suite carries the same three-line
 *  harness as its orchestrator sibling, printing the Python suite's own shape: one line per case,
 *  a Ran-N summary, and a non-zero exit on any failure.
 *
 *  THE DIRECT HALF NOW LOADS THE .ts MODULE, which is the Phase C change: `import()` is the exact
 *  counterpart of the importlib load the Python suite used, so the un-wired cases grade the module
 *  under test rather than a copy of it.
 */
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const HOOKS_DIR = dirname(dirname(fileURLToPath(import.meta.url)));
const MODULE_PATH = join(HOOKS_DIR, "modules/pretooluse/09_one_committer.ts");
const PRETOOLUSE_ENTRY = join(HOOKS_DIR, "orchestrator/pretooluse.ts");
const BUN = process.execPath;

// ── the harness ───────────────────────────────────────────────────────────────────────────────

interface Case {
  name: string;
  error: string | null;
}

const cases: Case[] = [];

function test(name: string, body: () => void): void {
  try {
    body();
    cases.push({ name, error: null });
  } catch (exc) {
    cases.push({ name, error: exc instanceof Error ? (exc.message ?? String(exc)) : String(exc) });
  }
}

const assert = (condition: boolean, message: string): void => {
  if (!condition) throw new Error(message);
};
const assertEqual = (actual: unknown, expected: unknown, message: string): void => {
  if (actual !== expected) {
    throw new Error(`${message}: ${JSON.stringify(actual)} != ${JSON.stringify(expected)}`);
  }
};
const assertNull = (actual: unknown, message: string): void => {
  if (actual !== null && actual !== undefined) throw new Error(`${message}: got ${JSON.stringify(actual)}`);
};
const assertContains = (haystack: unknown, needle: string, message: string): void => {
  if (typeof haystack !== "string" || !haystack.includes(needle)) {
    throw new Error(`${message}: ${JSON.stringify(needle)} not found`);
  }
};

interface HookResult {
  kind: string;
  payload: string;
  moduleName: string;
}

interface GuardModule {
  applies(data: Record<string, unknown>): boolean;
  run(data: Record<string, unknown>): HookResult | null;
}

// The port of the Python suite's importlib load: it grades the module UNDER TEST, not a copy.
const mod = (await import(MODULE_PATH)) as unknown as GuardModule;

const bash = (command: string, cwd?: string): Record<string, unknown> => {
  const event: Record<string, unknown> = { tool_name: "Bash", tool_input: { command } };
  if (cwd) event.cwd = cwd;
  return event;
};

/** A hermetic git repo with one committed file, so `git diff --cached` has a base to read. */
function makeRepo(): string {
  const root = mkdtempSync(join(tmpdir(), "one-committer-repo-"));
  for (const args of [
    ["init", "-q"],
    ["config", "user.email", "t@example.invalid"],
    ["config", "user.name", "t"],
  ]) {
    const proc = Bun.spawnSync(["git", "-C", root, ...args], { stdout: "pipe", stderr: "pipe" });
    assertEqual(proc.exitCode, 0, `git ${args.join(" ")} must succeed`);
  }
  writeFileSync(join(root, "base.txt"), "base\n", "utf8");
  for (const args of [
    ["add", "base.txt"],
    ["commit", "-q", "-m", "base"],
  ]) {
    const proc = Bun.spawnSync(["git", "-C", root, ...args], { stdout: "pipe", stderr: "pipe" });
    assertEqual(proc.exitCode, 0, `git ${args.join(" ")} must succeed`);
  }
  return root;
}

function stage(root: string, rel: string): void {
  mkdirSync(dirname(join(root, rel)), { recursive: true });
  writeFileSync(join(root, rel), `# ${rel}\n`, "utf8");
  const proc = Bun.spawnSync(["git", "-C", root, "add", rel], { stdout: "pipe", stderr: "pipe" });
  assertEqual(proc.exitCode, 0, `git add ${rel} must succeed`);
}

function result(command: string, cwd?: string): HookResult | null {
  return mod.run(bash(command, cwd)) ?? null;
}

function assertBlocked(command: string, cwd?: string, named?: string): HookResult {
  const got = result(command, cwd);
  assert(got !== null, `expected a block for ${JSON.stringify(command)}`);
  assertEqual(got?.kind, "block", `expected kind=block for ${JSON.stringify(command)}`);
  if (named !== undefined) {
    assertContains(got?.payload, named, `block for ${JSON.stringify(command)} must name ${JSON.stringify(named)}`);
  }
  return got as HookResult;
}

function assertAllowed(command: string, cwd?: string): void {
  assertNull(result(command, cwd), `expected no block for ${JSON.stringify(command)}`);
}

// --- scope and read-only git --------------------------------------------------

test("applies only to bash", () => {
  assert(mod.applies({ tool_name: "Bash" }), "Bash must apply");
  assert(!mod.applies({ tool_name: "Write" }), "Write must not apply");
});

test("read only git is untouched", () => {
  for (const command of ["git status", "git log", "git diff", "git stash list"]) {
    assertAllowed(command);
  }
});

// --- the inline-prefix grant --------------------------------------------------

test("plain git write is blocked", () => {
  assertBlocked("git add foo");
  assertBlocked("git commit -am x");
  assertBlocked("git push origin main");
});

test("compound separator is blocked", () => {
  assertBlocked("echo hi && git add foo");
});

test("inline grant allows", () => {
  assertAllowed("LEDGER_ORCHESTRATOR=1 git add foo");
});

test("inline grant among other vars allows", () => {
  assertAllowed("VAR=1 LEDGER_ORCHESTRATOR=1 git -C /repo commit -am msg");
});

test("git C option without grant is blocked", () => {
  assertBlocked("git -C /repo add foo");
});

// --- near-miss forms (the grant is a PREFIX, not a presence) ------------------

test("var set mid command as own command is blocked", () => {
  assertBlocked("LEDGER_ORCHESTRATOR=1 && git add foo");
});

test("var passed as argument is blocked", () => {
  assertBlocked("git add foo LEDGER_ORCHESTRATOR=1");
});

test("var in commit message is blocked", () => {
  assertBlocked('git commit -m "LEDGER_ORCHESTRATOR=1"');
});

test("var set in a previous command is not inherited", () => {
  // The hook sees one command per event; a prior `export` cannot leak into the next.
  assertAllowed("export LEDGER_ORCHESTRATOR=1");
  assertBlocked("git add foo");
});

test("aliased git is not caught", () => {
  // Documented gap: the guard matches literal `git`; a shell alias is invisible to it.
  assertAllowed("alias g=git; g add foo");
});

// --- console handover: path-exact carve-out -----------------------------------

test("handover add inside passes", () => {
  assertAllowed("git add webui/x.ts");
});

test("handover add outside is blocked by name", () => {
  assertBlocked("git add src/y.py", undefined, "src/y.py");
});

test("handover near miss one webui one src is blocked", () => {
  assertBlocked("git add webui/x.ts src/y.py", undefined, "src/y.py");
});

test("handover exact files pass", () => {
  assertAllowed("git add bun.lock .dev/campaigns/web-console.toml");
});

test("handover directory nested passes", () => {
  assertAllowed("git add .dev/web-console/a/b.ts");
});

// --- fail-closed: empty or unreadable affected set ----------------------------

test("bare add is refused", () => {
  assertBlocked("git add");
});

test("broad add flag is refused", () => {
  assertBlocked("git add -A");
});

test("commit with nothing staged is refused", () => {
  const root = makeRepo();
  try {
    assertBlocked("git commit -m x", root);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("commit staged inside passes", () => {
  const root = makeRepo();
  try {
    stage(root, "webui/x.ts");
    assertAllowed("git commit -m x", root);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("commit staged outside is blocked by name", () => {
  const root = makeRepo();
  try {
    stage(root, "src/y.py");
    assertBlocked("git commit -m x", root, "src/y.py");
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

// --- law 19: the guard is WIRED, not merely implemented -----------------------

function driveRunner(command: string): { exitCode: number; stderr: string } {
  const proc = Bun.spawnSync([BUN, PRETOOLUSE_ENTRY], {
    stdin: Buffer.from(JSON.stringify(bash(command))),
    stdout: "pipe",
    stderr: "pipe",
    timeout: 30000,
  });
  return { exitCode: proc.exitCode ?? -1, stderr: proc.stderr.toString() };
}

test("runner dispatches module and blocks", () => {
  const proc = driveRunner("git add foo");
  assertEqual(proc.exitCode, 2, `expected exit 2 (stderr: ${proc.stderr})`);
  assertContains(proc.stderr, "one-committer", "the block must name the module");
});

test("runner dispatches module and allows grant", () => {
  const proc = driveRunner("LEDGER_ORCHESTRATOR=1 git add foo");
  assertEqual(proc.exitCode, 0, `expected exit 0 (stderr: ${proc.stderr})`);
});

// ── report ────────────────────────────────────────────────────────────────────────────────────

const failed = cases.filter((c) => c.error !== null);
for (const c of failed) {
  process.stdout.write(`FAIL ${c.name}\n     ${c.error}\n`);
}
process.stdout.write(`\nRan ${cases.length} tests\n`);
if (failed.length > 0) {
  process.stdout.write(`FAILED (failures=${failed.length})\n`);
  process.exit(1);
}
process.stdout.write("OK\n");
