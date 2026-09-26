// tools/e2e/test/heads.test.ts — the red-green proof for `e2e heads`, run by the gate and by
// `bun tools/e2e heads --selftest`. Ported whole from checks/e2e/heads-e2e-selftest.sh
// (restructure PR 5): every arm of that canary is a named test here.
//
// The live harness is billed and skipped in CI. This canary drives it against a loopback
// control+head so the skip / fake-token / FATAL-mgmt-key arms cannot rot: a skip or FATAL
// that still probes the head (and would have shipped the mgmt key to a vendor) fails HERE.
// The loopback answers /health first so the verb will not cold-start the installed splice.jar,
// and it binds EPHEMERAL ports, so two of these suites can run side by side.
//
// Receipts land in an isolated dir under the temp tree, NEVER tools/e2e/receipts — DR-111: the
// loopback head key deliberately matches the real head in splice.example.toml, so an unredirected
// tier-1 pass would FABRICATE the real head's in-repo receipt and the #924 binding would grade
// loopback bytes. The in-repo receipt is snapshotted and asserted untouched at the end.
import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { createHash } from "node:crypto";
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import {
  HARNESS_EXIT,
  OVERLONG_MCP_LOG_NAME,
  OVERLONG_MCP_SERVER,
  OVERLONG_MCP_SERVER_SCRIPT,
  OVERLONG_MCP_TOOL,
  OVERLONG_TOOL_NAME,
  liveStateDir,
  perfRowsOk,
  plan,
  assertWire,
  type UpstreamRow,
} from "../src/commands/heads.ts";
import { layout } from "../../gate/src/lib/repo.ts";

const ROOT = layout().repoRoot;
const CLI = resolve(import.meta.dir, "../index.ts");
const HARNESS_SRC = join(ROOT, "tools/e2e/src/commands/heads.ts");
const MGMT = "mgmt-key-for-selftest-32bytes!!";
/** The same head key the real roster carries — the DR-111 trap, kept deliberately. */
const HEAD_KEY = "claude-splice";
const REPO_RECEIPT = join(ROOT, "tools/e2e/receipts", `${HEAD_KEY}.json`);

let tmp = "";
let state = "";
let receipts = "";
let record = "";
let controlPort = "";
let loopback: ReturnType<typeof Bun.spawn> | null = null;
let receiptShaBefore = "absent";

const hasTmux = Bun.spawnSync(["sh", "-c", "command -v tmux"], { stdio: ["ignore", "ignore", "ignore"] }).exitCode === 0;

const sha = (p: string): string =>
  existsSync(p) ? createHash("sha256").update(readFileSync(p)).digest("hex") : "absent";

interface Run {
  status: number;
  stdout: string;
  stderr: string;
}

/** One harness invocation, with the loopback's control port and an isolated receipt dir. The
 *  recorder is truncated first so `headHits()` answers about THIS arm only. */
function runArm(extra: Record<string, string> = {}, argv: string[] = ["--tier", "1", "--head", HEAD_KEY]): Run {
  writeFileSync(record, "");
  const env: Record<string, string> = {
    ...process.env,
    CLAUDEX_STATE_DIR: state,
    SPLICE_CONTROL_PORT: controlPort,
    E2E_RECEIPT_DIR: receipts,
    ...extra,
  };
  delete env["SPLICE_STATE_DIR"];
  if (!("SPLICE_E2E_CLIENT_TOKEN" in extra)) delete env["SPLICE_E2E_CLIENT_TOKEN"];
  const child = Bun.spawnSync([process.execPath, CLI, "heads", ...argv], { env, stdio: ["ignore", "pipe", "pipe"] });
  return { status: child.exitCode ?? 1, stdout: child.stdout.toString(), stderr: child.stderr.toString() };
}

/** How many times the HEAD plane was touched during the last arm — the mgmt-key-leak oracle. */
function headRows(): Array<{ plane: string; authorization: string }> {
  return readFileSync(record, "utf8")
    .split("\n")
    .filter((l) => l.trim())
    .map((l) => JSON.parse(l) as { plane: string; authorization: string })
    .filter((r) => r.plane === "head");
}

beforeAll(async () => {
  tmp = mkdtempSync(join(tmpdir(), "heads-selftest-"));
  state = join(tmp, "state");
  receipts = join(tmp, "receipts");
  record = join(tmp, "rec.jsonl");
  mkdirSync(state, { recursive: true });
  writeFileSync(join(state, "mgmt-key"), MGMT);
  writeFileSync(record, "");
  receiptShaBefore = sha(REPO_RECEIPT);

  const ready = join(tmp, "ready");
  loopback = Bun.spawn(
    [
      process.execPath, join(ROOT, "tools/e2e/fixtures/loopback_control.ts"),
      "--record", record,
      "--ready-file", ready,
      "--head-key", HEAD_KEY,
      "--duplicate-stop-file", join(tmp, "duplicate-stop"),
      "--unknown-kind-head", "unknown-kind",
      "--count-tokens-drop-file", join(tmp, "ct-drop"),
    ],
    { stdio: ["ignore", "pipe", "pipe"] },
  );
  for (let i = 0; i < 100 && !existsSync(ready); i++) await Bun.sleep(50);
  if (!existsSync(ready)) throw new Error("loopback never wrote READY");
  controlPort = /CONTROL=(\d+)/.exec(readFileSync(ready, "utf8"))?.[1] ?? "";
  expect(controlPort).not.toBe("");
});

afterAll(() => {
  loopback?.kill("SIGKILL");
  if (hasTmux) Bun.spawnSync(["tmux", "-L", "splice-e2e", "kill-server"], { stdio: ["ignore", "ignore", "ignore"] });
  rmSync(tmp, { recursive: true, force: true });
});

describe("tier 1 credential gate", () => {
  test("skip: no caller token — the arm reports SKIP and exits 0 (a SKIP is not a FAIL)", () => {
    const r = runArm();
    expect(r.status).toBe(0);
    expect(r.stderr).toContain("client-auth head, no caller credential");
  });

  test("skip: the arm never touched the head — that would be the mgmt-key leak", () => {
    runArm();
    expect(headRows()).toEqual([]);
  });

  test("token: a fake caller credential passes wire + count_tokens", () => {
    const r = runArm({ SPLICE_E2E_CLIENT_TOKEN: "caller-e2e-token" });
    expect(r.stderr).toContain(`✓ ${HEAD_KEY}/wire`);
    expect(r.stderr).toContain(`✓ ${HEAD_KEY}/count_tokens`);
    expect(r.status).toBe(0);
  });

  test("token: the head saw the caller bearer, never the mgmt key", () => {
    runArm({ SPLICE_E2E_CLIENT_TOKEN: "caller-e2e-token" });
    const head = headRows();
    expect(head.length).toBeGreaterThan(0);
    expect(head.filter((r) => r.authorization !== "Bearer caller-e2e-token")).toEqual([]);
    expect(head.some((r) => r.authorization.includes(MGMT))).toBe(false);
  });

  test("duplicate terminal: a second message_stop fails the wire probe, never a clean stream", () => {
    writeFileSync(join(tmp, "duplicate-stop"), "");
    try {
      const r = runArm({ SPLICE_E2E_CLIENT_TOKEN: "caller-e2e-token" });
      expect(r.status).not.toBe(0);
      expect(r.stderr).toContain("message_stop count = 2");
    } finally {
      rmSync(join(tmp, "duplicate-stop"), { force: true });
    }
  });

  test("FATAL: a token equal to the mgmt key is refused before discovery, naming the VERBATIM forward", () => {
    const r = runArm({ SPLICE_E2E_CLIENT_TOKEN: MGMT });
    expect(r.status).not.toBe(0);
    expect(r.stderr).toContain("VERBATIM");
  });

  test("FATAL: the refused-token arm never touched the head", () => {
    runArm({ SPLICE_E2E_CLIENT_TOKEN: MGMT });
    expect(headRows()).toEqual([]);
  });

  // DR-113: a count_tokens transport failure is a per-head FAIL, not a harness abort. Red on the
  // unfixed harness: the bare assignment errexited the whole run with curl's exit code — no ✗ row,
  // no summary, later heads unprobed. The loopback drops the connection with no HTTP response
  // while the marker file exists.
  test("count_tokens transport failure records a per-head FAIL and the run still summarizes", () => {
    writeFileSync(join(tmp, "ct-drop"), "");
    try {
      const r = runArm({ SPLICE_E2E_CLIENT_TOKEN: "caller-e2e-token" });
      expect(r.status).not.toBe(0);
      expect(r.stderr).toContain(`✓ ${HEAD_KEY}/wire`);
      expect(r.stderr).toContain(`✗ ${HEAD_KEY}/count_tokens`);
      expect(r.stderr).toContain("── e2e summary ──");
    } finally {
      rmSync(join(tmp, "ct-drop"), { force: true });
    }
  });
});

describe("selectors and unknown auth kinds", () => {
  test("a requested key absent from discovery fails BY NAME, never 0/0/0 success", () => {
    const r = runArm({}, ["--tier", "1", "--head", "absent-head"]);
    expect(r.status).not.toBe(0);
    expect(r.stderr).toContain("requested head 'absent-head' was not returned");
  });

  test("the absent selector never touched the discovered head", () => {
    runArm({}, ["--tier", "1", "--head", "absent-head"]);
    expect(headRows()).toEqual([]);
  });

  // DR-49a. Red on the unfixed harness: probeBearer's `exit 1` died inside tier1's command-
  // substitution SUBSHELL, the parent read rc=1 — the same code as the legit "no caller token"
  // skip — and the run exited 0 with the FATAL text scrolling past as decoration.
  test("an unrecognized authKind is harness-FATAL by name, never a silent SKIP", () => {
    const r = runArm({}, ["--tier", "1", "--head", "unknown-kind"]);
    expect(r.status).not.toBe(0);
    expect(r.stderr).toContain("unrecognized authKind 'mystery-kind'");
  });

  test("the unknown-authKind arm never touched the head — the exact leak the FATAL exists to stop", () => {
    runArm({}, ["--tier", "1", "--head", "unknown-kind"]);
    expect(headRows()).toEqual([]);
  });
});

describe("the perf oracle", () => {
  const perf = (): string => join(state, `${HEAD_KEY}-perf.jsonl`);
  const oracleArm = (since: string, want: string): Run =>
    runArm({ E2E_PERF_SINCE: since, E2E_PERF_WANT: want }, ["--tier", "perf-oracle", "--head", HEAD_KEY]);

  // DR-49b. Red on the unfixed harness: a healthy model's interleaved ok rows sat adjacent to every
  // failure of a broken model, so a lane that NEVER recovered read "retried-then-ok" and the oracle
  // exited 0 (proven: exit 0 + "retried-then-ok: http_500x2" on this exact fixture).
  test("interleaved cross-model oks do not pardon a persistently failing model", () => {
    writeFileSync(
      perf(),
      [
        '{"ts":1000,"model":"broken-model","outcome":"http_500"}',
        '{"ts":2000,"model":"healthy-model","outcome":"ok"}',
        '{"ts":3000,"model":"broken-model","outcome":"http_500"}',
        '{"ts":4000,"model":"healthy-model","outcome":"ok"}',
      ].join("\n") + "\n",
    );
    const r = oracleArm("0", "1");
    expect(r.status).not.toBe(0);
    expect(r.stderr).toContain("unrecovered non-ok rows");
    rmSync(perf(), { force: true });
  });

  test("same-model retry-through is still pardoned, and keeps its informational tally", () => {
    writeFileSync(
      perf(),
      [
        '{"ts":1000,"model":"m","outcome":"http_500"}',
        '{"ts":2000,"model":"m","outcome":"ok"}',
        '{"ts":3000,"model":"m","outcome":"ok"}',
      ].join("\n") + "\n",
    );
    const r = oracleArm("0", "2");
    expect(r.status).toBe(0);
    expect(r.stderr).toContain("retried-then-ok: http_500x1");
    rmSync(perf(), { force: true });
  });

  // The counter semantics the oracle's own comment pins: an ABSENT counter is compliant (TurnPerf
  // drops a zero delta), a written wrong one is not, and client_abort is never a head defect.
  test("counters: absent reads compliant, a written value must be right, client_abort is informational", () => {
    const file = join(tmp, "counters.jsonl");
    writeFileSync(
      file,
      [
        '{"ts":1,"model":"m","outcome":"ok","total":10}',
        '{"ts":2,"model":"m","outcome":"client_abort"}',
      ].join("\n") + "\n",
    );
    const clean = perfRowsOk(file, 0, 1);
    expect(clean.ok).toBe(true);
    expect(clean.verdict).toContain("client_abort x1 (informational");

    writeFileSync(file, '{"ts":1,"model":"m","outcome":"ok","retries":2}\n');
    const dirty = perfRowsOk(file, 0, 1);
    expect(dirty.ok).toBe(false);
    expect(dirty.verdict).toContain("retries=[2]");
  });

  test("a trailing failure with nothing after it in its lane is unrecovered by construction", () => {
    const file = join(tmp, "trailing.jsonl");
    writeFileSync(file, ['{"ts":1,"model":"m","outcome":"ok"}', '{"ts":2,"model":"m","outcome":"http_500"}'].join("\n") + "\n");
    expect(perfRowsOk(file, 0, 1)).toEqual({
      ok: false,
      verdict: "unrecovered non-ok rows in window: http_500x1",
    });
  });

  test("a missing perf file is an empty window, not a crash", () => {
    const missing = perfRowsOk(join(tmp, "no-such-perf.jsonl"), 0, 1);
    expect(missing.ok).toBe(false);
    expect(missing.verdict).toContain("only 0 ok perf rows");
  });
});

describe("receipts", () => {
  test("the in-repo receipt is untouched — the selftest never fabricates a real head's receipt", () => {
    expect(sha(REPO_RECEIPT)).toBe(receiptShaBefore);
  });

  test("tier-1 receipt emission is intact, redirected into the scratch dir", () => {
    runArm({ SPLICE_E2E_CLIENT_TOKEN: "caller-e2e-token" });
    const emitted = join(receipts, `${HEAD_KEY}.json`);
    expect(existsSync(emitted)).toBe(true);
    const row = JSON.parse(readFileSync(emitted, "utf8")) as Record<string, unknown>;
    expect(row["head"]).toBe(HEAD_KEY);
    expect(row["http_status"]).toBe(200);
    expect(row["contract_bound"]).toBe(false);
  });
});

// ── V4-33: the operator-shaped tool surface is REAL, not a constant in a comment. ───────────────
// Red on the unfixed harness in three different places, because the first version of this arm was
// none of these walls: it grepped the harness for a >64-char constant while the planted "MCP
// server" was an inline `-c` one-liner — it exits before the first byte of the stdio handshake —
// and nothing wrote enabledMcpjsonServers, so Claude Code registered ZERO tools, the 68-char name
// never reached the wire, and the arm could not have caught the muse 400 it exists for. The three
// walls below are: the enable plumbing is present; the server script is DRIVEN for real; and the
// harness gate that reads the handshake receipt is red on a surface that never formed and green on
// the receipt this suite's own real server run just wrote.
describe("the over-long MCP tool surface", () => {
  const mcpScratch = (): string => join(tmp, "mcp-scratch");

  // WALL 1: the plumbing. The constants come FROM the harness module, never retyped here: a
  // selftest that spells the names itself stops testing the harness and starts testing its own
  // copy of it.
  test("wall 1: the composed name is over 64 chars and composes from the .mcp.json key plus a real server's tool", () => {
    expect(OVERLONG_TOOL_NAME).toBe(`mcp__${OVERLONG_MCP_SERVER}__${OVERLONG_MCP_TOOL}`);
    expect(OVERLONG_TOOL_NAME.length).toBeGreaterThan(64);
    expect(existsSync(join(ROOT, OVERLONG_MCP_SERVER_SCRIPT))).toBe(true);
  });

  test("wall 1: the harness still plants a REAL server, drives the plant, and asserts the surface formed", () => {
    const src = readFileSync(HARNESS_SRC, "utf8");
    // The exact HEAD shape, matched on the .mcp.json argv rather than on the prose that explains
    // it: an inline `-c` server exits before the first byte of the stdio handshake.
    expect(src).not.toContain('["-c"');
    expect(src).toContain("plantOverlongMcp(scratch, ctx.root)");
    expect(src).toContain("mcpSurfaceGate(ctx.report, key, scratch)");
    // NOT checked here: that the plant WRITES the enable settings. Reading this file for
    // "enabledMcpjsonServers" passes on the COMMENT that explains the key — a denominator taken
    // from the same text it is checking. Wall 2 runs the plant and reads what it produced instead.
    expect(src).toContain("a skip is not a pass");
  });

  // WALL 2: run the PLANT, then drive what it planted — command, args and env, nothing retyped.
  // `--tier plant-oracle` runs the harness's own plantOverlongMcp into a scratch dir; everything
  // below is read back out of the files it wrote, so a plant that stops enabling the server, stops
  // pointing at the real server script, or stops handing it a receipt path goes red here rather
  // than passing on the prose that describes it.
  test("wall 2: the plant-oracle writes the tier-2 MCP scratch", () => {
    mkdirSync(mcpScratch(), { recursive: true });
    const r = runArm({ E2E_MCP_SCRATCH: mcpScratch() }, ["--tier", "plant-oracle", "--head", HEAD_KEY]);
    expect(r.status).toBe(0);
    expect(existsSync(join(mcpScratch(), ".mcp.json"))).toBe(true);
  });

  test("wall 2: the planted config enables the server and spawns one that completes a real stdio handshake", async () => {
    const cfg = JSON.parse(readFileSync(join(mcpScratch(), ".mcp.json"), "utf8")) as {
      mcpServers: Record<string, { command: string; args: string[]; env: Record<string, string> }>;
    };
    // The composed wire name comes from this key.
    expect(Object.keys(cfg.mcpServers)).toEqual([OVERLONG_MCP_SERVER]);
    const entry = cfg.mcpServers[OVERLONG_MCP_SERVER]!;

    // The enable settings are the other half of the HEAD defect: a project .mcp.json that Claude
    // Code has not been told to trust contributes no tools at all, and tier 2 has nobody to click
    // approve.
    let enabled = false;
    for (const name of ["settings.local.json", "settings.json"]) {
      const path = join(mcpScratch(), ".claude", name);
      if (!existsSync(path)) continue;
      const data = JSON.parse(readFileSync(path, "utf8")) as {
        enabledMcpjsonServers?: string[];
        enableAllProjectMcpServers?: boolean;
      };
      if ((data.enabledMcpjsonServers ?? []).includes(OVERLONG_MCP_SERVER) || data.enableAllProjectMcpServers) {
        enabled = true;
      }
    }
    expect(enabled).toBe(true);
    expect(resolve(entry.args[0]!)).toBe(resolve(join(ROOT, OVERLONG_MCP_SERVER_SCRIPT)));
    expect(entry.env["SPLICE_E2E_MCP_LOG"]).toBeTruthy();

    // Spawn it exactly as Claude Code would: the planted command, the planted args, the planted env.
    const proc = Bun.spawn([entry.command, ...entry.args], {
      env: { ...process.env, ...entry.env },
      stdio: ["pipe", "pipe", "pipe"],
    });
    const reader = proc.stdout.getReader();
    let buffered = "";
    const readLine = async (): Promise<string> => {
      for (;;) {
        const nl = buffered.indexOf("\n");
        if (nl >= 0) {
          const line = buffered.slice(0, nl);
          buffered = buffered.slice(nl + 1);
          if (line.trim()) return line;
          continue;
        }
        const { value, done } = await reader.read();
        if (done) throw new Error("server closed stdout before answering");
        buffered += new TextDecoder().decode(value);
      }
    };
    const send = (obj: unknown): void => {
      proc.stdin.write(JSON.stringify(obj) + "\n");
      proc.stdin.flush();
    };
    const expectId = async (id: number): Promise<Record<string, unknown>> => {
      const row = JSON.parse(await readLine()) as Record<string, unknown>;
      expect(row["id"]).toBe(id);
      expect(row["error"]).toBeUndefined();
      return row["result"] as Record<string, unknown>;
    };

    try {
      send({
        jsonrpc: "2.0", id: 1, method: "initialize",
        params: { protocolVersion: "2025-11-25", capabilities: {}, clientInfo: { name: "heads-selftest", version: "1" } },
      });
      const init = await expectId(1);
      expect(init["protocolVersion"]).toBe("2025-11-25");
      expect(init["capabilities"]).toHaveProperty("tools");
      // the third leg of the handshake; answering a notification is a protocol violation, so
      // nothing is read back for it
      send({ jsonrpc: "2.0", method: "notifications/initialized" });

      send({ jsonrpc: "2.0", id: 2, method: "tools/list" });
      const names = (((await expectId(2))["tools"] as Array<{ name: string }>) ?? []).map((t) => t.name);
      expect(names).toEqual([OVERLONG_MCP_TOOL]);
      expect(`mcp__${OVERLONG_MCP_SERVER}__${names[0]}`).toBe(OVERLONG_TOOL_NAME);

      send({ jsonrpc: "2.0", id: 3, method: "tools/call", params: { name: OVERLONG_MCP_TOOL, arguments: {} } });
      const content = ((await expectId(3))["content"] as Array<{ type: string; text: string }>) ?? [];
      expect(content[0]?.type).toBe("text");
      expect(content[0]?.text).toBeTruthy();
    } finally {
      proc.stdin.end();
      await proc.exited;
      reader.releaseLock();
    }

    const methods = readFileSync(entry.env["SPLICE_E2E_MCP_LOG"]!, "utf8")
      .split("\n")
      .filter((l) => l.trim())
      .map((l) => (JSON.parse(l) as { method: string }).method);
    for (const needed of ["initialize", "notifications/initialized", "tools/list", "tools/call"]) {
      expect(methods).toContain(needed);
    }
  });

  // WALL 3: the harness gate is red when the surface never formed. Mutation-proof, both boring
  // cases. (a) no receipt at all — the HEAD shape exactly: config planted, server never spawned.
  // (b) a receipt with initialize and nothing else — the server started but its tools never
  // entered the session, which passes every "is the file there" check.
  test("wall 3: the tool-surface gate reds when the planted server was never spawned", () => {
    const dir = join(tmp, "mcp-never-spawned");
    mkdirSync(dir, { recursive: true });
    const r = runArm({ E2E_MCP_SCRATCH: dir }, ["--tier", "mcp-oracle", "--head", HEAD_KEY]);
    expect(r.status).not.toBe(0);
    expect(r.stderr).toContain("no MCP handshake receipt");
  });

  test("wall 3: the gate reds when the server initialized but listed no tools", () => {
    const dir = join(tmp, "mcp-no-tools");
    mkdirSync(dir, { recursive: true });
    writeFileSync(join(dir, OVERLONG_MCP_LOG_NAME), '{"ts":1,"method":"initialize","protocol":"2026-09-01"}\n');
    const r = runArm({ E2E_MCP_SCRATCH: dir }, ["--tier", "mcp-oracle", "--head", HEAD_KEY]);
    expect(r.status).not.toBe(0);
    expect(r.stderr).toContain("no tools/list row");
  });

  // The green half comes from a REAL server run, never a hand-written fixture, so the gate and the
  // server cannot drift apart while agreeing with each other.
  test("wall 3, green: the same gate passes on the real server's own handshake receipt", () => {
    const r = runArm({ E2E_MCP_SCRATCH: mcpScratch() }, ["--tier", "mcp-oracle", "--head", HEAD_KEY]);
    expect(r.status).toBe(0);
    expect(r.stderr).toContain("entered the session tool surface");
  });
});

// ── tier 2, driven against stub wrappers on a tmux-capable box ──────────────────────────────────
describe.if(hasTmux)("tier 2 tmux drive", () => {
  const wrapper = (body: string): void => {
    writeFileSync(join(tmp, HEAD_KEY), body);
    chmodSync(join(tmp, HEAD_KEY), 0o755);
  };

  // DR-110. Red on the unfixed harness: the bare `wait_pane ...; rc=$?` errexited at the call, so
  // the README-promised SKIP never ran — the first not-logged-in head killed the run mid-roster
  // with no summary and a leaked tmux session. The stub prints the auth-needed pane text
  // (waitPane's "auth" pattern) instantly; PATH injection resolves the advertised label to it.
  test("a not-logged-in wrapper records a SKIP and the run completes", () => {
    wrapper('#!/bin/sh\necho "not logged in"\nsleep 60\n');
    const r = runArm({ PATH: `${tmp}:${process.env.PATH ?? ""}` }, ["--tier", "2", "--head", HEAD_KEY]);
    expect(r.status).toBe(0);
    expect(r.stderr).toContain("head not logged in");
    Bun.spawnSync(["tmux", "-L", "splice-e2e", "kill-server"], { stdio: ["ignore", "ignore", "ignore"] });
  });

  // V4-33: tier 2 answers the folder-trust dialog with YES, and the tool-surface gate is LIVE.
  // Red on the unfixed harness: the trust dialog's default selection is "No, exit" (Claude Code
  // 2.1.257), tier 2 sent a bare Enter, and the wrapper exited before a single turn — the drive
  // then failed 90 seconds later as "TUI never became ready", which reads like a head defect.
  // Every tier-2 scratch is a fresh temp dir, so this dialog is drawn on EVERY head of EVERY run.
  //
  // The stub is a real terminal program, not an echo: it draws the dialog with the cursor on "No",
  // moves it only on a Down key, and exits nonzero on an Enter pressed while "No" is selected — so
  // a harness that answers the dialog wrongly cannot reach readiness here either. It then answers
  // the two drive prompts so the arm costs seconds rather than two 150s timeouts.
  test("tier 2 answers the folder-trust dialog with YES, drives both turns, and reds the tool surface a stub never formed", () => {
    const stub = join(tmp, "trust-stub.ts");
    const marker = join(tmp, "trust-accepted");
    writeFileSync(
      stub,
      `const MARKER = process.env.TRUST_MARKER!;
// The ALTERNATE SCREEN is not decoration: without it tmux pushes every cleared frame into
// scrollback, capture-pane -S -160 keeps showing the dialog after it is answered, and the
// harness's dialog-first branch would match forever. Real Claude Code uses it, so the stub must.
process.stdout.write("\\x1b[?1049h");
let sel = 0, trusted = false, line = "", pending = "";
function draw() {
  process.stdout.write("\\x1b[2J\\x1b[H");
  process.stdout.write("Quick safety check: do you trust this folder\\r\\n");
  process.stdout.write((sel === 0 ? "\\u276f " : "  ") + "No, exit\\r\\n");
  process.stdout.write((sel === 1 ? "\\u276f " : "  ") + "Yes, I trust this folder\\r\\n");
}
process.stdin.setRawMode?.(true);
process.stdin.resume();
process.stdin.setEncoding("utf8");
draw();
process.stdin.on("data", (chunk: string) => {
  for (const ch of chunk) {
    if (pending || ch === "\\x1b") {
      pending += ch;
      if (pending.length >= 3) {
        if (pending.endsWith("B")) sel = 1;
        else if (pending.endsWith("A")) sel = 0;
        pending = "";
        if (!trusted) draw();
      }
      continue;
    }
    if (ch === "\\r" || ch === "\\n") {
      if (!trusted) {
        if (sel !== 1) {
          process.stdout.write("\\r\\nTRUST_DENIED — the harness answered No\\r\\n");
          process.exit(1);
        }
        trusted = true;
        require("node:fs").writeFileSync(MARKER, "yes\\n");
        process.stdout.write("\\x1b[2J\\x1b[H ready\\r\\n");
        process.stdout.write(" \\u23f5\\u23f5 bypass permissions on (shift+tab to cycle)\\r\\n");
      } else {
        if (line.includes("six times seven")) process.stdout.write("ANSWER=42\\r\\n");
        else if (line.includes("SECOND")) process.stdout.write("SECOND=DONE\\r\\n");
      }
      line = "";
      continue;
    }
    line += ch;
  }
});
`,
    );
    wrapper(`#!/bin/sh\nexec ${process.execPath} ${stub}\n`);
    const r = runArm({ PATH: `${tmp}:${process.env.PATH ?? ""}`, TRUST_MARKER: marker }, ["--tier", "2", "--head", HEAD_KEY]);
    expect(existsSync(marker)).toBe(true);
    expect(r.stderr).toContain(`✓ ${HEAD_KEY}/tui-turn1`);
    expect(r.stderr).toContain(`✓ ${HEAD_KEY}/tui-turn2`);
    // The gate is LIVE inside tier 2, not just reachable from the mcp-oracle hook: a stub wrapper
    // is not Claude Code, so it spawns no MCP server and the tool surface must be reported missing.
    expect(r.stderr).toContain(`✗ ${HEAD_KEY}/mcp-tool-surface`);
    Bun.spawnSync(["tmux", "-L", "splice-e2e", "kill-server"], { stdio: ["ignore", "ignore", "ignore"] });
  }, 180_000);
});

// ── the reasoning-cache probe (merged from checks/e2e/reasoning-cache-probe.sh) ──────────────────
describe("reasoning-cache probe", () => {
  test("a missing fat jar REFUSES with exit 2 and names its producer — never a nested gradle", () => {
    const r = runArm({ RCP_JAR: "/nonexistent/app-all.jar" }, ["reasoning-cache"]);
    expect(r.status).toBe(HARNESS_EXIT);
    expect(r.stderr).toContain("fat jar missing at /nonexistent/app-all.jar");
    expect(r.stderr).toContain("bun tools/gate slot <label> -- :app:shadowJar");
    expect(r.stderr).not.toContain("gradlew");
  });

  test("the scripted upstream answers per scenario and round, and 400s only a poisoned follow-up", () => {
    const ids = (p: { payload: unknown }): unknown[] => p.payload as unknown[];
    expect(plan("A", 0, false).status).toBe(200);
    expect(JSON.stringify(ids(plan("A", 0, false)))).toContain("env_a1");
    expect(JSON.stringify(ids(plan("A", 2, true)))).toContain("env_a2");
    expect(plan("B", 1, true).status).toBe(400);
    expect(plan("B", 1, false).status).toBe(200);
    expect(JSON.stringify(ids(plan("B", 1, false)))).toContain("RECOVERED");
    expect(plan("nope", 0, false).status).toBe(500);
  });

  // The acceptance itself, red and green, over recorded rows — the wire assertions are provable
  // without a jar, a daemon or a vendor.
  const row = (r: Partial<UpstreamRow> & Pick<UpstreamRow, "seq" | "scenario" | "outputs">): UpstreamRow => ({
    status: 200,
    include: ["reasoning.encrypted_content"],
    input: [],
    ...r,
  });
  const green = (): UpstreamRow[] => [
    row({ seq: 1, scenario: "A", outputs: 0 }),
    row({
      seq: 2, scenario: "A", outputs: 2,
      input: [
        { type: "reasoning", id: "rs_a1", encrypted_content: "env_a1" },
        { type: "function_call", call_id: "call_a1a" },
        { type: "function_call", call_id: "call_a1b" },
      ],
    }),
    row({
      seq: 3, scenario: "A", outputs: 3,
      input: [
        { type: "reasoning", id: "rs_a1", encrypted_content: "env_a1" },
        { type: "function_call", call_id: "call_a1a" },
        { type: "reasoning", id: "rs_a2", encrypted_content: "env_a2" },
        { type: "function_call", call_id: "call_a2" },
      ],
    }),
    row({
      seq: 4, scenario: "B", outputs: 1, status: 400,
      input: [{ type: "reasoning", id: "rs_b1", encrypted_content: "env_b1" }, { type: "function_call", call_id: "call_b1" }],
    }),
    row({ seq: 5, scenario: "B", outputs: 1, input: [{ type: "function_call", call_id: "call_b1" }] }),
    row({ seq: 6, scenario: "OFF", outputs: 1, input: [] }),
  ];

  test("the wire assertions pass on a faithful recording and report both wire states", () => {
    const { failures, summary } = assertWire(green());
    expect(failures).toEqual([]);
    expect(summary[1]).toContain("in-position before its function_call");
  });

  test("RED: an envelope injected twice, out of position, or never stripped on the 400 retry", () => {
    const duplicated = green();
    duplicated[1]!.input = [
      { type: "reasoning", id: "rs_a1", encrypted_content: "env_a1" },
      { type: "reasoning", id: "rs_a1", encrypted_content: "env_a1" },
      { type: "function_call", call_id: "call_a1a" },
      { type: "function_call", call_id: "call_a1b" },
    ];
    expect(assertWire(duplicated).failures.join("; ")).toContain("wanted exactly [\"rs_a1\"]");

    const unstripped = green();
    unstripped[4]!.input = [
      { type: "reasoning", id: "rs_b1", encrypted_content: "env_b1" },
      { type: "function_call", call_id: "call_b1" },
    ];
    expect(assertWire(unstripped).failures.join("; ")).toContain("retry still carries reasoning");

    const amnesiaBroken = green();
    amnesiaBroken[5]!.input = [{ type: "reasoning", id: "rs_o1", encrypted_content: "env_o1" }];
    expect(assertWire(amnesiaBroken).failures.join("; ")).toContain("cache off must carry zero reasoning");

    const noInclude = green();
    noInclude[0]!.include = [];
    expect(assertWire(noInclude).failures.join("; ")).toContain("include lacks reasoning.encrypted_content");
  });
});

describe("argv contract and the state-root rule", () => {
  test("an unknown flag exits 2 by name; the help lists the heads verb", () => {
    const bad = runArm({}, ["--nope"]);
    expect(bad.status).toBe(HARNESS_EXIT);
    expect(bad.stderr).toContain("unknown arg: --nope");
    const help = Bun.spawnSync([process.execPath, CLI, "--help"], { stdio: ["ignore", "pipe", "pipe"] });
    expect(help.stdout.toString()).toContain("heads [--tier");
  });

  test("--list prints the discovered roster and probes nothing", () => {
    const r = runArm({}, ["--list"]);
    expect(r.status).toBe(0);
    expect(r.stdout).toContain(`${HEAD_KEY}\t${HEAD_KEY}\t`);
    expect(headRows()).toEqual([]);
  });

  test("a bad --tier exits 2 by name", () => {
    const r = runArm({}, ["--tier", "nonsense", "--head", HEAD_KEY]);
    expect(r.status).toBe(HARNESS_EXIT);
    expect(r.stderr).toContain("bad --tier nonsense");
  });

  // The state-root rule's TS copy, pinned here as well as by StateLayoutDoctorTest: env first
  // (blank and whitespace-only are NOT answers, per variable), then ~/.splice/state, adopting a
  // pre-0.4 ~/.claude-codex/state in place only on PROVEN absence of the current root.
  test("liveStateDir: env wins per variable, blank falls through, adoption needs proven absence", () => {
    const home = mkdtempSync(join(tmpdir(), "heads-state-"));
    expect(liveStateDir(home, {})).toBe(join(home, ".splice", "state"));
    mkdirSync(join(home, ".claude-codex", "state"), { recursive: true });
    expect(liveStateDir(home, {})).toBe(join(home, ".claude-codex", "state"));
    expect(liveStateDir(home, { SPLICE_STATE_DIR: "/pointed/new" })).toBe("/pointed/new");
    expect(liveStateDir(home, { SPLICE_STATE_DIR: "", CLAUDEX_STATE_DIR: "/pointed/old" })).toBe("/pointed/old");
    expect(liveStateDir(home, { SPLICE_STATE_DIR: "   " })).toBe(join(home, ".claude-codex", "state"));
    // A REGULAR FILE at the pre-0.4 path is not proven presence of a root either.
    const other = mkdtempSync(join(tmpdir(), "heads-state-"));
    mkdirSync(join(other, ".claude-codex"), { recursive: true });
    writeFileSync(join(other, ".claude-codex", "state"), "not a directory");
    expect(liveStateDir(other, {})).toBe(join(other, ".splice", "state"));
    rmSync(home, { recursive: true, force: true });
    rmSync(other, { recursive: true, force: true });
  });
});
