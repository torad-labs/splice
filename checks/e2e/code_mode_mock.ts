#!/usr/bin/env bun
/** Exercise the packaged code-mode bridge through an isolated daemon and loopback upstream.
 *
 *  No real credentials or vendor calls. Client tools use code_mode_probe's in-memory workspace.
 *
 *  V4-145: converted from code_mode_mock.py. The mock upstream is pyhttp's BaseHTTPRequestHandler
 *  transcription at http.server's default HTTP/1.0 — one request per connection, send_error()'s
 *  exact 400 — because that is the upstream the daemon was proven against. request_json is reached
 *  through `seams` for the one test that patched it.
 */
import { createHash } from "node:crypto";
import { chmodSync, closeSync, existsSync, mkdirSync, mkdtempSync, openSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, dirname, join, resolve } from "node:path";
import { dumps, dumpsIndent, loadsBytes, obj, type PyObj, type PyValue } from "./pyjson.ts";
import { ephemeralPorts, threadingHTTPServer, type Methods, type Request } from "./pyhttp.ts";
import { GUIDANCE } from "./code_mode_guidance.ts";
import { CASES, MAX_BODY, TOOLS, requestJson, runCase } from "./code_mode_probe.ts";
import {
  argparse, argparseError, at, check, get, iter, isKeyError, isOSError, isTypeError, isValueError, osError, popen, pyEq,
  pyInt, runUnittest, setKey, StopIteration, sub, SubprocessError, TimeoutExpired, truthy, ValueError, type Proc, type Tests,
} from "./pyshim.ts";

export const SCRIPTS: Record<string, string> = {
  "lookup-edit": 'const data = JSON.parse(await tools.call("Read", {file_path:"settings.json"})); ' +
    'data.timeout = 20; await tools.call("Write", {file_path:"settings.json", content:JSON.stringify(data)}); ' +
    'return "UPDATED";',
  "independent-reads": 'const values = await Promise.all(["a.txt","b.txt","c.txt"].map(' +
    "file_path => tools.call(\"Read\", {file_path}))); return values.reduce((s,v)=>s+Number(v),0);",
  "optional-discovery": 'return await tools.call("mcp__release__lookup", {});',
  "background-result": 'return await tools.call("Agent", {prompt:"7 times 8"});',
};
export const EXPECTED: Record<string, string> = {
  "lookup-edit": "UPDATED", "independent-reads": "110", "optional-discovery": "0.4.0", "background-result": "56",
};
export const EXPECTED_ROUNDS: Record<string, number> = {
  ...Object.fromEntries(CASES.map(([c]) => [c, c === "optional-discovery" ? 3 : 2])),
  "toggle-probe": 4,
};
export const PREFACE = "Checking the current settings before updating them.";
export const CALLER_SYSTEM = "Use tools to verify the requested facts. Keep the final answer concise. " +
  "If a script tool is available, it may batch related tool operations. " +
  "Only synthetic tools exist; do not invent results or poll background jobs.";
export const TOGGLE_SYSTEM = "Toggle probe caller instructions.";
export const TOGGLE_PROMPT = "TOGGLE_GUIDANCE_PROBE";
export const TOGGLE_RESPONSE = "TOGGLE_OK";

const int = (n: number): PyValue => ({ __pyNum: String(n), isFloat: false });
const countOf = (hay: string, needle: string) => hay.split(needle).length - 1;

export function assertGuidance(body: PyValue, callerSystem: string, enabled: boolean): void {
  const inputItems = get(body, "input");
  if (!Array.isArray(inputItems) || inputItems.length < 2) {
    throw new ValueError("Responses lite input omitted its developer instruction");
  }
  if (!pyEq(get(inputItems[0], "type"), "additional_tools") || !pyEq(get(inputItems[0], "role"), "developer")) {
    throw new ValueError("missing Responses lite additional_tools prefix");
  }
  const developers = inputItems.filter((item) => pyEq(get(item, "role"), "developer") && !pyEq(get(item, "type"), "additional_tools"));
  if (developers.length !== 1 || developers[0] !== inputItems[1]) {
    throw new ValueError("developer instructions moved or duplicated");
  }
  const instructions = get(developers[0], "content");
  const expected = callerSystem + (enabled ? "\n\n" + GUIDANCE : "");
  if (instructions !== expected) {
    throw new ValueError("developer instructions did not preserve the expected caller/guidance boundary");
  }
  if (countOf(instructions, "<code_mode_orchestration>") !== Number(enabled)) {
    throw new ValueError("code-mode guidance section count was not exactly one when enabled");
  }
  const declarations = iter(get(inputItems[0], "tools", []));
  const runners = declarations.filter((tool) => pyEq(get(tool, "name"), "splice_exec"));
  if (runners.length !== Number(enabled) || runners.some((tool) => !pyEq(get(tool, "type"), "custom"))) {
    throw new ValueError("code-mode runner and guidance were not coupled");
  }
}

/** The mock upstream's shared state: per-case round counters, the toggle expectations, and the first
 *  protocol assertion that failed. */
export type MockState = Map<string, unknown>;
const counter = (state: MockState, k: string) => (state.get(k) as number | undefined) ?? 0;

function message(id: string, text: string): PyObj {
  return obj([["type", "message"], ["id", id], ["role", "assistant"], ["status", "completed"],
    ["content", [obj([["type", "output_text"], ["text", text], ["annotations", []]])]]]);
}

export function mockHandler(state: MockState): Methods {
  return {
    async POST(h: Request): Promise<void> {
      try {
        const size = pyInt(h.headers.get("Content-Length", "0") as string);
        if (h.path !== "/responses" || !(0 < size && size <= MAX_BODY)) throw new ValueError("unexpected mock request");
        const body = loadsBytes(await h.rfile.read(size));
        const wire = dumps(body);
        let caseName: string;
        if (wire.includes("AUTH_PREFLIGHT")) caseName = "auth-preflight";
        else if (wire.includes(TOGGLE_PROMPT)) caseName = "toggle-probe";
        else {
          const hit = CASES.find(([, prompt]) => wire.includes(prompt));
          if (hit === undefined) throw new StopIteration("");
          caseName = hit[0];
        }
        const account = caseName === "auth-preflight" ? "baseline" : "code_mode";
        if (h.headers.get("ChatGPT-Account-Id") !== account
          || !(h.headers.get("Authorization", "") as string).endsWith(".synthetic-" + account)) {
          throw new ValueError("wrong per-provider synthetic credentials");
        }
        state.set(caseName, counter(state, caseName) + 1);
        if (counter(state, caseName) > (EXPECTED_ROUNDS[caseName] ?? 1)) throw new ValueError("extra backend round");
        const input = sub(body, "input");
        const output = iter(input).filter((item) => pyEq(get(item, "type"), "custom_tool_call_output"));
        // .get("tools", []) alone: the iteration that can raise TypeError happens at each any(),
        // after assert_guidance, so it must not be hoisted here.
        const declaredValue = get(at(input, 0), "tools", []);
        const declared = () => iter(declaredValue);
        let item: PyObj;
        if (caseName === "auth-preflight") item = message("auth-ok", "AUTH_OK");
        else if (caseName === "toggle-probe") {
          if (!state.has("toggle_enabled")) throw new ValueError("toggle probe arrived without an expected TOML state");
          assertGuidance(body, TOGGLE_SYSTEM, state.get("toggle_enabled") as boolean);
          if (!state.has("toggle_seen")) state.set("toggle_seen", []);
          (state.get("toggle_seen") as boolean[]).push(state.get("toggle_enabled") as boolean);
          item = message("toggle-ok", TOGGLE_RESPONSE);
        } else {
          assertGuidance(body, CALLER_SYSTEM, true);
          if (caseName === "optional-discovery" && counter(state, caseName) === 1) {
            if (declared().some((t) => pyEq(get(t, "name"), "mcp__release__lookup"))) throw new ValueError("release lookup was not deferred");
            if (!declared().some((t) => pyEq(get(t, "type"), "tool_search"))) throw new ValueError("missing native discovery declaration");
            item = obj([["type", "tool_search_call"], ["id", "native-search-item"], ["call_id", "native-search"],
              ["arguments", obj([["query", "release lookup"], ["limit", int(1)]])]]);
          } else if (output.length === 0) {
            if (caseName === "optional-discovery" && !iter(input).some((it) => pyEq(get(it, "type"), "tool_search_output")
              && pyEq(get(it, "call_id"), "native-search")
              && iter(get(it, "tools", [])).some((t) => pyEq(get(t, "name"), "mcp__release__lookup")))) {
              throw new ValueError("native discovery did not expose release lookup");
            }
            if (!declared().some((t) => pyEq(get(t, "type"), "custom") && pyEq(get(t, "name"), "splice_exec"))) {
              throw new ValueError("missing code-mode declaration");
            }
            item = obj([["type", "custom_tool_call"], ["id", "item-" + caseName], ["call_id", "outer-" + caseName],
              ["name", "splice_exec"], ["input", SCRIPTS[caseName]], ["status", "completed"]]);
          } else {
            const expected = EXPECTED[caseName];
            const evidence = caseName === "background-result" ? wire : dumps(output);
            if (!evidence.includes(expected)) throw new ValueError("missing real synthetic tool evidence");
            if (iter(input).some((it) => pyEq(get(it, "type"), "function_call") || pyEq(get(it, "type"), "function_call_output"))) {
              throw new ValueError("owned client tool pairs leaked upstream");
            }
            if (caseName === "lookup-edit") {
              const entries = iter(input);
              const prefaces = entries.flatMap((entry, index) => (dumps(entry).includes(PREFACE) ? [index] : []));
              const outerIndex = entries.findIndex((entry) => pyEq(get(entry, "type"), "custom_tool_call"));
              if (outerIndex < 0) throw new StopIteration("");
              if (prefaces.length !== 1 || prefaces[0] >= outerIndex) {
                throw new ValueError("assistant continuity was lost, duplicated, or reordered");
              }
            }
            if (caseName === "optional-discovery") {
              const native = iter(input).filter((it) => pyEq(get(it, "call_id"), "native-search")).map((it) => get(it, "type"));
              if (!(native.length === 2 && pyEq(native[0], "tool_search_call") && pyEq(native[1], "tool_search_output"))) {
                throw new ValueError("native discovery history was lost or duplicated");
              }
            }
            item = message("final-" + caseName, expected);
          }
        }
        const items: PyObj[] = [item];
        if (caseName === "lookup-edit" && sub(item, "type") === "custom_tool_call") {
          // The client must replay emitted text while the same script awaits dependent calls.
          items.unshift(message("preface", PREFACE));
        }
        const response = obj([["id", "response-" + caseName], ["status", "completed"], ["output", items],
          ["usage", obj([["input_tokens", int(100)], ["output_tokens", int(10)],
            ["input_tokens_details", obj([["cached_tokens", int(0)]])]])]]);
        const events: PyValue[] = [obj([["type", "response.created"], ["response", obj([["id", sub(response, "id")], ["output", []]])]])];
        items.forEach((emitted, index) => {
          events.push(obj([["type", "response.output_item.added"], ["output_index", int(index)], ["item", emitted]]));
          if (sub(emitted, "type") === "message") {
            (sub(emitted, "content") as PyValue[]).forEach((part, contentIndex) => {
              events.push(obj([["type", "response.output_text.delta"], ["output_index", int(index)],
                ["content_index", int(contentIndex)], ["delta", sub(part, "text")]]));
            });
          }
          events.push(obj([["type", "response.output_item.done"], ["output_index", int(index)], ["item", emitted]]));
        });
        events.push(obj([["type", "response.completed"], ["response", response]]));
        const data = Buffer.from(events.map((event) => "data: " + dumps(event) + "\n\n").join(""), "utf8");
        h.sendResponse(200);
        h.sendHeader("Content-Type", "text/event-stream");
        h.sendHeader("Content-Length", String(data.length));
        h.endHeaders();
        h.wfile.write(data);
      } catch (error) {
        if (!(isValueError(error) || isKeyError(error) || isTypeError(error) || error instanceof StopIteration)) throw error;
        state.set("error", (error as Error).message);
        h.sendError(400, "mock protocol assertion failed");
      }
    },
  };
}

export const availablePorts = (): Promise<number[]> => ephemeralPorts(3);

/** datetime.now(timezone.utc).isoformat(): microseconds (omitted when zero) and a +00:00 offset. */
function isoNowUtc(): string {
  const now = new Date();
  const micros = now.getUTCMilliseconds() * 1000;
  const base = now.toISOString().slice(0, 19);
  return micros === 0 ? `${base}+00:00` : `${base}.${String(micros).padStart(6, "0")}+00:00`;
}

export function configure(root: string, upstream: number, control: number, head: number, baseline: number,
  codeMode: boolean | null = true): Record<string, string> {
  const state = join(root, "state");
  try {
    mkdirSync(state);
  } catch (e) {
    throw osError(e, state);
  }
  const bearer = "synthetic-management-" + Buffer.from(crypto.getRandomValues(new Uint8Array(12))).toString("hex");
  writeFileSync(join(state, "mgmt-key"), bearer);
  chmodSync(join(state, "mgmt-key"), 0o600);
  const payload = Buffer.from(dumps(obj([["exp", int(Math.floor(Date.now() / 1000) + 3600)]])), "utf8")
    .toString("base64").replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
  const blocks = [`[daemon]\ncontrol_port = ${control}\n`];
  for (const [name, port, enabled] of [["baseline", baseline, false], ["code_mode", head, codeMode]] as const) {
    const syntheticToken = "e30." + payload + ".synthetic-" + name;
    const auth = join(root, `${name}-auth.json`);
    writeFileSync(auth, dumps(obj([
      ["tokens", obj([["access_token", syntheticToken], ["refresh_token", "mock-refresh"], ["account_id", name]])],
      ["last_refresh", isoNowUtc()],
    ])));
    chmodSync(auth, 0o600);
    const codeModeField = enabled === null ? "" : `code_mode = ${enabled ? "true" : "false"}, `;
    blocks.push(`[providers.${name}]
dialect = "openai-responses"
base_url = "http://127.0.0.1:${upstream}"
auth = { kind = "chatgpt-oauth", file = "${auth}" }
quirks = { ${codeModeField}account_id_header = true, websocket = false, zstd_request_body = false, tool_surface = { enabled = true } }
[[providers.${name}.models]]
id = "gpt-6-astra"
context_window = 400000
[heads.${name}]
provider = "${name}"
port = ${port}
discovery_prefix = "claude-${name}--"
pinned_model = "gpt-6-astra"
[heads.${name}.claude]
command = "claude-${name}"
`);
  }
  const config = join(root, "splice.toml");
  writeFileSync(config, blocks.join("\n"));
  return {
    ...(process.env as Record<string, string>),
    SPLICE_CONFIG: config, CLAUDEX_STATE_DIR: state, CLAUDEX_QUOTA_POLL: "off",
    CODEX_AUTH_PATH: join(root, "missing-legacy-auth.json"),
    CODEX_OAUTH_TOKEN_URL: `http://127.0.0.1:${upstream}/oauth/token`, SPLICE_PROBE_BEARER: bearer,
  };
}

export async function startDaemon(root: string, artifact: string, env: Record<string, string>, control: number): Promise<[Proc, number]> {
  const log = openSync(join(root, "daemon-output.log"), "w");
  const proc = popen(["java", "-Xmx256m", `-Duser.home=${root}`, "-jar", artifact, "daemon"], { env, cwd: root, stdoutFd: log });
  try {
    const deadline = performance.now() / 1000 + 20;
    for (;;) {
      if (proc.poll() !== null) throw new ValueError("isolated daemon exited during startup");
      try {
        const health = await seams.requestJson(control, "GET", "/health");
        if (truthy(get(health, "ok")) && pyEq(get(health, "readyHeads"), int(2))) return [proc, log];
      } catch (e) {
        if (!(isOSError(e) || isValueError(e))) throw e;
      }
      if (performance.now() / 1000 >= deadline) throw new ValueError("isolated daemon did not become ready");
      await Bun.sleep(100);
    }
  } catch (e) {
    await stopDaemon(proc, log);
    throw e;
  }
}

export async function stopDaemon(proc: Proc, log: number): Promise<void> {
  proc.terminate();
  try {
    try {
      await proc.wait(10);
    } catch (e) {
      if (!(e instanceof TimeoutExpired)) throw e;
      proc.kill();
      await proc.wait(5);
    }
  } finally {
    closeSync(log);
  }
}

export async function runToggleProbe(head: number, bearer: string): Promise<void> {
  const response = await seams.requestJson(head, "POST", "/v1/messages", obj([
    ["model", "gpt-6-astra"], ["max_tokens", int(100)], ["stream", false], ["system", TOGGLE_SYSTEM],
    ["output_config", obj([["effort", "high"]])], ["tools", TOOLS],
    ["messages", [obj([["role", "user"], ["content", TOGGLE_PROMPT]])]],
  ]), [["Content-Type", "application/json"], ["Authorization", "Bearer " + bearer],
    ["x-claude-code-session-id", crypto.randomUUID()]]);
  if (!pyEq(get(response, "stop_reason"), "end_turn")
    || !iter(get(response, "content", [])).some((part) => pyEq(get(part, "text"), TOGGLE_RESPONSE))) {
    throw new ValueError("toggle probe did not receive its terminal loopback response");
  }
}

export async function runToggleBoots(root: string, artifact: string, upstream: number, state: MockState): Promise<void> {
  // v0.4.0 (V4-11): code mode is ON by default for the chatgpt-oauth + openai-responses shape,
  // so an omitted key means enabled; an explicit false still turns it off.
  const expectedStates: [boolean | null, boolean][] = [[null, true], [true, true], [false, false], [true, true]];
  for (const [index, [configured, expectedEnabled]] of expectedStates.entries()) {
    const boot = join(root, `toggle-${index}`);
    mkdirSync(boot);
    const [control, head, baseline] = await availablePorts();
    const env = configure(boot, upstream, control, head, baseline, configured);
    state.set("toggle_enabled", expectedEnabled);
    const [proc, log] = await startDaemon(boot, artifact, env, control);
    try {
      await runToggleProbe(head, env.SPLICE_PROBE_BEARER);
    } finally {
      await stopDaemon(proc, log);
    }
  }
  const seen = (state.get("toggle_seen") as boolean[] | undefined) ?? null;
  const want = expectedStates.map(([, enabled]) => enabled);
  if (seen === null || seen.length !== want.length || seen.some((v, i) => v !== want[i])) {
    throw new ValueError("fresh TOML boots did not preserve the requested code-mode toggle sequence");
  }
}

/** Path.with_suffix(".daemon.log"): the last suffix replaced, or appended when there is none. */
function withSuffix(path: string, suffix: string): string {
  const name = basename(path);
  const dot = name.lastIndexOf(".");
  return join(dirname(path), (dot > 0 ? name.slice(0, dot) : name) + suffix);
}

export async function run(args: { artifact: string; receipt: string }): Promise<void> {
  const artifact = resolve(args.artifact);
  const receipt = args.receipt;
  if (existsSync(receipt)) throw new ValueError("refusing to overwrite a receipt");
  let bytes: Buffer;
  try {
    bytes = readFileSync(artifact);
  } catch (e) {
    throw osError(e, artifact);
  }
  const digest = createHash("sha256").update(bytes).digest("hex");
  process.stdout.write("Packaged mock bridge SHA-256: " + digest + "\n");
  const state: MockState = new Map();
  const rows: PyObj[] = [];
  let failure: string | null = null;
  const server = await threadingHTTPServer("127.0.0.1", 0, mockHandler(state), "HTTP/1.0");
  const loop = server.serveForever();
  try {
    const root = mkdtempSync(join(tmpdir(), "code-mode-mock-"));
    try {
      const [control, head, baseline] = await availablePorts();
      const env = configure(root, server.serverPort, control, head, baseline);
      const previous = process.env.SPLICE_PROBE_BEARER;
      process.env.SPLICE_PROBE_BEARER = env.SPLICE_PROBE_BEARER;
      try {
        const [proc, log] = await startDaemon(root, artifact, env, control);
        try {
          const preflight = await seams.requestJson(baseline, "POST", "/v1/messages", obj([
            ["model", "gpt-6-astra"], ["max_tokens", int(100)], ["stream", false],
            ["messages", [obj([["role", "user"], ["content", "AUTH_PREFLIGHT"]])]],
          ]), [["Content-Type", "application/json"], ["Authorization", "Bearer " + env.SPLICE_PROBE_BEARER]]);
          if (!pyEq(get(preflight, "stop_reason"), "end_turn")
            || !iter(get(preflight, "content", [])).some((part) => pyEq(get(part, "text"), "AUTH_OK"))
            || counter(state, "auth-preflight") !== 1 || truthy((state.get("error") as string | undefined) ?? null)) {
            throw new ValueError("two-head auth preflight failed");
          }
          process.stdout.write("Two-head synthetic auth preflight: baseline passed\n");
          for (const [caseName, prompt] of CASES) {
            const row = await runCase(head, "gpt-6-astra", caseName, prompt);
            setKey(row, "backend_requests", int(counter(state, caseName)));
            rows.push(row);
            process.stdout.write(dumps(row) + "\n");
            if (!truthy(sub(row, "passed")) || counter(state, caseName) !== EXPECTED_ROUNDS[caseName] || truthy((state.get("error") as string | undefined) ?? null)) {
              mkdirSync(dirname(receipt), { recursive: true });
              writeFileSync(withSuffix(receipt, ".daemon.log"), readFileSync(join(root, "daemon-output.log")));
              throw new ValueError("mock bridge correctness failure");
            }
          }
        } finally {
          await stopDaemon(proc, log);
        }
        await runToggleBoots(root, artifact, server.serverPort, state);
      } finally {
        if (previous === undefined) delete process.env.SPLICE_PROBE_BEARER;
        else process.env.SPLICE_PROBE_BEARER = previous;
      }
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  } catch (error) {
    if (isOSError(error) || isValueError(error) || error instanceof SubprocessError) failure = (error as Error).message;
    throw error;
  } finally {
    server.shutdown();
    await server.serverClose();
    await Promise.race([loop, Bun.sleep(5000)]);
    mkdirSync(dirname(receipt), { recursive: true });
    writeFileSync(receipt, dumpsIndent(obj([
      ["kind", "code-mode-packaged-mock"], ["artifact_sha256", digest],
      ["failure", failure], ["mock_error", (state.get("error") as string | undefined) ?? null],
      ["auth_preflight_requests", int(counter(state, "auth-preflight"))],
      ["toggle_probe_requests", int(counter(state, "toggle-probe"))],
      ["toggle_states", (state.get("toggle_seen") as boolean[] | undefined) ?? []],
      ["runs", rows],
    ]), 2) + "\n");
  }
}

/** The one global the test suite replaced (mock.patch target in the original). */
export const seams = { requestJson };

// ---------------------------------------------------------------------------------------------
// MockTests
// ---------------------------------------------------------------------------------------------

const P = (v: unknown): PyValue => {
  if (v === null || v === undefined) return null;
  if (typeof v === "boolean" || typeof v === "string") return v;
  if (typeof v === "number") return int(v);
  if (Array.isArray(v)) return v.map(P);
  return obj(Object.entries(v as Record<string, unknown>).map(([k, x]) => [k, P(x)]));
};

export const tests: Tests = {
  test_guidance_keeps_one_original_developer_item_and_runner() {
    const callerTools = [{ type: "function", name: "Read" }];
    const body = P({ input: [
      { type: "additional_tools", role: "developer", tools: [...callerTools, { type: "custom", name: "splice_exec" }] },
      { role: "developer", content: CALLER_SYSTEM + "\n\n" + GUIDANCE },
      { role: "user", content: "synthetic" },
    ] });
    assertGuidance(body, CALLER_SYSTEM, true);
  },

  async test_disabled_guidance_is_exactly_the_caller_instruction_without_runner() {
    const body = P({ input: [
      { type: "additional_tools", role: "developer", tools: [{ type: "function", name: "Read" }] },
      { role: "developer", content: TOGGLE_SYSTEM },
      { role: "user", content: TOGGLE_PROMPT },
    ] });
    assertGuidance(body, TOGGLE_SYSTEM, false);
    (get(body, "input") as PyValue[]).push(P({ role: "developer", content: "duplicate" }));
    await check.raises((e) => e instanceof ValueError, () => assertGuidance(body, TOGGLE_SYSTEM, false), /duplicated/);
  },

  test_toggle_toml_has_true_false_and_omitted_states() {
    const directory = mkdtempSync(join(tmpdir(), "tmp"));
    try {
      for (const [index, [configured, expected]] of ([[null, null], [true, true], [false, false]] as const).entries()) {
        const current = join(directory, String(index));
        mkdirSync(current);
        const env = configure(current, 1, 2, 3, 4, configured);
        const quirks = (Bun.TOML.parse(readFileSync(env.SPLICE_CONFIG, "utf8")) as
          { providers: { code_mode: { quirks: Record<string, unknown> } } }).providers.code_mode.quirks;
        check.equal(expected, quirks.code_mode ?? null);
      }
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  },

  async test_toggle_probe_uses_normal_tools_system_and_session_header() {
    const captured: [number, string, string, PyValue, [string, string][]][] = [];
    const reply = async (port: number, method: string, path: string, body?: PyValue, headers?: [string, string][] | null) => {
      captured.push([port, method, path, body ?? null, headers ?? []]);
      return P({ stop_reason: "end_turn", content: [{ type: "text", text: TOGGLE_RESPONSE }] });
    };
    const old = seams.requestJson;
    seams.requestJson = reply as never;
    try {
      await runToggleProbe(1234, "synthetic-bearer");
    } finally {
      seams.requestJson = old;
    }
    const [, method, path, body, headers] = captured[0];
    check.equal(["POST", "/v1/messages"], [method, path]);
    check.equal(TOOLS, get(body, "tools"));
    check.equal(TOGGLE_SYSTEM, get(body, "system"));
    const header = (k: string) => headers.find(([name]) => name === k)?.[1];
    check.equal("Bearer synthetic-bearer", header("Authorization"));
    check.true(header("x-claude-code-session-id"));
  },
};

const PROG = "code_mode_mock.ts";
const USAGE = `usage: ${PROG} [-h] [--selftest] [--artifact ARTIFACT]
                         [--receipt RECEIPT]
`;
const HELP = `${USAGE}
Exercise the packaged code-mode bridge through an isolated daemon and loopback
upstream. No real credentials or vendor calls. Client tools use
code_mode_probe's in-memory workspace.

options:
  -h, --help           show this help message and exit
  --selftest
  --artifact ARTIFACT
  --receipt RECEIPT
`;

if (import.meta.main) {
  const a = argparse(process.argv.slice(2), [
    { flag: "--selftest", kind: "true" },
    { flag: "--artifact", kind: "str" },
    { flag: "--receipt", kind: "str" },
  ], PROG, USAGE, HELP);
  if (a.selftest) process.exit(await runUnittest("code_mode_mock", "MockTests", tests));
  else if (a.artifact && a.receipt) await run(a as { artifact: string; receipt: string });
  else argparseError(PROG, USAGE, "choose --selftest or both --artifact and --receipt");
}
