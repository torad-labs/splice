#!/usr/bin/env bun
/** Red/green proof for the splice orchestrator routing (walls-first, §17).
 *
 *  Every case drives the REAL orchestrator as a subprocess with a synthetic hook
 *  event against a hermetic copy of the repo's sgconfig + rules (SPLICE_HOOK_ROOT).
 *  The rules themselves are proven by `ast-grep test`; this suite proves the
 *  ROUTING: proposed-content computation, glob binding via the temp mirror,
 *  severity → decision mapping, and fail-closed behavior.
 *
 *  RUN IT AS `bun .claude/hooks/tests/test_orchestrator.ts`, which is the row's declared verify.
 *  That rules out `bun:test` — its primitives throw outside the `bun test` runner — so the suite
 *  carries its own three-line harness instead. The shape is the Python suite's own `unittest`
 *  output: one line per case, a Ran-N summary, and a non-zero exit on any failure.
 *
 *  THE INTERPRETER IS ADDRESSED ABSOLUTELY, and it is not a style choice: two arms below poison
 *  PATH to prove the fail-closed and fail-open branches, so a bare `bun` would not be found and
 *  those two arms would error out instead of testing what they name. Python got this for free
 *  from `sys.executable`; `process.execPath` is its exact equivalent here.
 */
import { cpSync, mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const HOOKS_DIR = dirname(dirname(fileURLToPath(import.meta.url)));
const ORCHESTRATOR = join(HOOKS_DIR, "orchestrator.ts");
const REPO = dirname(dirname(HOOKS_DIR));
const BUN = process.execPath;

// An error-severity finding used to prove the orchestrator routes a violation to
// a block. Uses L3 (a `message_stop` literal outside the sole emitter) — the
// former L1 include example was retired 2026-07-14 when reasoning replay shipped.
// Re-based onto the KOTLIN wall on 2026-08-10: these fixtures used the JavaScript
// rules scoped to `server/**`, and P8-CUT deleted that tree along with them. The
// Kotlin `kt-l3-sole-wire-terminals` is the same invariant on the live stack —
// same error severity, same sole-emitter shape with a path `ignores` — so the
// routing mechanics under test are unchanged.
// Class-wrapped on 2026-08-17, when kt-no-top-level-functions was promoted from a write-time-only
// hook into a routed gate rule. These fixtures must be clean under EVERY rule except the one under
// test, or CLEAN stops proving what its name claims: a bare `fun endTurn(...)` at file scope now
// trips the top-level-function wall, so the "clean write passes" case would fail for a reason that
// has nothing to do with L3. Only the body differs between the two.
const VIOLATION = 'class Probe {\n    fun endTurn(out: Writer) {\n        out.write("message_stop")\n    }\n}\n';
const CLEAN = "class Probe {\n    fun endTurn(out: Writer) {\n        out.close()\n    }\n}\n";
const L3_TARGET = "gateway/core/src/main/kotlin/splice/core/Probe.kt"; // in scope, NOT the emitter
const L3_EXEMPT = "gateway/gateway/src/main/kotlin/splice/gateway/wire/SseEmitter.kt"; // the sole emitter
const L3_RULE = "kt-l3-sole-wire-terminals";

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

const assert = (condition: boolean, message?: string): void => {
  if (!condition) throw new Error(message ?? "assertion failed");
};
const assertEqual = (actual: unknown, expected: unknown, message?: string): void => {
  if (actual !== expected) {
    throw new Error(`${message ?? "not equal"}: ${JSON.stringify(actual)} != ${JSON.stringify(expected)}`);
  }
};
const assertNull = (actual: unknown, message?: string): void => {
  if (actual !== null && actual !== undefined) {
    throw new Error(`${message ?? "expected null"}: got ${JSON.stringify(actual)}`);
  }
};
const assertContains = (haystack: unknown, needle: string, message?: string): void => {
  if (typeof haystack !== "string" || !haystack.includes(needle)) {
    throw new Error(`${message ?? "expected to contain"}: ${JSON.stringify(needle)}`);
  }
};

// ── the hermetic root ─────────────────────────────────────────────────────────────────────────

const root = mkdtempSync(join(tmpdir(), "splice-hook-test-"));
cpSync(join(REPO, "sgconfig.yml"), join(root, "sgconfig.yml"));
cpSync(join(REPO, "quality", "rules"), join(root, "quality", "rules"), { recursive: true });

interface RunResult {
  decision: Record<string, unknown> | null;
  stderr: string;
}

function runHook(
  lifecycle: string,
  event: Record<string, unknown>,
  envExtra: Record<string, string> | null = null,
  atRoot: string | null = null,
): RunResult {
  const env: Record<string, string> = {
    ...(process.env as Record<string, string>),
    SPLICE_HOOK_ROOT: atRoot ?? root,
  };
  delete env.SPLICE_WALLS_OK;
  Object.assign(env, envExtra ?? {});
  const proc = Bun.spawnSync([BUN, ORCHESTRATOR, lifecycle], {
    stdin: Buffer.from(JSON.stringify(event)),
    env,
    stdout: "pipe",
    stderr: "pipe",
    timeout: 60000,
  });
  const stdout = proc.stdout.toString();
  const stderr = proc.stderr.toString();
  assertEqual(proc.exitCode, 0, `orchestrator ${lifecycle} must exit 0 (stderr: ${stderr})`);
  const decision = stdout.trim() ? (JSON.parse(stdout) as Record<string, unknown>) : null;
  return { decision, stderr };
}

function expectBlock(result: RunResult, msg?: string): Record<string, unknown> {
  assert(result.decision !== null && result.decision !== undefined, msg ?? "expected a decision");
  assertEqual(result.decision?.decision, "block", msg ?? "expected a block");
  return result.decision as Record<string, unknown>;
}

function writeEvent(rel: string, content: string): Record<string, unknown> {
  return {
    tool_name: "Write",
    tool_input: { file_path: join(root, rel), content },
    cwd: root,
  };
}

// --- PreToolUse: Write routing -------------------------------------------------

test("write violation blocks with rule id and note", () => {
  const decision = expectBlock(runHook("pretooluse", writeEvent(L3_TARGET, VIOLATION)), "violation write must block");
  assertContains(decision.reason, L3_RULE);
  assertContains(decision.reason, "SseEmitter");
});

test("write clean passes", () => {
  assertNull(runHook("pretooluse", writeEvent(L3_TARGET, CLEAN)).decision);
});

test("files glob binds same shape elsewhere passes", () => {
  // l3 is path-scoped: it ignores the sole emitter, so the identical
  // message_stop shape inside SseEmitter.kt is legal.
  assertNull(runHook("pretooluse", writeEvent(L3_EXEMPT, VIOLATION)).decision);
});

test("inline suppression is honored", () => {
  const suppressed = VIOLATION.replace("    out.write", `    // ast-grep-ignore: ${L3_RULE}\n    out.write`);
  assertNull(runHook("pretooluse", writeEvent(L3_TARGET, suppressed)).decision);
});

test("tsx and css rules route", () => {
  const tsx = runHook(
    "pretooluse",
    writeEvent("console/src/widgets/UsageMeter/ui.tsx", "export const L = () => <span>usage — live</span>;\n"),
  );
  assertContains(expectBlock(tsx).reason, "webui-no-emdash-ui-text");
  const css = runHook("pretooluse", writeEvent("console/src/app/app.css", ".myx-panel { font-size: 13px; }\n"));
  assertContains(expectBlock(css).reason, "webui-css-tokens-only");
});

// --- PreToolUse: Edit routing --------------------------------------------------

test("edit introducing violation blocks", () => {
  const target = join(root, L3_TARGET);
  mkdirSync(dirname(target), { recursive: true });
  writeFileSync(target, "class Probe {\n    fun onDone(out: Writer) {\n        finish(out)\n    }\n}\n", "utf8");
  const result = runHook("pretooluse", {
    tool_name: "Edit",
    tool_input: { file_path: target, old_string: "finish(out)", new_string: 'out.write("message_stop")' },
    cwd: root,
  });
  const decision = expectBlock(result, "edit introducing message_stop outside SseEmitter must block");
  assertContains(decision.reason, L3_RULE);
  rmSync(target, { force: true });
});

test("edit clean passes and multiedit applies sequentially", () => {
  const target = join(root, "gateway/gateway/src/main/kotlin/splice/gateway/head/Boot.kt");
  mkdirSync(dirname(target), { recursive: true });
  writeFileSync(
    target,
    "class Boot {\n    fun boot() {\n" + '        embeddedServer(Netty, port = PORT, host = "127.0.0.1")\n    }\n}\n',
    "utf8",
  );
  const result = runHook("pretooluse", {
    tool_name: "MultiEdit",
    tool_input: {
      file_path: target,
      edits: [
        { old_string: "PORT", new_string: "3099" },
        { old_string: '"127.0.0.1"', new_string: '"0.0.0.0"' },
      ],
    },
    cwd: root,
  });
  const decision = expectBlock(result, "second edit rebinds to 0.0.0.0 — must block");
  assertContains(decision.reason, "kt-embedded-server-loopback");
  rmSync(target, { force: true });
});

// --- Jurisdiction and walls ----------------------------------------------------

test("non-write tool passes", () => {
  assertNull(runHook("pretooluse", { tool_name: "Bash", tool_input: { command: "ls" } }).decision);
});

test("outside repo passes", () => {
  assertNull(
    runHook("pretooluse", {
      tool_name: "Write",
      tool_input: { file_path: "/etc/hosts.test", content: VIOLATION },
    }).decision,
  );
});

test("missing ast grep fails closed", () => {
  const result = runHook("pretooluse", writeEvent(L3_TARGET, CLEAN), { PATH: "/nonexistent" });
  const decision = expectBlock(result, "missing scanner must fail closed on PreToolUse");
  assertContains(decision.reason, "HOOK POLICY INCOMPLETE");
});

// --- Stop ----------------------------------------------------------------------

test("stop blocks on dirty tree and respects active flag", () => {
  const isolated = mkdtempSync(join(tmpdir(), "splice-stop-test-"));
  try {
    cpSync(join(REPO, "sgconfig.yml"), join(isolated, "sgconfig.yml"));
    cpSync(join(REPO, "quality", "rules"), join(isolated, "quality", "rules"), { recursive: true });
    assertNull(runHook("stop", {}, null, isolated).decision, "clean tree must not block stop");
    const bad = join(isolated, L3_TARGET);
    mkdirSync(dirname(bad), { recursive: true });
    writeFileSync(bad, VIOLATION, "utf8");
    const decision = expectBlock(runHook("stop", {}, null, isolated), "dirty tree must block stop");
    assertContains(decision.reason, L3_RULE);
    assertNull(
      runHook("stop", { stop_hook_active: true }, null, isolated).decision,
      "stop_hook_active must not re-block",
    );
  } finally {
    rmSync(isolated, { recursive: true, force: true });
  }
});

test("stop fails open when scanner missing", () => {
  const result = runHook("stop", {}, { PATH: "/nonexistent" });
  assertNull(result.decision, "stop must fail open on infra failure");
  assertContains(result.stderr, "stop scan unavailable");
});

// ── report ────────────────────────────────────────────────────────────────────────────────────

rmSync(root, { recursive: true, force: true });

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
