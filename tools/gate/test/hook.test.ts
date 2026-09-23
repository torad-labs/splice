// Red/green proof for the write-time and stop-time wall (walls-first, §17). Every case drives the
// REAL entry — `bun tools/gate rules --stdin <lifecycle>`, the command .claude/settings.json routes
// the lifecycles to — as a subprocess with a synthetic hook event against a hermetic copy of the
// repo's sgconfig + rules (SPLICE_HOOK_ROOT). The rules themselves are proven by `ast-grep test`;
// this suite proves the ROUTING: proposed-content computation, glob binding via the temp mirror,
// severity → decision mapping, and the fail-closed / fail-open split.
//
// THE INTERPRETER IS ADDRESSED ABSOLUTELY (process.execPath): two arms poison PATH to prove the
// fail-closed and fail-open branches, so a bare `bun` would not be found and those arms would error
// out instead of testing what they name.
import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { layout } from "../src/lib/repo.ts";

const { repoRoot } = layout();
const GATE = join(repoRoot, "tools", "gate", "index.ts");
const BUN = process.execPath;

// An error-severity finding used to prove the hook routes a violation to a block: L3, a
// `message_stop` literal outside the sole emitter (`kt-l3-sole-wire-terminals`, error severity,
// sole-emitter shape with a path `ignores`). Class-wrapped because kt-no-top-level-functions is a
// routed gate rule: these fixtures must be clean under EVERY rule except the one under test, or
// CLEAN stops proving what its name claims. Only the body differs between the two.
const VIOLATION = 'class Probe {\n    fun endTurn(out: Writer) {\n        out.write("message_stop")\n    }\n}\n';
const CLEAN = "class Probe {\n    fun endTurn(out: Writer) {\n        out.close()\n    }\n}\n";
const L3_TARGET = "core/src/main/kotlin/splice/core/Probe.kt"; // in scope, NOT the emitter
const L3_EXEMPT = "features/turns/src/main/kotlin/splice/head/wire/SseEmitter.kt"; // the sole emitter
const L3_RULE = "kt-l3-sole-wire-terminals";

let root = "";
const hermetic = (): string => {
  const dir = mkdtempSync(join(tmpdir(), "splice-hook-test-"));
  cpSync(join(repoRoot, "sgconfig.yml"), join(dir, "sgconfig.yml"));
  cpSync(join(repoRoot, "quality", "rules"), join(dir, "quality", "rules"), { recursive: true });
  return dir;
};
beforeAll(() => {
  root = hermetic();
});
afterAll(() => rmSync(root, { recursive: true, force: true }));

interface RunResult {
  decision: Record<string, unknown> | null;
  stderr: string;
}

function runHook(lifecycle: string, event: Record<string, unknown>, envExtra: Record<string, string> = {}, atRoot = ""): RunResult {
  const env = { ...(process.env as Record<string, string>), SPLICE_HOOK_ROOT: atRoot || root, ...envExtra };
  const proc = Bun.spawnSync([BUN, GATE, "rules", "--stdin", lifecycle], {
    stdin: Buffer.from(JSON.stringify(event)),
    env,
    stdout: "pipe",
    stderr: "pipe",
    timeout: 60000,
  });
  const stdout = proc.stdout.toString();
  const stderr = proc.stderr.toString();
  expect(proc.exitCode, `hook ${lifecycle} must exit 0 (stderr: ${stderr})`).toBe(0);
  return { decision: stdout.trim() ? (JSON.parse(stdout) as Record<string, unknown>) : null, stderr };
}

function expectBlock(result: RunResult, msg?: string): string {
  expect(result.decision, msg ?? "expected a block decision").not.toBeNull();
  expect(result.decision?.decision, msg ?? "expected a block").toBe("block");
  return String(result.decision?.reason ?? "");
}

const writeEvent = (rel: string, content: string): Record<string, unknown> => ({
  tool_name: "Write",
  tool_input: { file_path: join(root, rel), content },
  cwd: root,
});

describe("gate rules --stdin: the write-time wall", () => {
  test("write violation blocks with rule id and note", () => {
    const reason = expectBlock(runHook("pretooluse", writeEvent(L3_TARGET, VIOLATION)), "violation write must block");
    expect(reason).toContain(L3_RULE);
    expect(reason).toContain("SseEmitter");
  });

  test("write clean passes", () => {
    expect(runHook("pretooluse", writeEvent(L3_TARGET, CLEAN)).decision).toBeNull();
  });

  test("files glob binds: the same shape inside the sole emitter passes", () => {
    expect(runHook("pretooluse", writeEvent(L3_EXEMPT, VIOLATION)).decision).toBeNull();
  });

  test("inline suppression is honored", () => {
    const suppressed = VIOLATION.replace("    out.write", `    // ast-grep-ignore: ${L3_RULE}\n    out.write`);
    expect(runHook("pretooluse", writeEvent(L3_TARGET, suppressed)).decision).toBeNull();
  });

  test("tsx and css rules route", () => {
    const tsx = runHook("pretooluse", writeEvent("console/src/widgets/UsageMeter/ui.tsx", "export const L = () => <span>usage — live</span>;\n"));
    expect(expectBlock(tsx)).toContain("webui-no-emdash-ui-text");
    const css = runHook("pretooluse", writeEvent("console/src/app/app.css", ".myx-panel { font-size: 13px; }\n"));
    expect(expectBlock(css)).toContain("webui-css-tokens-only");
  });

  test("edit introducing a violation blocks", () => {
    const target = join(root, L3_TARGET);
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, "class Probe {\n    fun onDone(out: Writer) {\n        finish(out)\n    }\n}\n", "utf8");
    try {
      const result = runHook("pretooluse", {
        tool_name: "Edit",
        tool_input: { file_path: target, old_string: "finish(out)", new_string: 'out.write("message_stop")' },
        cwd: root,
      });
      expect(expectBlock(result, "edit introducing message_stop outside SseEmitter must block")).toContain(L3_RULE);
    } finally {
      rmSync(target, { force: true });
    }
  });

  test("edit clean passes and multiedit applies sequentially", () => {
    const target = join(root, "features/turns/src/main/kotlin/splice/gateway/head/Boot.kt");
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, 'class Boot {\n    fun boot() {\n        embeddedServer(Netty, port = PORT, host = "127.0.0.1")\n    }\n}\n', "utf8");
    try {
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
      expect(expectBlock(result, "second edit rebinds to 0.0.0.0 — must block")).toContain("kt-embedded-server-loopback");
    } finally {
      rmSync(target, { force: true });
    }
  });

  test("non-write tool passes", () => {
    expect(runHook("pretooluse", { tool_name: "Bash", tool_input: { command: "ls" } }).decision).toBeNull();
  });

  test("outside repo passes", () => {
    expect(runHook("pretooluse", { tool_name: "Write", tool_input: { file_path: "/etc/hosts.test", content: VIOLATION } }).decision).toBeNull();
  });

  test("missing ast-grep fails CLOSED", () => {
    const reason = expectBlock(runHook("pretooluse", writeEvent(L3_TARGET, CLEAN), { PATH: "/nonexistent" }), "missing scanner must fail closed on pretooluse");
    expect(reason).toContain("HOOK POLICY INCOMPLETE");
  });
});

describe("gate rules --stdin: the stop-time wall", () => {
  test("stop blocks on a dirty tree and respects stop_hook_active", () => {
    const isolated = hermetic();
    try {
      expect(runHook("stop", {}, {}, isolated).decision, "clean tree must not block stop").toBeNull();
      const bad = join(isolated, L3_TARGET);
      mkdirSync(dirname(bad), { recursive: true });
      writeFileSync(bad, VIOLATION, "utf8");
      expect(expectBlock(runHook("stop", {}, {}, isolated), "dirty tree must block stop")).toContain(L3_RULE);
      expect(runHook("stop", { stop_hook_active: true }, {}, isolated).decision, "stop_hook_active must not re-block").toBeNull();
    } finally {
      rmSync(isolated, { recursive: true, force: true });
    }
  });

  test("stop fails OPEN when the scanner is missing", () => {
    const result = runHook("stop", {}, { PATH: "/nonexistent" });
    expect(result.decision, "stop must fail open on infra failure").toBeNull();
    expect(result.stderr).toContain("stop scan unavailable");
  });
});

describe("gate rules --stdin: the contract", () => {
  test(".claude/settings.json routes PreToolUse, Stop and SubagentStop to this verb", () => {
    const settings = JSON.parse(readFileSync(join(repoRoot, ".claude", "settings.json"), "utf8")) as {
      hooks: Record<string, { hooks: { command: string }[] }[]>;
    };
    const commands = (event: string) => settings.hooks[event]!.flatMap((h) => h.hooks.map((x) => x.command));
    expect(commands("PreToolUse")).toContain("bun $CLAUDE_PROJECT_DIR/tools/gate rules --stdin pretooluse");
    expect(commands("Stop")).toContain("bun $CLAUDE_PROJECT_DIR/tools/gate rules --stdin stop");
    expect(commands("SubagentStop")).toContain("bun $CLAUDE_PROJECT_DIR/tools/gate rules --stdin stop");
  });

  test("an unknown lifecycle, a missing one, or --stdin mixed with another flag is refused with exit 2", () => {
    for (const argv of [["--stdin", "posttooluse"], ["--stdin"], ["--stdin", "stop", "--prove-coverage"], ["--prove-coverage", "--stdin", "stop"]]) {
      const proc = Bun.spawnSync([BUN, GATE, "rules", ...argv], { stdin: Buffer.from("{}"), stdout: "pipe", stderr: "pipe" });
      expect(proc.exitCode, `argv ${argv.join(" ")}`).toBe(2);
      expect(proc.stdout.toString()).toBe("");
    }
  });

  test("malformed or non-object stdin is not this wall's business: exit 0, no decision", () => {
    for (const stdin of ["", "not json", "[1,2]", "null"]) {
      const proc = Bun.spawnSync([BUN, GATE, "rules", "--stdin", "pretooluse"], { stdin: Buffer.from(stdin), stdout: "pipe", stderr: "pipe" });
      expect(proc.exitCode).toBe(0);
      expect(proc.stdout.toString()).toBe("");
    }
  });
});
