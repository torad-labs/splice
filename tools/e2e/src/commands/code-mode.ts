/**
 * `e2e code-mode <probe|mock|guidance|compare> [flags]` — THE CODE-MODE BRIDGE'S OWN HARNESSES,
 * in one module. Merged from checks/e2e/code_mode_probe.ts, code_mode_mock.ts,
 * code_mode_guidance.ts and code_mode_compare.ts (restructure PR 5); the four carried one another's
 * fixtures, workspaces and seams across four files and three dynamic imports, and nothing else ever
 * imported them.
 *
 *   probe     Bounded, opt-in code-mode A/B probe. Fake client tools never touch files or run
 *             commands. `--proxy-port` runs the budget proxy; the comparison arm points BOTH
 *             isolated heads at that loopback upstream, with WebSocket and request compression
 *             disabled. The proxy caps actual vendor requests, including discovery/retry rounds;
 *             comparison mode reads its counters between synthetic tasks. No credential, prompt,
 *             source, or tool-output content is written to the receipt.
 *   mock      Exercise the PACKAGED bridge through an isolated daemon and loopback upstream. No
 *             real credentials or vendor calls; client tools use the probe's in-memory workspace.
 *   guidance  The frozen synthetic repository and evidence oracles for the prompt-only A/B. It is
 *             data plus its oracles: `--selftest` is its only arm.
 *   compare   The explicitly approved bounded comparison in one disposable, isolated daemon. Uses
 *             an existing ChatGPT auth file without modifying it. THIS CONSUMES SUBSCRIPTION
 *             QUOTA: never add it to the default gate.
 *
 * `--selftest` on an arm runs that arm's bun test file and returns its status; the suites live in
 * tools/e2e/test/code-mode*.test.ts. No command here ever calls process.exit — the verb returns a
 * status to tools/e2e/index.ts, and a signalled child reports as a shell would (128+signum,
 * tools/gate/src/lib/status.ts).
 *
 * V4-145 (carried): converted from the code_mode_*.py harnesses. Adaptations, each forced by the
 * runtime:
 *
 *   SEAMS FOR mock.patch. The suites patched module globals (request_json, run_case,
 *   http.client.HTTPSConnection, subprocess.Popen, ThreadingHTTPServer, run_comparison,
 *   time.monotonic, Budget). ESM bindings are read-only, so those are reached through
 *   `probeSeams` / `mockSeams` / `compareSeams` and the tests swap entries on them, restoring them
 *   in `finally`. A caller that imported one BY NAME in Python holds the original function,
 *   exactly as it did there.
 *
 *   THE PROXY HANDLER IS ASYNC. do_POST awaits the upstream instead of blocking a thread; every
 *   budget mutation is synchronous between awaits, which is what the Budget lock guaranteed.
 *
 *   THE RECEIPT'S harness_sha256 names THIS module — the one file the three merged harnesses
 *   became.
 *
 *   shlex.split and ast.parse/ast.walk had no JS counterpart and are carried in the guidance
 *   section rather than approximated; pythonDefs() FAILS CLOSED on any construct outside the
 *   modelled subset, so a fixture edit that outgrows it is a loud error, never a silently wrong
 *   reference list.
 */
import { createHash } from "node:crypto";
import {
  chmodSync, closeSync, existsSync, fstatSync, mkdirSync, mkdtempSync, openSync, readFileSync, readSync, rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { basename, dirname, join, resolve } from "node:path";
import {
  dumps, dumpsIndent, fromJS, isPyObj, loads, loadsBytes, obj, type PyObj, type PyValue,
} from "../compat/python-json.ts";
import {
  ephemeralPorts, httpRequest, httpsConnection, threadingHTTPServer, type Methods, type PyServer, type Request,
  type UpstreamConnection,
} from "../compat/python-http.ts";
import {
  argparse, argparseError, at, AttributeError, BadZipFile, cpCompare, get, hashKey, isHTTPException, isKeyError,
  isOSError, isTypeError, isValueError, iter, KeyError, OSError, osError, popen, pyEq, pyIn, pyInt, pyName, pyRoundInt,
  pyRstrip, pySplitlines, setKey, StopIteration, sub, SubprocessError, TimeoutExpired, truthy, typeName, ValueError,
  type Proc,
} from "../compat/python-values.ts";
import { exitStatusOf } from "../../../gate/src/lib/status.ts";
import { findRepoRoot } from "../../../gate/src/lib/repo.ts";

// =============================================================================================
// PROBE — the budget proxy, the synthetic workspace and the bounded A/B comparison.
// =============================================================================================

export const MAX_REQUESTS = 64;
export const INPUT_BUDGET = 400_000;
export const OUTPUT_BUDGET = 32_000;
export const MAX_BODY = 1_048_576;

const int = (n: number | bigint): PyValue => ({ __pyNum: String(n), isFloat: false });
const isExactInt = (v: PyValue): v is { __pyNum: string; isFloat: false } =>
  typeof v === "object" && v !== null && "__pyNum" in v && !(v as { isFloat: boolean }).isFloat;

export class Budget {
  requests = 0;
  // Token counts come off the wire as Python ints; BigInt keeps them exact past 2^53.
  input_tokens = 0n;
  output_tokens = 0n;
  cached_tokens = 0n;
  request_bytes = 0;
  response_bytes = 0;
  schema_bytes = 0;
  search_calls = 0;
  code_calls = 0;
  direct_calls = 0;
  code_tool_requests = 0;
  guidance_requests = 0;
  error: string | null = null;

  snapshot(): PyObj {
    return obj([
      ["requests", int(this.requests)], ["input_tokens", int(this.input_tokens)], ["output_tokens", int(this.output_tokens)],
      ["cached_tokens", int(this.cached_tokens)], ["request_bytes", int(this.request_bytes)],
      ["response_bytes", int(this.response_bytes)], ["schema_bytes", int(this.schema_bytes)],
      ["search_calls", int(this.search_calls)], ["code_calls", int(this.code_calls)], ["direct_calls", int(this.direct_calls)],
      ["code_tool_requests", int(this.code_tool_requests)], ["guidance_requests", int(this.guidance_requests)],
      ["error", this.error],
    ]);
  }

  reserve(size: number): boolean {
    if (this.error || this.requests >= MAX_REQUESTS || this.input_tokens >= BigInt(INPUT_BUDGET)
      || this.output_tokens >= BigInt(OUTPUT_BUDGET)) {
      return false;
    }
    this.requests += 1;
    this.request_bytes += size;
    return true;
  }

  recordShape(payload: PyValue): void {
    const declarations: PyValue[] = [...iter(get(payload, "tools", []))];
    for (const item of iter(get(payload, "input", []))) {
      if (isPyObj(item) && pyIn(get(item, "type"), ["additional_tools", "tool_search_output"])) {
        declarations.push(...iter(get(item, "tools", [])));
      }
    }
    this.schema_bytes += dumps(declarations, ",", ":").length;
    this.code_tool_requests += declarations.some((tool) => pyEq(get(tool, "name"), "splice_exec")) ? 1 : 0;
    this.guidance_requests += dumps(payload).includes("<code_mode_orchestration>") ? 1 : 0;
  }

  recordItem(item: PyValue, index: PyValue, seen: Set<string>): void {
    const kind = get(item, "type");
    if (!pyIn(kind, ["tool_search_call", "custom_tool_call", "function_call"])) return;
    // Added/done/terminal representations of the same item count once. The
    // registry belongs to one response, not the entire multi-request probe.
    const keys = new Set<string>();
    for (const key of ["id", "call_id"]) {
      if (truthy(get(item, key))) keys.add(JSON.stringify([kind, key, hashKey(sub(item, key))]));
    }
    if (index !== null) keys.add(JSON.stringify([kind, "index", hashKey(index)]));
    const duplicate = [...keys].some((k) => seen.has(k));
    for (const k of keys) seen.add(k);
    if (duplicate) return;
    this.search_calls += kind === "tool_search_call" ? 1 : 0;
    this.code_calls += kind === "custom_tool_call" ? 1 : 0;
    this.direct_calls += kind === "function_call" ? 1 : 0;
  }

  finish(usage: PyValue, error: string | null = null): void {
    if (!isPyObj(usage) || ["input_tokens", "output_tokens"].some((key) => {
      const v = get(usage, key);
      return !isExactInt(v) || BigInt(v.__pyNum) < 0n;
    })) {
      this.error = error || "vendor response omitted usage; stopping rather than guessing spend";
      return;
    }
    const input = BigInt((sub(usage, "input_tokens") as { __pyNum: string }).__pyNum);
    this.input_tokens += input;
    this.output_tokens += BigInt((sub(usage, "output_tokens") as { __pyNum: string }).__pyNum);
    const details = get(usage, "input_tokens_details", obj([]));
    const cached = isPyObj(details) ? get(details, "cached_tokens", int(0)) : null;
    if (!isExactInt(cached as PyValue) || !(BigInt((cached as { __pyNum: string }).__pyNum) >= 0n
      && BigInt((cached as { __pyNum: string }).__pyNum) <= input)) {
      this.error = "vendor response contained invalid cache usage; stopping";
      return;
    }
    this.cached_tokens += BigInt((cached as { __pyNum: string }).__pyNum);
    this.error = error || this.error;
  }
}

/** The request-handler slice the proxy reads: http.server's, or a test's fake. */
export type ProxyRequest = Pick<Request, "path" | "headers" | "rfile" | "wfile" | "sendResponse" | "sendHeader" | "endHeaders" | "sendError"> & {
  closeConnection: boolean;
};

const bytesStrip = (b: Uint8Array): Uint8Array => {
  const ws = (c: number) => c === 0x20 || (c >= 0x09 && c <= 0x0d);
  let a = 0;
  let z = b.length;
  while (a < z && ws(b[a]!)) a++;
  while (z > a && ws(b[z - 1]!)) z--;
  return b.subarray(a, z);
};
const startsWith = (b: Uint8Array, prefix: string) =>
  b.length >= prefix.length && [...prefix].every((ch, i) => b[i] === ch.charCodeAt(0));

export function proxyHandler(budget: Budget): Methods & { GET(h: ProxyRequest): void; POST(h: ProxyRequest): Promise<void> } {
  return {
    // Request and credential details must not enter logs: http.server's log_message is silenced,
    // and python-http never logs a request.
    GET(h: ProxyRequest): void {
      const payload = Buffer.from(dumps(budget.snapshot()), "utf8");
      h.sendResponse(h.path === "/metrics" ? 200 : 404);
      h.sendHeader("Content-Type", "application/json");
      h.sendHeader("Content-Length", String(payload.length));
      h.endHeaders();
      h.wfile.write(payload);
    },

    async POST(h: ProxyRequest): Promise<void> {
      const size = pyInt(h.headers.get("Content-Length", "0") as string);
      const bearer = h.headers.get("Authorization", "") as string;
      const localBearer = process.env.SPLICE_PROBE_BEARER ?? "";
      if (h.path !== "/responses" || !(0 < size && size <= MAX_BODY)
        || truthy(h.headers.get("Content-Encoding")) || !bearer.startsWith("Bearer ")
        || (localBearer && bearer === "Bearer " + localBearer)) {
        h.sendError(400, "invalid probe request or credential boundary");
        return;
      }
      const body = await h.rfile.read(size);
      let payload: PyValue;
      try {
        payload = loadsBytes(body);
        if (!isPyObj(payload) || !pyEq(get(payload, "model"), "gpt-6-astra")) {
          throw new ValueError("probe permits only the approved Astra model");
        }
        const reasoning = get(payload, "reasoning");
        if (!isPyObj(reasoning) || !pyEq(get(reasoning, "effort"), "high")) {
          throw new ValueError("probe permits only the approved high effort");
        }
      } catch (e) {
        if (!(isValueError(e) || isTypeError(e))) throw e;
        h.sendError(400, "invalid probe model or JSON");
        return;
      }
      if (!budget.reserve(body.length)) {
        h.sendError(429, "probe budget exhausted or halted");
        return;
      }
      budget.recordShape(payload);
      const upstream: UpstreamConnection = probeSeams.httpsConnection("chatgpt.com", 120);
      let usage: PyValue = null;
      let error: string | null = null;
      let accounted = false;
      const seenItems = new Set<string>();
      try {
        const excluded = new Set(["host", "connection", "content-length", "transfer-encoding", "accept-encoding"]);
        const headers: [string, string][] = [];
        for (const [k, v] of h.headers.items()) {
          if (excluded.has(k.toLowerCase())) continue;
          const hit = headers.find(([name]) => name === k);
          if (hit) hit[1] = v;
          else headers.push([k, v]);
        }
        headers.push(["Accept-Encoding", "identity"]);
        upstream.request("POST", "/backend-api/codex/responses", body, headers);
        const response = await upstream.getresponse();
        if (response.status !== 200) error = `vendor HTTP ${response.status}`;
        h.sendResponse(response.status);
        h.sendHeader("Content-Type", response.getheader("Content-Type", "application/json") as string);
        h.sendHeader("Connection", "close");
        h.endHeaders();
        let pending: Uint8Array = new Uint8Array(0);
        let forwarding = true;
        for (;;) {
          const chunk = await response.read1(65536);
          if (chunk.length === 0) break;
          budget.response_bytes += chunk.length;
          pending = Buffer.concat([pending, chunk]);
          for (let nl = pending.indexOf(0x0a); nl >= 0; nl = pending.indexOf(0x0a)) {
            const line = pending.subarray(0, nl);
            pending = pending.subarray(nl + 1);
            if (!startsWith(line, "data: ") || Buffer.from(bytesStrip(line.subarray(6))).toString("latin1") === "[DONE]") continue;
            let event: PyValue;
            try {
              event = loadsBytes(line.subarray(6));
            } catch (e) {
              if (isValueError(e)) continue;
              throw e;
            }
            const type = get(event, "type");
            if (pyIn(type, ["response.output_item.added", "response.output_item.done"])) {
              budget.recordItem(get(event, "item", obj([])), get(event, "output_index"), seenItems);
            }
            if (pyIn(type, ["response.completed", "response.incomplete", "response.failed"])) {
              if (accounted) throw new ValueError("duplicate terminal response");
              const terminal = get(event, "response", obj([]));
              usage = get(terminal, "usage");
              iter(get(terminal, "output", [])).forEach((item, index) => budget.recordItem(item, int(index), seenItems));
              if (!pyEq(sub(event, "type"), "response.completed")) error = "vendor response did not complete";
              // A forwarded terminal can immediately trigger another vendor request.
              budget.finish(usage, error);
              accounted = true;
            }
          }
          if (pending.length > MAX_BODY) throw new ValueError("oversized upstream frame");
          if (forwarding) {
            try {
              h.wfile.write(chunk);
              h.wfile.flush();
            } catch (e) {
              if (!isOSError(e)) throw e;
              forwarding = false;
              error = "client disconnected; upstream drained for usage";
            }
          }
        }
      } catch (exc) {
        if (!(isOSError(exc) || isValueError(exc) || isHTTPException(exc))) throw exc;
        error = pyName(exc);
      } finally {
        h.closeConnection = true;
        upstream.close();
        if (!accounted) budget.finish(usage, error);
        else if (error) budget.error = error;
      }
    },
  };
}

function definition(name: string, description: string, properties: [string, PyValue][]): PyValue {
  return obj([["name", name], ["description", description], ["input_schema", obj([
    ["type", "object"], ["properties", obj(properties)], ["required", properties.map(([k]) => k)],
    ["additionalProperties", false],
  ])]]);
}
const str = obj([["type", "string"]]);

export const PROBE_TOOLS: PyValue[] = [
  definition("Read", "Read a named file in the synthetic workspace.", [["file_path", str]]),
  definition("Write", "Replace one synthetic workspace file.", [["file_path", str], ["content", str]]),
  definition("Agent", "Start a background arithmetic job. Its result arrives in a completion notification; do not poll.", [
    ["prompt", str],
  ]),
  definition("mcp__release__lookup", "Look up the next release version.", []),
  ...Array.from({ length: 8 }, (_, i) =>
    definition(`mcp__unrelated__operation_${i}`, "An unrelated integration, not needed by these tasks.", [])),
];

export const PROBE_CASES: [string, string][] = [
  ["lookup-edit", "Read settings.json, change timeout from 10 to 20 without losing other fields, and report UPDATED."],
  ["independent-reads", "Read a.txt, b.txt, and c.txt and report the sum of the three numbers."],
  ["optional-discovery", "Use the release lookup integration to obtain the next release version. Report that version."],
  ["background-result", "Start an Agent to calculate 7 times 8, then report the answer from its completion notification. Do not poll."],
];

/** json.dumps(v, sort_keys=True, ...) as an equality signature. */
function sortedDump(v: PyValue): string {
  const sortKeys = (x: PyValue): PyValue => {
    if (Array.isArray(x)) return x.map(sortKeys);
    if (isPyObj(x)) return obj([...x.__pyObj].sort(([a], [b]) => cpCompare(a, b)).map(([k, y]) => [k, sortKeys(y)]));
    return x;
  };
  return dumps(sortKeys(v), ",", ":");
}
/** set(args) == {names...}: iterating args as Python would, hashing every member. */
function setEquals(args: PyValue, names: string[]): boolean {
  const members = new Set(iter(args).map(hashKey));
  return members.size === names.length && names.every((n) => members.has(hashKey(n)));
}

/** A workspace the probe's tools read: files keyed by the hash of the name they were written under. */
export class ProbeWorkspace {
  files = new Map<string, [PyValue, PyValue]>([
    ["settings.json", '{"timeout":10,"enabled":true}'], ["a.txt", "21"], ["b.txt", "34"], ["c.txt", "55"],
  ].map(([k, v]) => [hashKey(k as string), [k, v] as [PyValue, PyValue]]));
  calls: PyValue[] = [];
  private argumentsSeen = new Set<string>();
  repeated_calls = 0;
  private readPaths = new Set<string>();
  notification: string | null = null;

  execute(tool: { name: PyValue; input: PyValue }): PyValue {
    const name = tool.name;
    const args = tool.input;
    this.calls.push(name);
    const signature = sortedDump([name, args]);
    this.repeated_calls += this.argumentsSeen.has(signature) ? 1 : 0;
    this.argumentsSeen.add(signature);
    if (pyEq(name, "Read") && setEquals(args, ["file_path"])) {
      const path = sub(args, "file_path");
      this.readPaths.add(hashKey(path));
      const hit = this.files.get(hashKey(path));
      if (hit === undefined) throw new KeyError(typeof path === "string" ? `'${path}'` : dumps(path));
      return hit[1];
    }
    if (pyEq(name, "Write") && setEquals(args, ["file_path", "content"])) {
      const path = sub(args, "file_path");
      const key = hashKey(path);
      if (!this.files.has(key)) throw new ValueError("write outside synthetic workspace");
      (this.files.get(key) as [PyValue, PyValue])[1] = sub(args, "content");
      return "Updated synthetic file.";
    }
    if (pyEq(name, "mcp__release__lookup") && !truthy(args)) return '{"version":"0.4.0"}';
    if (pyEq(name, "Agent") && setEquals(args, ["prompt"]) && this.calls.filter((c) => pyEq(c, "Agent")).length === 1) {
      this.notification = "Background job probe-job completed: 7 times 8 = 56.";
      return "Background job probe-job queued; wait for its completion notification, not polling.";
    }
    throw new ValueError("unexpected tool or arguments in synthetic task");
  }

  metrics(): PyObj {
    return obj([]);
  }

  correct(caseName: string, text: string): boolean {
    const called = (n: string) => pyIn(n, this.calls);
    if (caseName === "lookup-edit") {
      const settings = (this.files.get(hashKey("settings.json")) as [PyValue, PyValue])[1];
      if (typeof settings !== "string") {
        throw new TypeError(`the JSON object must be str, bytes or bytearray, not ${typeName(settings)}`);
      }
      return pyEq(loadsBytes(Buffer.from(settings, "utf8")), obj([["timeout", int(20)], ["enabled", true]]))
        && called("Read") && called("Write") && text.includes("UPDATED");
    }
    if (caseName === "independent-reads") {
      // re.search(r"\b110\b", text), with Python's Unicode \w on both sides of the boundary.
      return ["a.txt", "b.txt", "c.txt"].every((p) => this.readPaths.has(hashKey(p)))
        && /(?<![\p{L}\p{N}_])110(?![\p{L}\p{N}_])/u.test(text);
    }
    if (caseName === "optional-discovery") return called("mcp__release__lookup") && text.includes("0.4.0");
    return this.calls.filter((c) => pyEq(c, "Agent")).length === 1 && text.includes("56");
  }
}

/** request_json: one HTTP/1.1 exchange with the loopback daemon, JSON in and out. */
export async function requestJson(port: number, method: string, path: string, body: PyValue | undefined = undefined,
  headers: [string, string][] | null = null): Promise<PyValue> {
  const payload = body !== undefined ? Buffer.from(dumps(body), "utf8") : null;
  const conn = await httpRequest("127.0.0.1", port, method, path, payload, headers ?? [], 180);
  try {
    const data = await conn.response.read(MAX_BODY + 1);
    if (data.length > MAX_BODY) throw new ValueError("probe response exceeded byte limit");
    if (conn.response.status !== 200) throw new ValueError(`probe HTTP ${conn.response.status}`);
    return loadsBytes(data);
  } finally {
    conn.close();
  }
}

/** A prompt-only A/B scenario (the guidance section below). */
export interface Scenario {
  Workspace: new () => { execute(t: { name: PyValue; input: PyValue }): PyValue; calls: PyValue[]; repeated_calls: number;
    metrics(): PyObj; correct(c: string, text: string): boolean };
  SYSTEM: string;
  TOOLS: PyValue[];
  CASES: [string, string][];
  GUIDANCE: string;
  GUIDANCE_PATH: string;
  FILES: [string, string][];
}

export async function runCase(port: number, model: string, caseName: string, prompt: string,
  scenario: Scenario | null = null, instructionSuffix = ""): Promise<PyObj> {
  const workspace = scenario ? new scenario.Workspace() : new ProbeWorkspace();
  let system = scenario ? scenario.SYSTEM : (
    "Use tools to verify the requested facts. Keep the final answer concise. " +
    "If a script tool is available, it may batch related tool operations. " +
    "Only synthetic tools exist; do not invent results or poll background jobs."
  );
  if (instructionSuffix) system += "\n\n" + instructionSuffix;
  const messages: PyValue[] = [obj([["role", "user"], ["content", prompt]])];
  const headers: [string, string][] = [["Content-Type", "application/json"], ["x-claude-code-session-id", crypto.randomUUID()]];
  const token = process.env.SPLICE_PROBE_BEARER;
  if (token) headers.push(["Authorization", "Bearer " + token]);
  const start = performance.now() / 1000;
  let final = "";
  let turn = 0;
  let error: string | null = null;
  let failedTools = 0;
  let clientBytes = 0;
  let bridgeCallbacks = 0;
  let bridgeBatches = 0;
  let maxBridgeBatch = 0;
  const seenCallIds = new Set<string>();
  let passed: boolean;
  try {
    for (turn = 0; turn < 12; turn++) { // Local script resumptions do not each invoke the model.
      const payload = obj([
        ["model", model], ["stream", false], ["max_tokens", int(2048)],
        ["system", system],
        ["output_config", obj([["effort", "high"]])], ["tools", scenario ? scenario.TOOLS : PROBE_TOOLS],
        ["messages", messages],
      ]);
      clientBytes += Buffer.byteLength(dumps(payload), "utf8");
      const response = await probeSeams.requestJson(port, "POST", "/v1/messages", payload, headers);
      const content = get(response, "content", []);
      messages.push(obj([["role", "assistant"], ["content", content]]));
      const calls = iter(content).filter((b) => pyEq(get(b, "type"), "tool_use"));
      if (calls.length === 0) {
        const parts = iter(content).filter((b) => pyEq(get(b, "type"), "text")).map((b) => get(b, "text", ""));
        parts.forEach((p, i) => {
          if (typeof p !== "string") throw new TypeError(`sequence item ${i}: expected str instance, ${typeName(p)} found`);
        });
        final = parts.join("");
        if (!pyIn(get(response, "stop_reason"), ["end_turn", "stop_sequence"])) throw new ValueError("unexpected terminal reason");
        break;
      }
      let bridgeCount = 0;
      for (const t of calls) {
        const id = sub(t, "id");
        if (typeof id !== "string") throw new AttributeError(`'${typeName(id)}' object has no attribute 'startswith'`);
        bridgeCount += id.startsWith("toolu_splice_") ? 1 : 0;
      }
      bridgeCallbacks += bridgeCount;
      bridgeBatches += bridgeCount ? 1 : 0;
      maxBridgeBatch = Math.max(maxBridgeBatch, bridgeCount);
      const results: PyValue[] = [];
      for (const t of calls) {
        let output: PyValue;
        try {
          const key = hashKey(sub(t, "id"));
          if (seenCallIds.has(key)) throw new ValueError("duplicate callback identity");
          seenCallIds.add(key);
          output = workspace.execute({ name: sub(t, "name"), input: get(t, "input", obj([])) });
        } catch (e) {
          if (isKeyError(e) || isTypeError(e) || isValueError(e)) failedTools += 1;
          throw e;
        }
        results.push(obj([["type", "tool_result"], ["tool_use_id", sub(t, "id")], ["content", output]]));
      }
      const notification = (workspace as { notification?: string | null }).notification;
      if (notification) {
        results.push(obj([["type", "text"], ["text", notification]]));
        (workspace as unknown as { notification: string | null }).notification = null;
      }
      messages.push(obj([["role", "user"], ["content", results]]));
    }
    if (turn === 12) turn = 11; // range(12) leaves the loop variable at its last value
    passed = workspace.correct(caseName, final);
  } catch (exc) {
    if (!(isOSError(exc) || isValueError(exc) || isKeyError(exc) || isTypeError(exc) || isHTTPException(exc))) throw exc;
    // Only the exception class enters the receipt; never vendor output or fixture content.
    error = pyName(exc);
    passed = false;
  }
  const row = obj([
    ["case", caseName], ["passed", passed], ["error", error], ["client_requests", int(turn + 1)],
    ["tool_calls", int(workspace.calls.length)], ["repeated_tool_calls", int(workspace.repeated_calls)],
    ["failed_tool_calls", int(failedTools)], ["client_request_bytes", int(clientBytes)],
    ["bridge_callbacks", int(bridgeCallbacks)], ["bridge_callback_batches", int(bridgeBatches)],
    ["max_bridge_batch", int(maxBridgeBatch)],
    ["elapsed_ms", int(pyRoundInt((performance.now() / 1000 - start) * 1000))],
  ]);
  if (scenario) for (const [k, v] of workspace.metrics().__pyObj) setKey(row, k, v);
  return row;
}

// ---------------------------------------------------------------------------------------------
// zipfile: the namelist() the prompt-only guard reads, and (for the tests) a stored-entry writer.
// ---------------------------------------------------------------------------------------------

/** ZipFile(path).namelist(): the central directory's names, zip64 included. Raw bytes are compared,
 *  which is exact for the ASCII name the guard looks for under both cp437 and UTF-8 decoding. */
export function zipNames(path: string): string[] {
  let fd: number;
  try {
    fd = openSync(path, "r");
  } catch (e) {
    throw (isOSError(e) ? e : new OSError(String(e)));
  }
  try {
    const size = fstatSync(fd).size;
    const tailLen = Math.min(size, 65557);
    const tail = Buffer.alloc(tailLen);
    readSync(fd, tail, 0, tailLen, size - tailLen);
    const eocd = tail.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
    if (eocd < 0) throw new BadZipFile("File is not a zip file");
    let count = tail.readUInt16LE(eocd + 10);
    let cdSize = tail.readUInt32LE(eocd + 12);
    let cdOffset = tail.readUInt32LE(eocd + 16);
    const loc = eocd - 20;
    if (loc >= 0 && tail.readUInt32LE(loc) === 0x07064b50) {
      const recOff = Number(tail.readBigUInt64LE(loc + 8));
      const rec = Buffer.alloc(56);
      readSync(fd, rec, 0, 56, recOff);
      if (rec.readUInt32LE(0) !== 0x06064b50) throw new BadZipFile("Corrupt zip64 end of central directory locator");
      count = Number(rec.readBigUInt64LE(32));
      cdSize = Number(rec.readBigUInt64LE(40));
      cdOffset = Number(rec.readBigUInt64LE(48));
    }
    const cd = Buffer.alloc(cdSize);
    readSync(fd, cd, 0, cdSize, cdOffset);
    const names: string[] = [];
    let p = 0;
    for (let i = 0; i < count; i++) {
      if (cd.readUInt32LE(p) !== 0x02014b50) throw new BadZipFile("Bad magic number for central directory");
      const nameLen = cd.readUInt16LE(p + 28);
      const extraLen = cd.readUInt16LE(p + 30);
      const commentLen = cd.readUInt16LE(p + 32);
      names.push(cd.subarray(p + 46, p + 46 + nameLen).toString("latin1"));
      p += 46 + nameLen + extraLen + commentLen;
    }
    return names;
  } finally {
    closeSync(fd);
  }
}

export interface ComparisonArgs {
  artifact: string;
  receipt: string;
  model: string;
  baseline_port: number;
  code_mode_port: number;
  metrics_port: number;
  prompt_guidance?: boolean;
}

export function validatePromptExperiment(args: { artifact: string; prompt_guidance?: boolean }): void {
  if (!args.prompt_guidance) return;
  if (zipNames(args.artifact).includes("splice/provider/codex/code-mode-orchestration.txt")) {
    throw new ValueError("prompt-only A/B requires a pre-injection artifact; this build adds guidance automatically");
  }
}

const sha256 = (data: Uint8Array | string) => createHash("sha256").update(data).digest("hex");
/** json.dumps(v, sort_keys=True) with the default separators: the fixture/catalog hashes. */
function sortKeysDump(v: PyValue): string {
  const sortKeys = (x: PyValue): PyValue => {
    if (Array.isArray(x)) return x.map(sortKeys);
    if (isPyObj(x)) return obj([...x.__pyObj].sort(([a], [b]) => cpCompare(a, b)).map(([k, y]) => [k, sortKeys(y)]));
    return x;
  };
  return dumps(sortKeys(v));
}
/** Path(path).read_bytes(), raising the OSError subclass Python would. */
const readBytes = (path: string): Buffer => {
  try {
    return readFileSync(path);
  } catch (e) {
    throw osError(e, path);
  }
};

/** after[key] - before[key]: ints subtract exactly, a float makes a float, anything else is a
 *  TypeError as `-` is in Python. */
function pySubtract(a: PyValue, b: PyValue): PyValue {
  const numeric = (v: PyValue) => typeof v === "boolean" || (typeof v === "object" && v !== null && "__pyNum" in v);
  if (!numeric(a) || !numeric(b)) {
    throw new TypeError(`unsupported operand type(s) for -: '${typeName(a)}' and '${typeName(b)}'`);
  }
  const asInt = (v: PyValue) => (typeof v === "boolean" ? (v ? 1n : 0n) : BigInt((v as { __pyNum: string }).__pyNum));
  const isF = (v: PyValue) => typeof v === "object" && v !== null && (v as { isFloat: boolean }).isFloat;
  if (!isF(a) && !isF(b)) return int(asInt(a) - asInt(b));
  const f = (v: PyValue) => (typeof v === "boolean" ? (v ? 1 : 0) : Number((v as { __pyNum: string }).__pyNum));
  return { __pyNum: String(f(a) - f(b)), isFloat: true };
}

export async function runComparison(args: ComparisonArgs): Promise<void> {
  validatePromptExperiment(args);
  const rows: PyObj[] = [];
  const receipt = args.receipt;
  if (existsSync(receipt)) throw new ValueError("refusing to overwrite an existing receipt");
  const digest = sha256(readBytes(args.artifact));
  let scenario: Scenario | null = null;
  let experiment: PyObj = obj([["kind", "bridge-on-off"]]);
  if (args.prompt_guidance) {
    scenario = GUIDANCE_SCENARIO;
    experiment = obj([
      ["kind", "code-mode-prompt-guidance"], ["base_system", scenario.SYSTEM],
      ["appended_section", scenario.GUIDANCE],
      ["guidance_sha256", sha256(readFileSync(scenario.GUIDANCE_PATH))],
      ["fixture_sha256", sha256(sortKeysDump(obj(scenario.FILES)))],
      ["catalog_sha256", sha256(sortKeysDump(scenario.TOOLS))],
    ]);
  }
  // The probe, compare and guidance harnesses are ONE module since the restructure, so the receipt
  // names that module: the hash still identifies the exact harness bytes that produced the row.
  setKey(experiment, "harness_sha256", obj([[basename(import.meta.path), sha256(readFileSync(import.meta.path))]]));
  let failure: string | null = null;
  let accounting: PyValue = null;
  try {
    for (let repetition = 0; repetition < 2; repetition++) {
      for (const [caseName, prompt] of scenario ? scenario.CASES : PROBE_CASES) {
        // Alternate ordering to reduce a consistent warm-cache/order advantage.
        const variants: [string, number][] = scenario
          ? [["existing", args.baseline_port], ["guided", args.code_mode_port]]
          : [["baseline", args.baseline_port], ["code_mode", args.code_mode_port]];
        for (const [variant, port] of repetition === 0 ? variants : [...variants].reverse()) {
          const before = await probeSeams.requestJson(args.metrics_port, "GET", "/metrics");
          accounting = before;
          if (truthy(sub(before, "error")) || num(sub(before, "requests")) >= MAX_REQUESTS
            || num(sub(before, "input_tokens")) >= INPUT_BUDGET || num(sub(before, "output_tokens")) >= OUTPUT_BUDGET) {
            throw new ValueError("proxy budget halted");
          }
          const row = await probeSeams.runCase(port, args.model, caseName, prompt, scenario,
            scenario && variant === "guided" ? scenario.GUIDANCE : "");
          setKey(row, "variant", variant);
          setKey(row, "repetition", int(repetition + 1));
          setKey(row, "accounting_complete", false);
          rows.push(row);
          const after = await probeSeams.requestJson(args.metrics_port, "GET", "/metrics");
          accounting = after;
          for (const key of iter(before)) {
            if (key !== "error") setKey(row, key as string, pySubtract(sub(after, key as string), sub(before, key as string)));
          }
          setKey(row, "accounting_complete", !truthy(sub(after, "error")));
          process.stdout.write(dumps(row) + "\n");
          if (!truthy(sub(row, "passed")) || truthy(sub(after, "error"))) {
            throw new ValueError("correctness failure or upstream accounting failure; comparison stopped");
          }
        }
      }
    }
  } catch (exc) {
    if (isOSError(exc) || isValueError(exc) || isKeyError(exc) || isTypeError(exc) || isHTTPException(exc)) failure = pyName(exc);
    throw exc;
  } finally {
    mkdirSync(dirname(receipt), { recursive: true });
    writeFileSync(receipt, dumpsIndent(obj([
      ["artifact_sha256", digest], ["model", args.model], ["experiment", experiment],
      ["planned_runs", int(16)], ["completed_runs", int(rows.length)],
      ["failure", failure], ["last_observed_accounting", accounting],
      ["cache_condition", "uncontrolled; observed cached tokens recorded per run"],
      ["budgets", obj([["requests", int(MAX_REQUESTS)], ["input_tokens", int(INPUT_BUDGET)], ["output_tokens", int(OUTPUT_BUDGET)]])],
      ["runs", rows],
    ]), 2) + "\n");
  }
  process.stdout.write(dumps(obj([
    ["artifact_sha256", digest], ["completed_runs", int(rows.length)], ["passed", rows.every((r) => truthy(sub(r, "passed")))],
  ])) + "\n");
}
/** A numeric wire value for a budget comparison; a non-number compares as Python would refuse. */
function num(v: PyValue): number {
  if (typeof v === "boolean") return v ? 1 : 0;
  if (typeof v === "object" && v !== null && "__pyNum" in v) return Number(v.__pyNum);
  throw new TypeError(`'>=' not supported between instances of '${typeName(v)}' and 'int'`);
}

/** The globals the test suite replaces (mock.patch targets in the original). */
export const probeSeams = {
  requestJson,
  runCase,
  httpsConnection: httpsConnection as (host: string, timeoutS: number) => UpstreamConnection,
};

// =============================================================================================
// GUIDANCE — the frozen synthetic repository and its evidence oracles (the prompt-only A/B).
// =============================================================================================

export const GUIDANCE_SYSTEM = `Investigate the supplied synthetic repository using tools to establish the requested facts.
The tool implementations are read-only fixtures. Do not edit files or invent evidence.
Return only the requested JSON object, without markdown fences. Stop once sufficient evidence exists.`;

// The resource the packaged daemon injects. It is read from the REPOSITORY ROOT, found by walking
// up to `.git` rather than counting `..` segments, so moving this harness never silently reads a
// different file (restructure PR 5 moved it two directories deeper).
export const GUIDANCE_PATH = join(
  findRepoRoot(import.meta.dir),
  "integrations/providers/codex/src/main/resources",
  "splice/provider/codex/code-mode-orchestration.txt",
);
// read_text() decodes UTF-8 with universal newlines.
export const GUIDANCE = pyRstrip(
  new TextDecoder("utf-8", { fatal: true }).decode(readFileSync(GUIDANCE_PATH)).replaceAll("\r\n", "\n").replaceAll("\r", "\n"),
);

function tool(name: string, description: string, properties: [string, PyValue][]): PyValue {
  return obj([
    ["name", name],
    ["description", description],
    ["input_schema", obj([
      ["type", "object"],
      ["properties", obj(properties)],
      ["required", properties.map(([k]) => k)],
      ["additionalProperties", false],
    ])],
  ]);
}
const gstr = obj([["type", "string"]]);

export const GUIDANCE_TOOLS: PyValue[] = [
  tool("Read", "Read one complete synthetic fixture file by exact relative path.", [["file_path", gstr]]),
  tool("Grep", "Literal text search. path is a fixture path/prefix, or '.' for all files. Returns path:line:text.", [
    ["pattern", gstr], ["path", gstr],
  ]),
  tool("LSP", "Inspect Python definitions or call references across the fixture workspace. " +
    "findReferences returns caller function names and source locations, excluding comments/strings. " +
    "file_path is the defining Python file; symbol is empty for documentSymbol.", [
    ["operation", obj([["type", "string"], ["enum", ["documentSymbol", "findReferences"]]])],
    ["file_path", gstr], ["symbol", gstr],
  ]),
  tool("Bash", "Read-only simulated fixture commands only: find . -type f; cat PATH; grep -n LITERAL PATH. " +
    "No command execution, pipes, or multiple commands; use the documented forms exactly.", [["command", gstr]]),
  tool("mcp__calendar__list", "Unrelated synthetic calendar integration.", []),
  tool("mcp__deploy__status", "Unrelated synthetic deployment integration.", []),
  tool("mcp__tickets__search", "Unrelated synthetic ticket integration.", []),
];

export const FILES: [string, string][] = [
  ["config/settings.toml", "[service]\ntimeout_seconds = 15\nretries = 2\n"],
  ["config/worker.toml", "[worker]\nconcurrency = 4\n"],
  ["config/cache.toml", "[cache]\nttl_seconds = 60\nenabled = false\n"],
  ["app/config.py", 'def load_timeout(config):\n    return config["service"]["timeout_seconds"]\n'],
  ["app/limits.py", `def build_client(timeout_seconds):
    return {"timeout": timeout_seconds}


def normalize_timeout(seconds):
    return min(max(seconds, 0), 60)


# The text build_client in this comment is not a caller.
DISPLAY_NAME = "build_client"
`],
  ["app/service.py", `from app.config import load_timeout
from app.limits import build_client


def start(config):
    return build_client(load_timeout(config))
`],
  ["app/worker.py", `from app import limits


def worker_client():
    return limits.build_client(30)
`],
  ["docs/notes.txt", "The words build_client() appear here, but this is documentation only.\n"],
  ["spec/timeouts.md", "# Request timeouts\n\nA request timeout of zero is invalid; accept integers 1 through 60 seconds.\n"],
  ["tests/test_limits.py", `from app.limits import normalize_timeout


def test_zero_is_currently_accepted():
    assert normalize_timeout(0) == 0
`],
];

export const GUIDANCE_CASES: [string, string][] = [
  ["configuration-audit", "Audit config/settings.toml, config/worker.toml, and config/cache.toml. " +
    "Report service timeout_seconds, worker concurrency, and cache ttl_seconds. " +
    'Return JSON with numeric fields "timeout_seconds", "worker_concurrency", "cache_ttl_seconds", ' +
    'and "citations": an array of source references in path:line format.'],
  ["function-callers", "Find every actual Python caller of build_client, defined in app/limits.py. " +
    "Exclude definitions, imports, comments, and string decoys. " +
    'Return JSON with "callers": an array of "path:function" strings, ' +
    'and "citations": an array of call-site references in path:line format.'],
  ["config-consumer-trace", "Trace the configured service timeout from config/settings.toml through " +
    "app/config.py to app/service.py and identify its sink function. " +
    'Return JSON with "setting" (section.key), "reader" (path:function), ' +
    '"consumer" (path:function), "sink" (dotted module.function), ' +
    'and "citations": an array of source references in path:line format.'],
  ["timeout-boundary-bug", "Check normalize_timeout in app/limits.py against spec/timeouts.md and " +
    "tests/test_limits.py for input zero. Do not edit anything. " +
    'Return JSON with numeric "actual_zero", "allowed_min", "allowed_max", boolean "violates_spec", ' +
    'and "citations": an array of source references in path:line format.'],
];

// These expectations are evaluator-only; none are interpolated into model instructions.
export const CONTRACTS: Record<string, PyObj> = {
  "configuration-audit": fromJS({
    timeout_seconds: 15, worker_concurrency: 4, cache_ttl_seconds: 60,
    citations: ["config/settings.toml:2", "config/worker.toml:2", "config/cache.toml:2"],
  }) as PyObj,
  "function-callers": fromJS({
    callers: ["app/service.py:start", "app/worker.py:worker_client"],
    citations: ["app/service.py:6", "app/worker.py:5"],
  }) as PyObj,
  "config-consumer-trace": fromJS({
    setting: "service.timeout_seconds", reader: "app/config.py:load_timeout",
    consumer: "app/service.py:start", sink: "app.limits.build_client",
    citations: ["config/settings.toml:2", "app/config.py:2", "app/service.py:6"],
  }) as PyObj,
  "timeout-boundary-bug": fromJS({
    actual_zero: 0, allowed_min: 1, allowed_max: 60, violates_spec: true,
    citations: ["app/limits.py:6", "spec/timeouts.md:3", "tests/test_limits.py:5"],
  }) as PyObj,
};

export const REQUIRED_LINES: Record<string, [string, number][]> = {
  "configuration-audit": [["config/settings.toml", 2], ["config/worker.toml", 2], ["config/cache.toml", 2]],
  "function-callers": [["app/service.py", 5], ["app/service.py", 6], ["app/worker.py", 4], ["app/worker.py", 5]],
  "config-consumer-trace": [["config/settings.toml", 2], ["app/config.py", 1], ["app/config.py", 2],
    ["app/service.py", 5], ["app/service.py", 6]],
  "timeout-boundary-bug": [["app/limits.py", 6], ["spec/timeouts.md", 3], ["tests/test_limits.py", 5]],
};

// ---------------------------------------------------------------------------------------------
// shlex.split (posix=True, whitespace_split=True, comments=False), transcribed from read_token.
// ---------------------------------------------------------------------------------------------

export function shlexSplit(s: string): string[] {
  const WS = " \t\r\n";
  const QUOTES = "'\"";
  const out: string[] = [];
  let i = 0;
  const read = (): string => (i < s.length ? s[i++]! : "");
  for (;;) {
    let state: string | null = " ";
    let token = "";
    let quoted = false;
    let escapedstate = " ";
    for (;;) {
      const c = read();
      if (state === " ") {
        if (!c) {
          state = null;
          break;
        } else if (WS.includes(c)) {
          if (token || quoted) break;
          continue;
        } else if (c === "\\") {
          escapedstate = "a";
          state = c;
        } else if (QUOTES.includes(c)) {
          state = c;
        } else {
          token = c;
          state = "a";
        }
      } else if (state !== null && QUOTES.includes(state)) {
        quoted = true;
        if (!c) throw new ValueError("No closing quotation");
        if (c === state) state = "a";
        else if (c === "\\" && state === '"') {
          escapedstate = state;
          state = c;
        } else token += c;
      } else if (state === "\\") {
        if (!c) throw new ValueError("No escaped character");
        if (QUOTES.includes(escapedstate) && c !== state && c !== escapedstate) token += state;
        token += c;
        state = escapedstate;
      } else if (state === "a") {
        if (!c) {
          state = null;
          break;
        } else if (WS.includes(c)) {
          state = " ";
          if (token || quoted) break;
          continue;
        } else if (QUOTES.includes(c)) state = c;
        else if (c === "\\") {
          escapedstate = "a";
          state = c;
        } else token += c;
      }
    }
    if (!quoted && token === "") return out;
    out.push(token);
  }
}

// ---------------------------------------------------------------------------------------------
// The Python subset: top-level defs and classes, and every Call inside each top-level function in
// ast.walk order. Node children follow ast's _fields order, which is what fixes that order.
// ---------------------------------------------------------------------------------------------

type Tok = { kind: "name" | "num" | "str" | "op" | "nl"; text: string; line: number; col: number };
interface Node {
  kind: string;
  line: number;
  kids: Node[];
  name?: string; // Name.id / Attribute.attr, for Call.func matching
}

function unsupported(what: string, line: number): never {
  throw new Error(`code-mode guidance: fixture Python outside the modelled subset (${what} at line ${line}); ` +
    "extend pythonCalls() before changing FILES");
}

function tokenize(src: string): Tok[][] {
  const lines: Tok[][] = [];
  let cur: Tok[] = [];
  let depth = 0;
  let line = 1;
  let col = 0;
  let i = 0;
  const OPS = ["**=", "//=", ">>=", "<<=", "**", "//", "==", "!=", "<=", ">=", "->", ":=", "<<", ">>", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "@="];
  while (i < src.length) {
    const c = src[i];
    if (c === "\n") {
      if (depth === 0 && cur.length > 0) {
        lines.push(cur);
        cur = [];
      }
      i++;
      line++;
      col = 0;
      continue;
    }
    if (c === " ") {
      i++;
      col++;
      continue;
    }
    if (c === "\t" || c === "\f") unsupported("tab or form-feed indentation", line);
    if (c === "#") {
      while (i < src.length && src[i] !== "\n") i++;
      continue;
    }
    if (c === "\\" && src[i + 1] === "\n") {
      i += 2;
      line++;
      col = 0;
      continue;
    }
    const startLine = line;
    const startCol = col;
    const strM = /^([rRbBuU]{0,2})('''|"""|'|")/.exec(src.slice(i, i + 5));
    if (strM && !/[fF]/.test(strM[1]!) && (strM[1] === "" || !/[\p{L}\p{N}_]/u.test(src[i - 1] ?? ""))) {
      const q = strM[2]!;
      const raw = /[rR]/.test(strM[1]!);
      let j = i + strM[0].length;
      for (;;) {
        if (j >= src.length) unsupported("unterminated string", startLine);
        if (src[j] === "\\" && !raw) {
          if (src[j + 1] === "\n") line++;
          j += 2;
          continue;
        }
        if (src.startsWith(q, j)) {
          j += q.length;
          break;
        }
        if (src[j] === "\n") {
          if (q.length === 1) unsupported("newline in a string", startLine);
          line++;
        }
        j++;
      }
      cur.push({ kind: "str", text: src.slice(i, j), line: startLine, col: startCol });
      col += j - i;
      i = j;
      continue;
    }
    if (/^[fF]/.test(src.slice(i)) && /^[fF][rR]?['"]/.test(src.slice(i, i + 3))) unsupported("f-string", line);
    const nameM = /^[\p{L}_][\p{L}\p{N}_]*/u.exec(src.slice(i, i + 200));
    if (nameM) {
      cur.push({ kind: "name", text: nameM[0], line, col });
      i += nameM[0].length;
      col += nameM[0].length;
      continue;
    }
    const numM = /^(?:0[xXoObB][0-9a-fA-F_]+|(?:\d[\d_]*)?\.?\d[\d_]*(?:[eE][+-]?\d+)?[jJ]?)/.exec(src.slice(i, i + 100));
    if (numM && numM[0]) {
      cur.push({ kind: "num", text: numM[0], line, col });
      i += numM[0].length;
      col += numM[0].length;
      continue;
    }
    const op = OPS.find((o) => src.startsWith(o, i)) ?? (c as string);
    if ("([{".includes(op)) depth++;
    if (")]}".includes(op)) depth--;
    cur.push({ kind: "op", text: op, line, col });
    i += op.length;
    col += op.length;
  }
  if (cur.length > 0) lines.push(cur);
  return lines;
}

class ExprParser {
  private i = 0;
  constructor(private readonly t: Tok[]) {}
  get done(): boolean {
    return this.i >= this.t.length;
  }
  peek(): Tok | undefined {
    return this.t[this.i];
  }
  private is(text: string): boolean {
    const k = this.t[this.i];
    return k !== undefined && (k.kind === "op" || k.kind === "name") && k.text === text;
  }
  eat(text: string): boolean {
    if (this.is(text)) {
      this.i++;
      return true;
    }
    return false;
  }
  expect(text: string): void {
    if (!this.eat(text)) unsupported(`expected '${text}'`, this.peek()?.line ?? 0);
  }
  /** testlist: a bare tuple `a, b` is one Tuple node. */
  testlist(): Node {
    const first = this.test();
    if (!this.is(",")) return first;
    const elts = [first];
    while (this.eat(",")) {
      if (this.done || this.is("=") || this.is(")")) break;
      elts.push(this.test());
    }
    return { kind: "Tuple", line: first.line, kids: elts };
  }
  test(): Node {
    const line = this.peek()?.line ?? 0;
    if (this.is("lambda")) unsupported("lambda", line);
    const body = this.orTest();
    if (this.eat("if")) {
      const cond = this.orTest();
      this.expect("else");
      const orelse = this.test();
      return { kind: "IfExp", line: body.line, kids: [cond, body, orelse] };
    }
    return body;
  }
  private boolChain(op: string, next: () => Node): Node {
    const first = next();
    if (!this.is(op)) return first;
    const values = [first];
    while (this.eat(op)) values.push(next());
    return { kind: "BoolOp", line: first.line, kids: values };
  }
  private orTest(): Node {
    return this.boolChain("or", () => this.andTest());
  }
  private andTest(): Node {
    return this.boolChain("and", () => this.notTest());
  }
  private notTest(): Node {
    const t = this.peek();
    if (this.eat("not")) return { kind: "UnaryOp", line: t!.line, kids: [this.notTest()] };
    return this.comparison();
  }
  private comparison(): Node {
    const left = this.bin(0);
    const comps: Node[] = [];
    for (;;) {
      if (["<", ">", "==", ">=", "<=", "!="].some((o) => this.eat(o))) comps.push(this.bin(0));
      else if (this.eat("in")) comps.push(this.bin(0));
      else if (this.is("not") && this.t[this.i + 1]?.text === "in") {
        this.i += 2;
        comps.push(this.bin(0));
      } else if (this.eat("is")) {
        this.eat("not");
        comps.push(this.bin(0));
      } else break;
    }
    return comps.length ? { kind: "Compare", line: left.line, kids: [left, ...comps] } : left;
  }
  private static readonly LEVELS = [["|"], ["^"], ["&"], ["<<", ">>"], ["+", "-"], ["*", "/", "//", "%", "@"]];
  private bin(level: number): Node {
    if (level >= ExprParser.LEVELS.length) return this.unary();
    let left = this.bin(level + 1);
    for (;;) {
      const op = ExprParser.LEVELS[level]!.find((o) => this.t[this.i]?.kind === "op" && this.t[this.i]!.text === o);
      if (op === undefined) return left;
      this.i++;
      left = { kind: "BinOp", line: left.line, kids: [left, this.bin(level + 1)] };
    }
  }
  private unary(): Node {
    const t = this.peek();
    if (t && t.kind === "op" && ["-", "+", "~"].includes(t.text)) {
      this.i++;
      return { kind: "UnaryOp", line: t.line, kids: [this.unary()] };
    }
    return this.power();
  }
  private power(): Node {
    const base = this.primary();
    if (this.eat("**")) return { kind: "BinOp", line: base.line, kids: [base, this.unary()] };
    return base;
  }
  private primary(): Node {
    let node = this.atom();
    for (;;) {
      if (this.eat(".")) {
        const name = this.t[this.i++];
        if (!name || name.kind !== "name") unsupported("attribute", node.line);
        node = { kind: "Attribute", line: node.line, kids: [node], name: name.text };
      } else if (this.eat("(")) {
        const args: Node[] = [];
        const keywords: Node[] = [];
        while (!this.eat(")")) {
          const t = this.peek();
          if (!t) unsupported("unclosed call", node.line);
          if (this.eat("**")) keywords.push({ kind: "keyword", line: t.line, kids: [this.test()] });
          else if (this.eat("*")) args.push({ kind: "Starred", line: t.line, kids: [this.test()] });
          else if (t.kind === "name" && this.t[this.i + 1]?.text === "=") {
            this.i += 2;
            keywords.push({ kind: "keyword", line: t.line, kids: [this.test()] });
          } else {
            args.push(this.test());
            if (this.is("for")) unsupported("generator expression", t.line);
          }
          if (!this.eat(",")) {
            this.expect(")");
            break;
          }
        }
        node = { kind: "Call", line: node.line, kids: [node, ...args, ...keywords] };
      } else if (this.eat("[")) {
        const slice = this.subscript();
        this.expect("]");
        node = { kind: "Subscript", line: node.line, kids: [node, slice] };
      } else return node;
    }
  }
  private subscript(): Node {
    const line = this.peek()?.line ?? 0;
    const part = (): Node | null => (this.is(":") || this.is("]") || this.is(",") ? null : this.test());
    const lower = part();
    if (!this.eat(":")) {
      if (lower === null) unsupported("empty subscript", line);
      return lower as Node;
    }
    const upper = part();
    const step = this.eat(":") ? part() : null;
    return { kind: "Slice", line, kids: [lower, upper, step].filter((n): n is Node => n !== null) };
  }
  private atom(): Node {
    const t = this.t[this.i++];
    if (!t) unsupported("missing expression", 0);
    if (t.kind === "name") {
      if (["None", "True", "False"].includes(t.text)) return { kind: "Constant", line: t.line, kids: [] };
      if (["yield", "await", "lambda", "for", "if", "else", "not"].includes(t.text)) unsupported(t.text, t.line);
      return { kind: "Name", line: t.line, kids: [], name: t.text };
    }
    if (t.kind === "num") return { kind: "Constant", line: t.line, kids: [] };
    if (t.kind === "str") {
      while (this.peek()?.kind === "str") this.i++; // adjacent literals are ONE Constant
      return { kind: "Constant", line: t.line, kids: [] };
    }
    if (t.text === "(") {
      if (this.eat(")")) return { kind: "Tuple", line: t.line, kids: [] };
      const inner = this.testlist();
      if (this.is("for")) unsupported("generator expression", t.line);
      this.expect(")");
      return inner;
    }
    if (t.text === "[") {
      const elts: Node[] = [];
      while (!this.eat("]")) {
        elts.push(this.test());
        if (this.is("for")) unsupported("list comprehension", t.line);
        if (!this.eat(",")) {
          this.expect("]");
          break;
        }
      }
      return { kind: "List", line: t.line, kids: elts };
    }
    if (t.text === "{") {
      const keys: Node[] = [];
      const values: Node[] = [];
      let isSet = false;
      while (!this.eat("}")) {
        if (this.eat("**")) values.push(this.bin(0));
        else {
          const k = this.test();
          if (this.is("for")) unsupported("comprehension", t.line);
          if (this.eat(":")) {
            keys.push(k);
            values.push(this.test());
            if (this.is("for")) unsupported("dict comprehension", t.line);
          } else {
            isSet = true;
            keys.push(k);
          }
        }
        if (!this.eat(",")) {
          this.expect("}");
          break;
        }
      }
      if (isSet) return { kind: "Set", line: t.line, kids: keys };
      return { kind: "Dict", line: t.line, kids: [...keys, ...values] };
    }
    return unsupported(`token '${t.text}'`, t.line);
  }
}

/** One simple statement -> its node. Compound statements are outside the subset. */
function statement(toks: Tok[]): Node {
  const p = new ExprParser(toks);
  const first = toks[0] as Tok;
  const kw = first.kind === "name" ? first.text : "";
  if (["if", "for", "while", "with", "try", "def", "class", "async", "match", "else", "elif", "except", "finally", "global", "nonlocal", "del", "yield"].includes(kw)) {
    unsupported(`statement '${kw}'`, first.line);
  }
  if (first.kind === "op" && first.text === "@") unsupported("decorator", first.line);
  let node: Node;
  if (kw === "pass") {
    p.eat("pass");
    node = { kind: "Pass", line: first.line, kids: [] };
  } else if (kw === "return") {
    p.eat("return");
    node = { kind: "Return", line: first.line, kids: p.done ? [] : [p.testlist()] };
  } else if (kw === "raise") {
    p.eat("raise");
    const kids: Node[] = [];
    if (!p.done) {
      kids.push(p.test());
      if (p.eat("from")) kids.push(p.test());
    }
    node = { kind: "Raise", line: first.line, kids };
  } else if (kw === "assert") {
    p.eat("assert");
    const kids = [p.test()];
    if (p.eat(",")) kids.push(p.test());
    node = { kind: "Assert", line: first.line, kids };
  } else if (kw === "import" || kw === "from") {
    return { kind: "Import", line: first.line, kids: [] };
  } else {
    const exprs = [p.testlist()];
    const aug = p.peek();
    if (aug && aug.kind === "op" && aug.text.length >= 2 && aug.text.endsWith("=") && !["==", "<=", ">=", "!="].includes(aug.text)) {
      p.eat(aug.text);
      node = { kind: "AugAssign", line: first.line, kids: [exprs[0] as Node, p.testlist()] };
    } else {
      while (p.eat("=")) exprs.push(p.testlist());
      node = exprs.length === 1
        ? { kind: "Expr", line: first.line, kids: [exprs[0] as Node] }
        : { kind: "Assign", line: first.line, kids: exprs };
    }
  }
  if (!p.done) unsupported(`trailing '${p.peek()!.text}'`, p.peek()!.line);
  return node;
}

/** Split a logical line on top-level `;`. */
function simpleStatements(toks: Tok[]): Tok[][] {
  const out: Tok[][] = [[]];
  let depth = 0;
  for (const t of toks) {
    if (t.kind === "op" && "([{".includes(t.text)) depth++;
    if (t.kind === "op" && ")]}".includes(t.text)) depth--;
    if (depth === 0 && t.kind === "op" && t.text === ";") out.push([]);
    else out[out.length - 1]!.push(t);
  }
  return out.filter((s) => s.length > 0);
}

export interface PyDef {
  name: string;
  line: number;
  kind: "FunctionDef" | "ClassDef";
  calls: { line: number; callee: string | null }[];
}

/** ast.parse(src).body's FunctionDef/ClassDef nodes, and for each FunctionDef the Call nodes of
 *  ast.walk(def) in order: breadth-first over the ast children. */
export function pythonDefs(src: string): PyDef[] {
  const lines = tokenize(src);
  const defs: PyDef[] = [];
  for (let k = 0; k < lines.length; k++) {
    const toks = lines[k] as Tok[];
    if (toks[0]!.col !== 0) unsupported("indented line outside a def", toks[0]!.line);
    const head = toks[0]!.text;
    const bodyLines: Tok[][] = [];
    while (k + 1 < lines.length && lines[k + 1]![0]!.col > 0) bodyLines.push(lines[++k] as Tok[]);
    if (toks[0]!.kind === "op" && head === "@") unsupported("decorator", toks[0]!.line);
    if (head === "async") continue; // AsyncFunctionDef is neither a FunctionDef nor a ClassDef
    if (head === "class") {
      defs.push({ name: toks[1]!.text, line: toks[0]!.line, kind: "ClassDef", calls: [] });
      continue;
    }
    if (head !== "def") continue;
    // def NAME ( plain, names ) : [simple statement]
    const name = toks[1]!.text;
    let j = 3;
    const params: Node[] = [];
    while (toks[j] && toks[j]!.text !== ")") {
      if (toks[j]!.kind !== "name") unsupported("parameter default, annotation or star", toks[j]!.line);
      params.push({ kind: "arg", line: toks[j]!.line, kids: [] });
      j++;
      if (toks[j]?.text === ",") j++;
      else if (toks[j]?.text !== ")") unsupported("parameter default, annotation or star", toks[j]?.line ?? 0);
    }
    if (toks[j + 1]?.text !== ":") unsupported("return annotation", toks[0]!.line);
    const inline = toks.slice(j + 2);
    const body: Node[] = [];
    for (const stmtToks of [inline, ...bodyLines].flatMap(simpleStatements)) body.push(statement(stmtToks));
    const root: Node = { kind: "FunctionDef", line: toks[0]!.line, kids: [{ kind: "arguments", line: 0, kids: params }, ...body] };
    const calls: PyDef["calls"] = [];
    const queue: Node[] = [root];
    while (queue.length > 0) {
      const node = queue.shift() as Node;
      queue.push(...node.kids);
      if (node.kind === "Call") {
        const f = node.kids[0] as Node;
        calls.push({ line: node.line, callee: f.kind === "Name" || f.kind === "Attribute" ? (f.name as string) : null });
      }
    }
    defs.push({ name, line: toks[0]!.line, kind: "FunctionDef", calls });
  }
  return defs;
}

// ---------------------------------------------------------------------------------------------

const lineKey = (path: string, n: number) => `${path} ${n}`;
/** json.dumps(value, sort_keys=True) — used only as an equality signature. */
function signature(v: PyValue): string {
  const sortKeys = (x: PyValue): PyValue => {
    if (Array.isArray(x)) return x.map(sortKeys);
    if (isPyObj(x)) return obj([...x.__pyObj].sort(([a], [b]) => cpCompare(a, b)).map(([k, y]) => [k, sortKeys(y)]));
    return x;
  };
  return dumps(sortKeys(v));
}
/** Python's str.isdecimal digits: every Nd run is a contiguous 0-9 block, so a digit's value is its
 *  offset from the start of its run, mod 10. */
function pyIntDigits(s: string): number {
  let v = 0;
  for (const ch of s) {
    let cp = ch.codePointAt(0) as number;
    let start = cp;
    while (/\p{Nd}/u.test(String.fromCodePoint(start - 1))) start--;
    cp = (cp - start) % 10;
    v = v * 10 + cp;
  }
  return v;
}

/** All tools derive results from FILES; no shell, disk, network, exec, or eval. */
export class GuidanceWorkspace {
  files = new Map(FILES);
  calls: PyValue[] = [];
  repeated_calls = 0;
  private seenCalls = new Set<string>();
  private lines = new Set<string>();
  private facts = new Set<string>();
  private noNewEvidence = 0;
  private quality = obj([]);
  private trace: PyValue[] = [];

  execute(tool: { name: PyValue; input: PyValue }): string {
    const name = tool.name;
    const args = tool.input;
    this.calls.push(name);
    const sig = signature([name, args]);
    this.repeated_calls += this.seenCalls.has(sig) ? 1 : 0;
    this.seenCalls.add(sig);
    this.trace.push(obj([["name", name], ["input", args]]));
    const before = [this.lines.size, this.facts.size];
    const handlers: Record<string, (a: PyValue) => string> = {
      Read: (a) => this.Read(a), Grep: (a) => this.Grep(a), LSP: (a) => this.LSP(a), Bash: (a) => this.Bash(a),
    };
    hashKey(name); // `name not in handlers` hashes the name: a list or dict name is a TypeError
    if (typeof name !== "string" || !Object.hasOwn(handlers, name)) {
      throw new ValueError("unknown or unavailable synthetic tool");
    }
    const result = handlers[name]!(args);
    this.noNewEvidence += before[0] === this.lines.size && before[1] === this.facts.size ? 1 : 0;
    return result;
  }

  private Read(args: PyValue): string {
    GuidanceWorkspace.exact(args, ["file_path"]);
    const path = sub(args, "file_path") as string;
    const text = this.files.get(path);
    if (text === undefined) throw new ValueError("unknown fixture path");
    const n = pySplitlines(text).length;
    for (let i = 1; i <= n; i++) this.lines.add(lineKey(path, i));
    return text;
  }

  private Grep(args: PyValue): string {
    GuidanceWorkspace.exact(args, ["pattern", "path"]);
    const pattern = sub(args, "pattern") as string;
    const prefix = sub(args, "path") as string;
    const trimmed = prefix.replace(/\/+$/, "");
    const paths = [...this.files.keys()].filter((p) => prefix === "." || p === prefix || p.startsWith(trimmed + "/"));
    if (!pattern || paths.length === 0) throw new ValueError("invalid literal search");
    const rows: string[] = [];
    for (const path of paths) {
      pySplitlines(this.files.get(path) as string).forEach((line, idx) => {
        if (line.includes(pattern)) {
          rows.push(`${path}:${idx + 1}:${line}`);
          this.lines.add(lineKey(path, idx + 1));
        }
      });
    }
    return rows.join("\n");
  }

  private LSP(args: PyValue): string {
    GuidanceWorkspace.exact(args, ["operation", "file_path", "symbol"]);
    const operation = sub(args, "operation") as string;
    const path = sub(args, "file_path") as string;
    const symbol = sub(args, "symbol") as string;
    if (!this.files.has(path) || !path.endsWith(".py")) throw new ValueError("LSP requires a Python fixture");
    const defs = pythonDefs(this.files.get(path) as string);
    let rows: PyValue[];
    if (operation === "documentSymbol" && !symbol) {
      rows = defs.map((d) => obj([["name", d.name], ["line", { __pyNum: String(d.line), isFloat: false }]]));
      for (const d of defs) this.lines.add(lineKey(path, d.line));
    } else if (operation === "findReferences" && symbol) {
      if (!defs.some((d) => d.kind === "FunctionDef" && d.name === symbol)) {
        throw new ValueError("symbol is not defined in the given fixture");
      }
      rows = [];
      for (const [candidate, source] of this.files) {
        if (!candidate.endsWith(".py")) continue;
        for (const enclosing of pythonDefs(source)) {
          if (enclosing.kind !== "FunctionDef") continue;
          for (const call of enclosing.calls) {
            if (call.callee !== symbol) continue;
            rows.push(obj([
              ["path", candidate], ["line", { __pyNum: String(call.line), isFloat: false }],
              ["caller", enclosing.name], ["caller_line", { __pyNum: String(enclosing.line), isFloat: false }],
            ]));
            this.lines.add(lineKey(candidate, call.line));
            this.lines.add(lineKey(candidate, enclosing.line));
          }
        }
      }
    } else throw new ValueError("invalid LSP operation");
    this.facts.add(JSON.stringify([operation, path, symbol]));
    return dumps(rows);
  }

  private Bash(args: PyValue): string {
    GuidanceWorkspace.exact(args, ["command"]);
    const words = shlexSplit(sub(args, "command") as string);
    if (words.length === 4 && words.join(" ") === ["find", ".", "-type", "f"].join(" ")) {
      for (const path of this.files.keys()) this.facts.add(JSON.stringify(["path", path]));
      return [...this.files.keys()].join("\n");
    }
    if (words.length === 2 && words[0] === "cat") return this.Read(obj([["file_path", words[1] as string]]));
    if (words.length === 4 && words[0] === "grep" && words[1] === "-n" && this.files.has(words[3] as string)) {
      return this.Grep(obj([["pattern", words[2] as string], ["path", words[3] as string]]));
    }
    throw new ValueError("command is outside the read-only fixture allowlist");
  }

  /** isinstance(args, dict), exactly the expected keys, each value exactly a str. */
  private static exact(args: PyValue, expected: string[]): void {
    if (!isPyObj(args)) throw new ValueError("invalid tool input");
    const keys = new Set(args.__pyObj.map(([k]) => k));
    if (keys.size !== expected.length || !expected.every((k) => keys.has(k))
      || expected.some((k) => typeof sub(args, k) !== "string")) {
      throw new ValueError("invalid tool input");
    }
  }

  correct(caseName: string, text: PyValue): boolean {
    const expected = CONTRACTS[caseName] as PyObj;
    let answer: PyValue | undefined;
    if (typeof text !== "string") answer = null; // json.loads(non-str) is a TypeError, caught
    else {
      try {
        answer = loads(text);
      } catch (e) {
        if (!(e instanceof SyntaxError)) throw e;
        answer = null;
      }
    }
    const o = isPyObj(answer) ? answer : obj([]);
    const expectedKeys = expected.__pyObj.map(([k]) => k);
    const answerKeys = new Set(o.__pyObj.map(([k]) => k));
    this.quality = obj([["json_shape", isPyObj(answer) && answerKeys.size === expectedKeys.length
      && expectedKeys.every((k) => answerKeys.has(k))]]);
    for (const [key, value] of expected.__pyObj) {
      if (key === "citations") continue;
      const actual = get(o, key);
      let ok: boolean;
      if (Array.isArray(value) && Array.isArray(actual) && actual.every((v) => typeof v === "string")) {
        const a = (actual as string[]).slice().sort(cpCompare);
        const b = (value as string[]).slice().sort(cpCompare);
        ok = a.length === b.length && a.every((x, i) => x === b[i]);
      } else ok = sameTypeEqual(actual, value);
      setKey(this.quality, key, ok);
    }
    const references = get(o, "citations", []);
    const citedPaths = new Set<string>();
    let valid = Array.isArray(references) && references.length > 0;
    if (Array.isArray(references)) {
      for (const ref of references) {
        const m = typeof ref === "string" ? /^([^\n]+):(\p{Nd}+)(?:-(\p{Nd}+))?$/u.exec(ref) : null;
        if (!m) {
          valid = false;
          continue;
        }
        const [, path, first, last] = m as unknown as [string, string, string, string | undefined];
        const lo = pyIntDigits(first);
        const hi = pyIntDigits(last ?? first);
        const nonEmpty = hi >= lo;
        // lines.issubset(self._lines), without materializing a range the model may have made huge:
        // a range wider than the lines ever seen cannot be a subset.
        let subset = nonEmpty && hi - lo + 1 <= this.lines.size;
        for (let n = lo; subset && n <= hi; n++) subset = this.lines.has(lineKey(path, n));
        valid = valid && nonEmpty && subset;
        if (nonEmpty) citedPaths.add(path);
      }
    }
    const required = REQUIRED_LINES[caseName] as [string, number][];
    setKey(this.quality, "valid_citations", valid);
    setKey(this.quality, "required_sources_cited", required.every(([p]) => citedPaths.has(p)));
    setKey(this.quality, "source_evidence", required.every(([p, n]) => this.lines.has(lineKey(p, n))));
    return this.quality.__pyObj.every(([, v]) => v === true);
  }

  metrics(): PyObj {
    // dict(Counter(self.calls)): first-seen key order, keys merged by hash equality, and rendered as
    // json.dumps renders a non-str key.
    const counts = new Map<string, [PyValue, number]>();
    for (const name of this.calls) {
      const k = hashKey(name);
      const hit = counts.get(k);
      if (hit) hit[1]++;
      else counts.set(k, [name, 1]);
    }
    const jsonKey = (v: PyValue): string => {
      if (typeof v === "string") return v;
      if (v === null) return "null";
      if (typeof v === "boolean") return v ? "true" : "false";
      return dumps(v);
    };
    const passed = this.quality.__pyObj.filter(([, v]) => v === true).length;
    return obj([
      ["tool_counts", obj([...counts.values()].map(([k, n]) => [jsonKey(k), { __pyNum: String(n), isFloat: false }]))],
      ["exact_duplicate_calls", { __pyNum: String(this.repeated_calls), isFloat: false }],
      ["no_new_evidence_calls", { __pyNum: String(this.noNewEvidence), isFloat: false }],
      ["quality_checks", obj([
        ["passed", { __pyNum: String(passed), isFloat: false }],
        ["total", { __pyNum: String(this.quality.__pyObj.length), isFloat: false }],
        ["checks", obj(this.quality.__pyObj.map(([k, v]) => [k, v]))],
      ])],
      ["trace", [...this.trace]],
    ]);
  }
}

/** `actual == value and type(actual) is type(value)` for the contract's scalar types. */
function sameTypeEqual(actual: PyValue, value: PyValue): boolean {
  if (typeof value === "string") return actual === value;
  if (typeof value === "boolean") return actual === value;
  if (value === null) return actual === null;
  if (Array.isArray(value)) {
    // A list contract whose actual is not a list of str: list == list compares elementwise, and a
    // non-str element can never equal a str one.
    return false;
  }
  const v = value as { __pyNum: string; isFloat: boolean };
  const a = actual as { __pyNum?: string; isFloat?: boolean };
  return typeof actual === "object" && actual !== null && a.__pyNum !== undefined && a.isFloat === v.isFloat
    && (v.isFloat ? Number(a.__pyNum) === Number(v.__pyNum) : BigInt(a.__pyNum) === BigInt(v.__pyNum));
}

/** The prompt-only A/B scenario the comparison runs when `--prompt-guidance` is set. */
export const GUIDANCE_SCENARIO: Scenario = {
  Workspace: GuidanceWorkspace as unknown as Scenario["Workspace"],
  SYSTEM: GUIDANCE_SYSTEM,
  TOOLS: GUIDANCE_TOOLS,
  CASES: GUIDANCE_CASES,
  GUIDANCE,
  GUIDANCE_PATH,
  FILES,
};

// =============================================================================================
// MOCK — the packaged bridge against an isolated daemon and a loopback upstream.
// =============================================================================================

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
  ...Object.fromEntries(PROBE_CASES.map(([c]) => [c, c === "optional-discovery" ? 3 : 2])),
  "toggle-probe": 4,
};
export const PREFACE = "Checking the current settings before updating them.";
export const CALLER_SYSTEM = "Use tools to verify the requested facts. Keep the final answer concise. " +
  "If a script tool is available, it may batch related tool operations. " +
  "Only synthetic tools exist; do not invent results or poll background jobs.";
export const TOGGLE_SYSTEM = "Toggle probe caller instructions.";
export const TOGGLE_PROMPT = "TOGGLE_GUIDANCE_PROBE";
export const TOGGLE_RESPONSE = "TOGGLE_OK";

const countOf = (hay: string, needle: string) => hay.split(needle).length - 1;

export function assertGuidance(body: PyValue, callerSystem: string, enabled: boolean): void {
  const inputItems = get(body, "input");
  if (!Array.isArray(inputItems) || inputItems.length < 2) {
    throw new ValueError("Responses lite input omitted its developer instruction");
  }
  if (!pyEq(get(inputItems[0] as PyValue, "type"), "additional_tools") || !pyEq(get(inputItems[0] as PyValue, "role"), "developer")) {
    throw new ValueError("missing Responses lite additional_tools prefix");
  }
  const developers = inputItems.filter((item) => pyEq(get(item, "role"), "developer") && !pyEq(get(item, "type"), "additional_tools"));
  if (developers.length !== 1 || developers[0] !== inputItems[1]) {
    throw new ValueError("developer instructions moved or duplicated");
  }
  const instructions = get(developers[0] as PyValue, "content");
  const expected = callerSystem + (enabled ? "\n\n" + GUIDANCE : "");
  if (instructions !== expected) {
    throw new ValueError("developer instructions did not preserve the expected caller/guidance boundary");
  }
  if (countOf(instructions, "<code_mode_orchestration>") !== Number(enabled)) {
    throw new ValueError("code-mode guidance section count was not exactly one when enabled");
  }
  const declarations = iter(get(inputItems[0] as PyValue, "tools", []));
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
          const hit = PROBE_CASES.find(([, prompt]) => wire.includes(prompt));
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
              ["name", "splice_exec"], ["input", SCRIPTS[caseName] as string], ["status", "completed"]]);
          } else {
            const expected = EXPECTED[caseName] as string;
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
              if (prefaces.length !== 1 || (prefaces[0] as number) >= outerIndex) {
                throw new ValueError("assistant continuity was lost, duplicated, or reordered");
              }
            }
            if (caseName === "optional-discovery") {
              const native = iter(input).filter((it) => pyEq(get(it, "call_id"), "native-search")).map((it) => get(it, "type"));
              if (!(native.length === 2 && pyEq(native[0] as PyValue, "tool_search_call") && pyEq(native[1] as PyValue, "tool_search_output"))) {
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

export function mockConfigure(root: string, upstream: number, control: number, head: number, baseline: number,
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
        const health = await mockSeams.requestJson(control, "GET", "/health");
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
  const response = await mockSeams.requestJson(head, "POST", "/v1/messages", obj([
    ["model", "gpt-6-astra"], ["max_tokens", int(100)], ["stream", false], ["system", TOGGLE_SYSTEM],
    ["output_config", obj([["effort", "high"]])], ["tools", PROBE_TOOLS],
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
    const [control, head, baseline] = (await availablePorts()) as [number, number, number];
    const env = mockConfigure(boot, upstream, control, head, baseline, configured);
    state.set("toggle_enabled", expectedEnabled);
    const [proc, log] = await startDaemon(boot, artifact, env, control);
    try {
      await runToggleProbe(head, env.SPLICE_PROBE_BEARER as string);
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

export async function runMock(args: { artifact: string; receipt: string }): Promise<void> {
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
      const [control, head, baseline] = (await availablePorts()) as [number, number, number];
      const env = mockConfigure(root, server.serverPort, control, head, baseline);
      const previous = process.env.SPLICE_PROBE_BEARER;
      process.env.SPLICE_PROBE_BEARER = env.SPLICE_PROBE_BEARER;
      try {
        const [proc, log] = await startDaemon(root, artifact, env, control);
        try {
          const preflight = await mockSeams.requestJson(baseline, "POST", "/v1/messages", obj([
            ["model", "gpt-6-astra"], ["max_tokens", int(100)], ["stream", false],
            ["messages", [obj([["role", "user"], ["content", "AUTH_PREFLIGHT"]])]],
          ]), [["Content-Type", "application/json"], ["Authorization", "Bearer " + env.SPLICE_PROBE_BEARER]]);
          if (!pyEq(get(preflight, "stop_reason"), "end_turn")
            || !iter(get(preflight, "content", [])).some((part) => pyEq(get(part, "text"), "AUTH_OK"))
            || counter(state, "auth-preflight") !== 1 || truthy((state.get("error") as string | undefined) ?? null)) {
            throw new ValueError("two-head auth preflight failed");
          }
          process.stdout.write("Two-head synthetic auth preflight: baseline passed\n");
          for (const [caseName, prompt] of PROBE_CASES) {
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

/** The one global the mock suite replaced (mock.patch target in the original). */
export const mockSeams = { requestJson };

// =============================================================================================
// COMPARE — the billed, opt-in runner for the bounded comparison.
// =============================================================================================

/** The isolated daemon's config, state dir and credentials, and the environment that points at them. */
export async function compareConfigure(root: string, authFile: string, proxyPort: number, promptGuidance = false):
  Promise<[Record<string, string>, number, number, number]> {
  const state = join(root, "state");
  try {
    mkdirSync(state, { mode: 0o700 });
  } catch (e) {
    throw osError(e, state);
  }
  const bearer = "probe-management-" + Buffer.from(crypto.getRandomValues(new Uint8Array(24))).toString("hex");
  writeFileSync(join(state, "mgmt-key"), bearer);
  chmodSync(join(state, "mgmt-key"), 0o600);
  // Refresh is deliberately unavailable in this experiment. A stale credential
  // fails locally rather than mutating the operator's token or expanding spend.
  const auth = join(root, "auth.json");
  closeSync(openSync(auth, "a", 0o600));
  let source: Buffer;
  try {
    source = readFileSync(authFile);
  } catch (e) {
    throw osError(e, authFile);
  }
  writeFileSync(auth, source);
  const [control, baseline, codeMode] = (await ephemeralPorts(3)) as [number, number, number];
  const lines = ["[daemon]", `control_port = ${control}`, 'effort = "high"'];
  for (const [variant, port, enabled] of [["baseline", baseline, promptGuidance ? "true" : "false"], ["code_mode", codeMode, "true"]] as const) {
    lines.push(
      `[providers.${variant}]`, 'dialect = "openai-responses"',
      `base_url = "http://127.0.0.1:${proxyPort}"`,
      `auth = { kind = "chatgpt-oauth", file = ${dumps(auth)} }`,
      `quirks = { code_mode = ${enabled}, account_id_header = true, websocket = false, zstd_request_body = false, ` +
        "tool_surface = { enabled = true } }",
      `[[providers.${variant}.models]]`, 'id = "gpt-6-astra"', "context_window = 400000",
      `[heads.${variant}]`, `provider = "${variant}"`, `port = ${port}`,
      `discovery_prefix = "claude-probe-${variant}--"`, 'pinned_model = "gpt-6-astra"',
      `[heads.${variant}.claude]`, `command = "claude-probe-${variant}"`,
    );
  }
  const config = join(root, "splice.toml");
  writeFileSync(config, lines.join("\n") + "\n");
  const env: Record<string, string> = {
    ...(process.env as Record<string, string>),
    SPLICE_CONFIG: config, CLAUDEX_STATE_DIR: state, CLAUDEX_QUOTA_POLL: "off",
    CODEX_OAUTH_TOKEN_URL: `http://127.0.0.1:${proxyPort}/oauth/token`, SPLICE_PROBE_BEARER: bearer,
  };
  return [env, control, baseline, codeMode];
}

const isProcessError = (e: unknown) => isOSError(e) || e instanceof SubprocessError;

export async function stopProcess(proc: Proc | null): Promise<[number | null, Error | null]> {
  if (proc === null) return [null, null];
  let cleanupError: Error | null = null;
  let status: number | null;
  try {
    status = proc.poll();
  } catch (exc) {
    if (!isProcessError(exc)) throw exc;
    status = null;
    cleanupError = exc as Error;
  }
  if (status === null) {
    try {
      proc.terminate();
      try {
        await proc.wait(10);
      } catch (exc) {
        if (!(exc instanceof TimeoutExpired)) throw exc;
        proc.kill();
        await proc.wait(5);
      }
    } catch (exc) {
      if (!isProcessError(exc)) throw exc;
      cleanupError = cleanupError ?? (exc as Error);
    }
  }
  try {
    status = proc.poll();
  } catch (exc) {
    if (!isProcessError(exc)) throw exc;
    cleanupError = cleanupError ?? (exc as Error);
    status = null;
  }
  return [Number.isInteger(status) ? status : null, cleanupError];
}

export async function stopServer(server: PyServer | null, loop: Promise<void> | null): Promise<Error | null> {
  let cleanupError: Error | null = null;
  if (server !== null) {
    for (const operation of [() => server.shutdown(), () => server.serverClose()]) {
      try {
        await operation();
      } catch (exc) {
        if (!isOSError(exc)) throw exc;
        cleanupError = cleanupError ?? (exc as Error);
      }
    }
  }
  // thread.join(timeout=5): the serve loop has five seconds to return.
  if (loop !== null) await Promise.race([loop, Bun.sleep(5000)]);
  return cleanupError;
}

export function finalizeReceipt(receipt: string, artifactSha256: string, phase: string, category: string,
  exitStatus: number | null, budget: Budget): void {
  const finalAccounting = budget.snapshot();
  if (get(finalAccounting, "error") === "comparison ended") setKey(finalAccounting, "error", null);
  let saved: PyObj;
  if (existsSync(receipt)) {
    let text: string;
    try {
      text = new TextDecoder("utf-8", { fatal: true }).decode(readFileSync(receipt));
    } catch {
      const e = new SyntaxError("'utf-8' codec can't decode bytes"); // UnicodeDecodeError is a ValueError
      e.name = "UnicodeDecodeError";
      throw e;
    }
    const parsed = loads(text.replaceAll("\r\n", "\n").replaceAll("\r", "\n"));
    // saved.update(...) on anything but a dict is an AttributeError, which nothing here catches.
    if (!isPyObj(parsed)) throw new AttributeError(`'${typeName(parsed)}' object has no attribute 'update'`);
    saved = parsed;
  } else {
    saved = obj([["model", "gpt-6-astra"], ["planned_runs", int(16)], ["completed_runs", int(0)], ["runs", []]]);
  }
  setKey(saved, "artifact_sha256", artifactSha256);
  setKey(saved, "phase", phase);
  setKey(saved, "category", category);
  setKey(saved, "exit_status", exitStatus === null ? null : int(exitStatus));
  setKey(saved, "final_accounting", finalAccounting);
  mkdirSync(dirname(receipt), { recursive: true });
  writeFileSync(receipt, dumpsIndent(saved, 2) + "\n");
}

export interface RunArgs {
  artifact: string;
  auth_file: string;
  receipt: string;
  prompt_guidance?: boolean;
}

export async function runCompare(args: RunArgs): Promise<void> {
  validatePromptExperiment(args);
  const artifact = resolve(args.artifact);
  const receipt = args.receipt;
  if (existsSync(receipt)) throw new ValueError("refusing to overwrite a receipt");
  let artifactBytes: Buffer;
  try {
    artifactBytes = readFileSync(artifact);
  } catch (e) {
    throw osError(e, artifact);
  }
  const artifactSha256 = createHash("sha256").update(artifactBytes).digest("hex");
  const budget = compareSeams.Budget();
  let server: PyServer | null = null;
  let loop: Promise<void> | null = null;
  let proc: Proc | null = null;
  const previous = process.env.SPLICE_PROBE_BEARER;
  let phase = "startup";
  let category = "configuration_failure";
  let exitStatus: number | null = null;
  let cleanupError: Error | null = null;
  let receiptError: Error | null = null;
  let primaryError: Error | null = null;
  let launchAttempted = false;
  try {
    server = await compareSeams.ThreadingHTTPServer(["127.0.0.1", 0], proxyHandler(budget));
    server.daemonThreads = false; // server_close must await every billed response's accounting.
    loop = Promise.resolve(server.serveForever());
    const root = mkdtempSync(join(tmpdir(), "code-mode-comparison-"));
    try {
      try {
        const promptGuidance = args.prompt_guidance ?? false;
        const [env, control, baseline, codeMode] = await compareConfigure(root, args.auth_file, server.serverPort, promptGuidance);
        process.env.SPLICE_PROBE_BEARER = env.SPLICE_PROBE_BEARER;
        const log = openSync(join(root, "daemon-output.log"), "w");
        try {
          launchAttempted = true;
          proc = compareSeams.Popen(["java", "-Xmx256m", `-Duser.home=${root}`, "-jar", artifact, "daemon"], { env, cwd: root, stdoutFd: log });
          const deadline = compareSeams.monotonic() + 20;
          for (;;) {
            const status = proc.poll();
            if (status !== null) {
              exitStatus = Number.isInteger(status) ? status : null;
              category = "daemon_exit";
              throw new ValueError("isolated daemon exited during startup");
            }
            let health: PyValue = null;
            let answered = false;
            try {
              health = await compareSeams.requestJson(control, "GET", "/health");
              answered = true;
            } catch (e) {
              if (!(isOSError(e) || isValueError(e))) throw e;
            }
            if (answered) {
              if (!(isPyObj(health) && typeof get(health, "ok") === "boolean" && isExactInt(get(health, "readyHeads")))) {
                category = "invalid_health_response";
                throw new ValueError("isolated daemon returned invalid startup health");
              }
              if (get(health, "ok") === true && (get(health, "readyHeads") as { __pyNum: string }).__pyNum === "2") break;
            }
            if (compareSeams.monotonic() >= deadline) {
              category = "readiness_timeout";
              throw new ValueError("isolated daemon did not become ready");
            }
            await compareSeams.sleep(0.1);
          }
          phase = "comparison";
          await compareSeams.runComparison({
            artifact, receipt, model: "gpt-6-astra",
            baseline_port: baseline, code_mode_port: codeMode, metrics_port: server.serverPort,
            prompt_guidance: promptGuidance,
          });
          phase = "complete";
          category = "complete";
        } finally {
          closeSync(log);
        }
      } finally {
        if (proc !== null) {
          // Prevent retries from starting while teardown drains existing work.
          budget.error = budget.error || "comparison ended";
          const [stoppedStatus, processCleanupError] = await stopProcess(proc);
          exitStatus = stoppedStatus !== null ? stoppedStatus : exitStatus;
          cleanupError = cleanupError ?? processCleanupError;
        }
      }
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  } catch (exc) {
    if (isOSError(exc) || isValueError(exc) || isKeyError(exc) || isTypeError(exc) || exc instanceof SubprocessError) {
      primaryError = exc as Error;
      if (phase === "startup" && category === "configuration_failure" && launchAttempted) category = "spawn_failure";
      else if (phase === "comparison") category = "comparison_failure";
    }
    throw exc;
  } finally {
    const serverCleanupError = await stopServer(server, loop);
    cleanupError = cleanupError ?? serverCleanupError;
    if (primaryError === null && cleanupError !== null) {
      phase = "cleanup";
      category = "cleanup_failure";
    }
    try {
      finalizeReceipt(receipt, artifactSha256, phase, category, exitStatus, budget);
    } catch (exc) {
      if (!(isOSError(exc) || isValueError(exc) || isTypeError(exc))) throw exc;
      receiptError = exc as Error;
    }
    if (previous === undefined) delete process.env.SPLICE_PROBE_BEARER;
    else process.env.SPLICE_PROBE_BEARER = previous;
    if (primaryError === null) {
      // eslint-disable-next-line no-unsafe-finally
      if (cleanupError !== null) throw cleanupError;
      // eslint-disable-next-line no-unsafe-finally
      if (receiptError !== null) throw receiptError;
    }
  }
}

/** The names the comparison suite replaces (mock.patch targets in the original). */
export const compareSeams = {
  Popen: popen as (argv: string[], opts: { env: Record<string, string>; cwd: string; stdoutFd: number }) => Proc,
  ThreadingHTTPServer: ((address: [string, number], methods: Methods) =>
    threadingHTTPServer(address[0], address[1], methods, "HTTP/1.1")) as (address: [string, number], methods: Methods) =>
    Promise<PyServer> | PyServer,
  requestJson,
  runComparison: runComparison as (args: Parameters<typeof runComparison>[0]) => Promise<void>,
  monotonic: (): number => performance.now() / 1000,
  sleep: (s: number): Promise<void> => Bun.sleep(s * 1000),
  Budget: (): Budget => new Budget(),
};

// =============================================================================================
// CLI — one verb, four arms. Every arm keeps the argparse flags its script declared, so a gate
// leg or a runbook line only changes its PREFIX. Nothing here calls process.exit: the status goes
// back to tools/e2e/index.ts. (`argparse` itself still exits 2 on a bad flag, which is CPython's
// parser.error contract and the behaviour these arms were proven against.)
// =============================================================================================

export const usage =
  "code-mode <probe|mock|guidance|compare> [flags]   " +
  "the code-mode bridge's harnesses: budget-proxy A/B probe, packaged-bridge mock, prompt-only guidance oracles, billed comparison";

/** `--selftest` per arm is that arm's bun test file; the status is what a shell would report. */
const SELFTEST = {
  probe: "code-mode.probe.test.ts",
  mock: "code-mode.mock.test.ts",
  guidance: "code-mode.guidance.test.ts",
} as const;

function selftest(arm: keyof typeof SELFTEST): number {
  const file = resolve(import.meta.dir, "../../test", SELFTEST[arm]);
  const child = Bun.spawnSync([process.execPath, "test", file], {
    cwd: findRepoRoot(import.meta.dir),
    stdio: ["inherit", "inherit", "inherit"],
  });
  return exitStatusOf(child);
}

/** The exit status for a harness failure — a fact about the RUN, never a verdict about the bridge.
 *  Same vocabulary as `e2e oracle`. */
export const HARNESS_EXIT = 2;

/** The fat jar is an INPUT to the mock and comparison arms, never built from here.
 *
 *  The gate builds it (clean check runs :app:shadowJar) and HOLDS THE GRADLE SLOT while these legs
 *  run, so a nested gradle from inside the command is the deadlock-or-double-build the slot exists
 *  to prevent. A missing jar therefore refuses BEFORE anything is spawned, read or written, with a
 *  message that names its producer — the same contract, word for word, as oracle.ts:505.
 */
function artifactPresent(artifact: string): boolean {
  if (existsSync(artifact)) return true;
  process.stderr.write(
    `HARNESS FAILURE: fat jar missing at ${artifact} — build it first ` +
      "(bun tools/gate slot <label> -- :app:shadowJar) or pass --artifact\n",
  );
  return false;
}

const PROBE_PROG = "e2e code-mode probe";
const PROBE_USAGE = `usage: ${PROBE_PROG} [-h] [--selftest] [--proxy-port PROXY_PORT]
                          [--baseline-port BASELINE_PORT]
                          [--code-mode-port CODE_MODE_PORT]
                          [--metrics-port METRICS_PORT] [--model MODEL]
                          [--artifact ARTIFACT] [--receipt RECEIPT]
`;
const PROBE_HELP = `${PROBE_USAGE}
Bounded, opt-in code-mode A/B probe. Fake client tools never touch files or
run commands. Run --proxy-port first and point BOTH isolated heads at that
loopback upstream, with WebSocket and request compression disabled. The proxy
caps actual vendor requests, including discovery/retry rounds; comparison mode
reads its counters between synthetic tasks. No credential, prompt, source, or
tool-output content is written to the receipt.

options:
  -h, --help            show this help message and exit
  --selftest
  --proxy-port PROXY_PORT
  --baseline-port BASELINE_PORT
  --code-mode-port CODE_MODE_PORT
  --metrics-port METRICS_PORT
  --model MODEL
  --artifact ARTIFACT
  --receipt RECEIPT
`;

async function probeArm(argv: string[]): Promise<number> {
  const a = argparse(argv, [
    { flag: "--selftest", kind: "true" },
    { flag: "--proxy-port", kind: "int" },
    { flag: "--baseline-port", kind: "int" },
    { flag: "--code-mode-port", kind: "int" },
    { flag: "--metrics-port", kind: "int" },
    { flag: "--model", kind: "str", dflt: "gpt-6-astra" },
    { flag: "--artifact", kind: "str" },
    { flag: "--receipt", kind: "str" },
  ], PROBE_PROG, PROBE_USAGE, PROBE_HELP);
  if (a.selftest) return selftest("probe");
  if (a.proxy_port) {
    const server = await threadingHTTPServer("127.0.0.1", a.proxy_port as number, proxyHandler(new Budget()), "HTTP/1.1");
    process.stdout.write(`Budget proxy listening on loopback:${server.serverPort}\n`);
    try {
      await server.serveForever();
    } finally {
      await server.serverClose();
    }
    return 0;
  }
  if (a.baseline_port && a.code_mode_port && a.metrics_port && a.artifact && a.receipt) {
    await runComparison(a as unknown as ComparisonArgs);
    return 0;
  }
  argparseError(PROBE_PROG, PROBE_USAGE, "choose --selftest, --proxy-port, or all comparison arguments");
}

const MOCK_PROG = "e2e code-mode mock";
const MOCK_USAGE = `usage: ${MOCK_PROG} [-h] [--selftest] [--artifact ARTIFACT]
                         [--receipt RECEIPT]
`;
const MOCK_HELP = `${MOCK_USAGE}
Exercise the packaged code-mode bridge through an isolated daemon and loopback
upstream. No real credentials or vendor calls. Client tools use the probe's
in-memory workspace.

options:
  -h, --help           show this help message and exit
  --selftest
  --artifact ARTIFACT
  --receipt RECEIPT
`;

async function mockArm(argv: string[]): Promise<number> {
  const a = argparse(argv, [
    { flag: "--selftest", kind: "true" },
    { flag: "--artifact", kind: "str" },
    { flag: "--receipt", kind: "str" },
  ], MOCK_PROG, MOCK_USAGE, MOCK_HELP);
  if (a.selftest) return selftest("mock");
  if (a.artifact && a.receipt) {
    if (!artifactPresent(a.artifact as string)) return HARNESS_EXIT;
    await runMock(a as unknown as { artifact: string; receipt: string });
    return 0;
  }
  argparseError(MOCK_PROG, MOCK_USAGE, "choose --selftest or both --artifact and --receipt");
}

const GUIDANCE_PROG = "e2e code-mode guidance";
const GUIDANCE_USAGE = `usage: ${GUIDANCE_PROG} [-h] [--selftest]\n`;
const GUIDANCE_HELP = `${GUIDANCE_USAGE}
Frozen synthetic repository and evidence oracles for the prompt-only A/B.

options:
  -h, --help  show this help message and exit
  --selftest
`;

function guidanceArm(argv: string[]): number {
  const a = argparse(argv, [{ flag: "--selftest", kind: "true" }], GUIDANCE_PROG, GUIDANCE_USAGE, GUIDANCE_HELP);
  if (a.selftest) return selftest("guidance");
  argparseError(GUIDANCE_PROG, GUIDANCE_USAGE, "choose --selftest");
}

const COMPARE_PROG = "e2e code-mode compare";
const COMPARE_USAGE = `usage: ${COMPARE_PROG} [-h] --artifact ARTIFACT --auth-file AUTH_FILE
                            --receipt RECEIPT [--prompt-guidance]
`;
const COMPARE_HELP = `${COMPARE_USAGE}
Run the explicitly approved bounded comparison in one disposable, isolated
daemon. Uses an existing ChatGPT auth file without modifying it. Only the
budget proxy may contact the vendor; tools are in-memory fixtures and receipts
contain aggregates. This consumes subscription quota: never add this runner to
the default gate.

options:
  -h, --help            show this help message and exit
  --artifact ARTIFACT
  --auth-file AUTH_FILE
  --receipt RECEIPT
  --prompt-guidance     enable code mode in both heads and vary only an
                        appended instruction section
`;

async function compareArm(argv: string[]): Promise<number> {
  const a = argparse(argv, [
    { flag: "--artifact", kind: "str", required: true },
    { flag: "--auth-file", kind: "str", required: true },
    { flag: "--receipt", kind: "str", required: true },
    { flag: "--prompt-guidance", kind: "true" },
  ], COMPARE_PROG, COMPARE_USAGE, COMPARE_HELP);
  if (!artifactPresent(a.artifact as string)) return HARNESS_EXIT;
  await runCompare(a as unknown as RunArgs);
  return 0;
}

const ARMS: Record<string, (argv: string[]) => number | Promise<number>> = {
  probe: probeArm,
  mock: mockArm,
  guidance: guidanceArm,
  compare: compareArm,
};

export async function codeMode(argv: readonly string[]): Promise<number> {
  const [arm, ...rest] = argv;
  const run = arm === undefined ? undefined : ARMS[arm];
  if (run === undefined) {
    process.stderr.write(
      `e2e code-mode: ${arm === undefined ? "no arm given" : `no such arm "${arm}"`} — ` +
        `expected one of ${Object.keys(ARMS).join(", ")}\n`,
    );
    return 2;
  }
  return await run(rest);
}
