#!/usr/bin/env bun
/** Bounded, opt-in code-mode A/B probe. Fake client tools never touch files or run commands.
 *
 *  Run --proxy-port first and point BOTH isolated heads at that loopback upstream, with
 *  WebSocket and request compression disabled. The proxy caps actual vendor requests,
 *  including discovery/retry rounds; comparison mode reads its counters between synthetic tasks.
 *  No credential, prompt, source, or tool-output content is written to the receipt.
 *
 *  V4-145: converted from code_mode_probe.py. Adaptations, each forced by the runtime:
 *
 *    SEAMS FOR mock.patch. The suite patched module globals (request_json, run_case,
 *    http.client.HTTPSConnection). ESM bindings are read-only, so those three are reached through
 *    `seams` and the tests swap entries on it, restoring them in `finally`. A module that imported
 *    one BY NAME in Python (compare, mock) holds the original function, exactly as it did there.
 *
 *    THE PROXY HANDLER IS ASYNC. do_POST awaits the upstream instead of blocking a thread; every
 *    budget mutation is synchronous between awaits, which is what the Budget lock guaranteed.
 *
 *    THE RECEIPT'S harness_sha256 names the .ts files. It hashes the harness that ran, and that is
 *    now code_mode_probe.ts / code_mode_compare.ts / code_mode_guidance.ts.
 */
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, openSync, readFileSync, readSync, closeSync, fstatSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { dumps, dumpsIndent, isPyObj, loadsBytes, obj, type PyObj, type PyValue } from "./pyjson.ts";
import { httpRequest, httpsConnection, threadingHTTPServer, type Methods, type Request, type UpstreamConnection } from "./pyhttp.ts";
import {
  argparse, argparseError, AttributeError, BadZipFile, check, cpCompare, get, hashKey, isHTTPException, isKeyError,
  isOSError, isTypeError, isValueError, iter, KeyError, OSError, osError, pyEq, pyIn, pyInt, pyName, pyRoundInt, runUnittest,
  setKey, sub, truthy, typeName, ValueError, type Tests,
} from "./pyshim.ts";

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
  while (a < z && ws(b[a])) a++;
  while (z > a && ws(b[z - 1])) z--;
  return b.subarray(a, z);
};
const startsWith = (b: Uint8Array, prefix: string) =>
  b.length >= prefix.length && [...prefix].every((ch, i) => b[i] === ch.charCodeAt(0));

export function proxyHandler(budget: Budget): Methods & { GET(h: ProxyRequest): void; POST(h: ProxyRequest): Promise<void> } {
  return {
    // Request and credential details must not enter logs: http.server's log_message is silenced,
    // and pyhttp never logs a request.
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
      const upstream: UpstreamConnection = seams.httpsConnection("chatgpt.com", 120);
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

export const TOOLS: PyValue[] = [
  definition("Read", "Read a named file in the synthetic workspace.", [["file_path", str]]),
  definition("Write", "Replace one synthetic workspace file.", [["file_path", str], ["content", str]]),
  definition("Agent", "Start a background arithmetic job. Its result arrives in a completion notification; do not poll.", [
    ["prompt", str],
  ]),
  definition("mcp__release__lookup", "Look up the next release version.", []),
  ...Array.from({ length: 8 }, (_, i) =>
    definition(`mcp__unrelated__operation_${i}`, "An unrelated integration, not needed by these tasks.", [])),
];

export const CASES: [string, string][] = [
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
export class Workspace {
  files = new Map<string, [PyValue, PyValue]>([
    ["settings.json", '{"timeout":10,"enabled":true}'], ["a.txt", "21"], ["b.txt", "34"], ["c.txt", "55"],
  ].map(([k, v]) => [hashKey(k), [k, v] as [PyValue, PyValue]]));
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

/** A prompt-only A/B scenario module (code_mode_guidance.ts). */
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
  const workspace = scenario ? new scenario.Workspace() : new Workspace();
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
        ["output_config", obj([["effort", "high"]])], ["tools", scenario ? scenario.TOOLS : TOOLS],
        ["messages", messages],
      ]);
      clientBytes += Buffer.byteLength(dumps(payload), "utf8");
      const response = await seams.requestJson(port, "POST", "/v1/messages", payload, headers);
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

/** ZipFile(path, "w").writestr(name, data) for each entry: stored, no compression. */
export function writeZip(path: string, entries: [string, string][]): void {
  const locals: Buffer[] = [];
  const centrals: Buffer[] = [];
  let offset = 0;
  for (const [name, text] of entries) {
    const data = Buffer.from(text, "utf8");
    const nameBytes = Buffer.from(name, "utf8");
    const crc = Bun.hash.crc32(data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBytes.length, 26);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(data.length, 20);
    central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(nameBytes.length, 28);
    central.writeUInt32LE(offset, 42);
    locals.push(local, nameBytes, data);
    centrals.push(central, nameBytes);
    offset += 30 + nameBytes.length + data.length;
  }
  const cd = Buffer.concat(centrals);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(entries.length, 8);
  eocd.writeUInt16LE(entries.length, 10);
  eocd.writeUInt32LE(cd.length, 12);
  eocd.writeUInt32LE(offset, 16);
  writeFileSync(path, Buffer.concat([...locals, cd, eocd]));
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
    scenario = (await import("./code_mode_guidance.ts")) as unknown as Scenario;
    experiment = obj([
      ["kind", "code-mode-prompt-guidance"], ["base_system", scenario.SYSTEM],
      ["appended_section", scenario.GUIDANCE],
      ["guidance_sha256", sha256(readFileSync(scenario.GUIDANCE_PATH))],
      ["fixture_sha256", sha256(sortKeysDump(obj(scenario.FILES)))],
      ["catalog_sha256", sha256(sortKeysDump(scenario.TOOLS))],
    ]);
  }
  const here = dirname(import.meta.path);
  setKey(experiment, "harness_sha256", obj(
    ["code_mode_probe.ts", "code_mode_compare.ts", ...(scenario ? ["code_mode_guidance.ts"] : [])]
      .map((name) => [name, sha256(readFileSync(join(here, name)))]),
  ));
  let failure: string | null = null;
  let accounting: PyValue = null;
  try {
    for (let repetition = 0; repetition < 2; repetition++) {
      for (const [caseName, prompt] of scenario ? scenario.CASES : CASES) {
        // Alternate ordering to reduce a consistent warm-cache/order advantage.
        const variants: [string, number][] = scenario
          ? [["existing", args.baseline_port], ["guided", args.code_mode_port]]
          : [["baseline", args.baseline_port], ["code_mode", args.code_mode_port]];
        for (const [variant, port] of repetition === 0 ? variants : [...variants].reverse()) {
          const before = await seams.requestJson(args.metrics_port, "GET", "/metrics");
          accounting = before;
          if (truthy(sub(before, "error")) || num(sub(before, "requests")) >= MAX_REQUESTS
            || num(sub(before, "input_tokens")) >= INPUT_BUDGET || num(sub(before, "output_tokens")) >= OUTPUT_BUDGET) {
            throw new ValueError("proxy budget halted");
          }
          const row = await seams.runCase(port, args.model, caseName, prompt, scenario,
            scenario && variant === "guided" ? scenario.GUIDANCE : "");
          setKey(row, "variant", variant);
          setKey(row, "repetition", int(repetition + 1));
          setKey(row, "accounting_complete", false);
          rows.push(row);
          const after = await seams.requestJson(args.metrics_port, "GET", "/metrics");
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
export const seams = {
  requestJson,
  runCase,
  httpsConnection: httpsConnection as (host: string, timeoutS: number) => UpstreamConnection,
};

// ---------------------------------------------------------------------------------------------
// ProbeTests
// ---------------------------------------------------------------------------------------------

/** A handler double: the MagicMock the original built, with the attributes do_POST touches. */
function fakeHandler(body: Uint8Array, onWrite?: (chunk: Uint8Array) => void) {
  const map = new Map<string, string>([["Content-Length", String(body.length)], ["Authorization", "Bearer synthetic-upstream"]]);
  let offset = 0;
  const h = {
    path: "/responses",
    closeConnection: false,
    headers: { get: (k: string, d: string | null = null) => map.get(k) ?? d, items: () => [...map.entries()] as [string, string][] },
    rfile: { read: (n: number) => { const out = body.subarray(offset, offset + n); offset += out.length; return out; } },
    writes: [] as Uint8Array[],
    sendErrorCalls: 0,
    wfile: { write: (c: Uint8Array | string) => { const b = typeof c === "string" ? Buffer.from(c) : c; h.writes.push(b); onWrite?.(b); }, flush: () => {} },
    sendResponse: () => {},
    sendHeader: () => {},
    endHeaders: () => {},
    sendError: () => {
      h.sendErrorCalls++;
    },
  };
  return h;
}
/** HTTPSConnection double whose response.read1 yields `chunks` in order. */
function fakeUpstream(chunks: Uint8Array[]) {
  const calls = { connect: 0 };
  const factory = () => {
    calls.connect++;
    const queue = [...chunks];
    return {
      request: () => {},
      getresponse: async () => ({
        status: 200,
        getheader: () => "text/event-stream",
        read1: async () => {
          const next = queue.shift();
          if (next === undefined) throw new Error("StopIteration");
          return next;
        },
        read: async () => new Uint8Array(0),
      }),
      close: () => {},
    };
  };
  return { factory, calls };
}
const enc = (s: string) => Buffer.from(s, "utf8");
const P = (v: unknown): PyValue => fromPlain(v);
function fromPlain(v: unknown): PyValue {
  if (v === null || v === undefined) return null;
  if (typeof v === "boolean" || typeof v === "string") return v;
  if (typeof v === "number") return { __pyNum: String(v), isFloat: !Number.isInteger(v) };
  if (Array.isArray(v)) return v.map(fromPlain);
  return obj(Object.entries(v as Record<string, unknown>).map(([k, x]) => [k, fromPlain(x)]));
}
async function withTempDir<T>(fn: (dir: string) => Promise<T> | T): Promise<T> {
  const { mkdtempSync, rmSync } = await import("node:fs");
  const { tmpdir } = await import("node:os");
  const dir = mkdtempSync(join(tmpdir(), "tmp"));
  try {
    return await fn(dir);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}
const snapGet = (b: Budget, k: string) => get(b.snapshot(), k);

export const tests: Tests = {
  async test_prompt_only_experiment_rejects_auto_injection_before_launch() {
    const compare = await import("./code_mode_compare.ts");
    await withTempDir(async (directory) => {
      const artifact = join(directory, "app.jar");
      writeZip(artifact, [["splice/provider/codex/code-mode-orchestration.txt", "synthetic guidance"]]);
      const args = { artifact, prompt_guidance: true } as never;
      let launched = 0;
      let served = 0;
      const old = { ...compare.seams };
      try {
        compare.seams.Popen = (() => { launched++; throw new Error("launched"); }) as never;
        compare.seams.ThreadingHTTPServer = (() => { served++; throw new Error("served"); }) as never;
        await check.raises((e) => e instanceof ValueError, () => compare.run(args), /adds guidance automatically/);
        check.equal(0, launched);
        check.equal(0, served);
      } finally {
        Object.assign(compare.seams, old);
      }
      writeZip(artifact, [["legacy.txt", "no automatic guidance"]]);
      validatePromptExperiment(args);
    });
  },

  async test_guidance_comparison_enables_identical_provider_configs() {
    const { configure } = await import("./code_mode_compare.ts");
    await withTempDir(async (root) => {
      const source = join(root, "source-auth.json");
      writeFileSync(source, '{"tokens":{"access_token":"synthetic"}}');
      const [env] = await configure(root, source, 12345, true);
      const providers = (Bun.TOML.parse(readFileSync(env.SPLICE_CONFIG, "utf8")) as Record<string, Record<string, Record<string, unknown>>>).providers;
      check.equal(providers.baseline, providers.code_mode);
      check.true((providers.baseline.quirks as Record<string, unknown>).code_mode);
    });
  },

  async test_guidance_only_appends_system_text_and_records_real_callback_ids() {
    const scenario = (await import("./code_mode_guidance.ts")) as unknown as Scenario;
    const captured: PyObj[] = [];
    const [caseName, prompt] = scenario.CASES[1];
    const reply = async (_port: number, _method: string, _path: string, body?: PyValue) => {
      captured.push(loadsBytes(Buffer.from(dumps(body as PyValue))) as PyObj);
      if (captured.length % 2) {
        return P({ content: [{ type: "tool_use", id: "toolu_splice_test", name: "LSP", input: {
          operation: "findReferences", file_path: "app/limits.py", symbol: "build_client",
        } }] });
      }
      const { CONTRACTS } = await import("./code_mode_guidance.ts");
      return obj([["content", [obj([["type", "text"], ["text", dumps(CONTRACTS[caseName])]])]], ["stop_reason", "end_turn"]]);
    };
    for (const suffix of ["", scenario.GUIDANCE]) {
      const old = seams.requestJson;
      seams.requestJson = reply as never;
      let row: PyObj;
      try {
        row = await runCase(1, "gpt-6-astra", caseName, prompt, scenario, suffix);
      } finally {
        seams.requestJson = old;
      }
      check.true(get(row, "passed"), dumps(row));
      check.equal(1, get(row, "bridge_callbacks"));
      check.equal(1, get(row, "bridge_callback_batches"));
      check.equal(P({ LSP: 1 }), get(row, "tool_counts"));
    }
    const pop = (o: PyObj, k: string) => {
      const v = get(o, k);
      return [v, obj(o.__pyObj.filter(([key]) => key !== k))] as const;
    };
    const [existingSystem, existing] = pop(captured[0], "system");
    const [guidedSystem, guided] = pop(captured[2], "system");
    check.equal(scenario.SYSTEM, existingSystem);
    check.equal(scenario.SYSTEM + "\n\n" + scenario.GUIDANCE, guidedSystem);
    check.equal(existing, guided);
  },

  test_upstream_shape_counts_guidance_and_script_declaration_separately() {
    const budget = new Budget();
    const payload = P({ input: [{ type: "additional_tools", tools: [{ type: "custom", name: "splice_exec" }] }] }) as PyObj;
    budget.recordShape(payload);
    (get(payload, "input") as PyValue[]).push(P({ role: "developer", content: "<code_mode_orchestration>guide</code_mode_orchestration>" }));
    budget.recordShape(payload);
    check.equal(2, snapGet(budget, "code_tool_requests"));
    check.equal(1, snapGet(budget, "guidance_requests"));
  },

  async test_isolated_comparison_config_changes_only_bridge_policy() {
    const { configure } = await import("./code_mode_compare.ts");
    const { statSync } = await import("node:fs");
    await withTempDir(async (root) => {
      const source = join(root, "source-auth.json");
      writeFileSync(source, '{"tokens":{"access_token":"synthetic"}}');
      const [env, control, baseline, codeMode] = await configure(root, source, 12345);
      const config = Bun.TOML.parse(readFileSync(env.SPLICE_CONFIG, "utf8")) as Record<string, Record<string, Record<string, Record<string, unknown>>>>;
      const providers = config.providers;
      const baseQuirks = providers.baseline.quirks as Record<string, unknown>;
      const codeQuirks = providers.code_mode.quirks as Record<string, unknown>;
      check.false(baseQuirks.code_mode);
      delete baseQuirks.code_mode;
      check.true(codeQuirks.code_mode);
      delete codeQuirks.code_mode;
      check.equal(providers.baseline, providers.code_mode);
      check.true((baseQuirks.tool_surface as Record<string, unknown>).enabled);
      check.true(baseQuirks.account_id_header);
      check.equal("high", (config.daemon as unknown as Record<string, unknown>).effort);
      check.equal(3, new Set([control, baseline, codeMode]).size);
      check.equal(readFileSync(source).toString("latin1"), readFileSync(join(root, "auth.json")).toString("latin1"));
      check.equal(0o600, statSync(join(root, "auth.json")).mode & 0o777);
      check.true(env.CODEX_OAUTH_TOKEN_URL.startsWith("http://127.0.0.1:"));
      check.equal("off", env.CLAUDEX_QUOTA_POLL);
    });
  },

  async test_isolated_comparison_tears_down_without_vendor_requests() {
    const compare = await import("./code_mode_compare.ts");
    await withTempDir(async (root) => {
      const [artifact, auth, receipt] = ["app.jar", "auth.json", "receipt.json"].map((n) => join(root, n));
      writeFileSync(artifact, "synthetic jar");
      writeFileSync(auth, '{"tokens":{"access_token":"synthetic"}}');
      const args = { artifact, auth_file: auth, receipt };
      const previous = process.env.SPLICE_PROBE_BEARER;
      const launch = { terminate: 0, waits: [] as (number | undefined)[] };
      const old = { ...compare.seams };
      try {
        compare.seams.runComparison = (async () => {
          writeFileSync(receipt, '{"runs":[],"failure":null}');
        }) as never;
        compare.seams.requestJson = (async () => P({ ok: true, readyHeads: 2 })) as never;
        compare.seams.Popen = (() => ({
          pid: 1, poll: () => null, kill: () => {},
          terminate: () => { launch.terminate++; },
          wait: (timeout?: number) => { launch.waits.push(timeout); return null; },
        })) as never;
        await compare.run(args);
      } finally {
        Object.assign(compare.seams, old);
      }
      check.equal(1, launch.terminate);
      check.equal([10], launch.waits);
      check.equal(previous, process.env.SPLICE_PROBE_BEARER);
      const saved = loadsBytes(readFileSync(receipt));
      check.equal(0, get(get(saved, "final_accounting"), "requests"));
      check.isNone(get(get(saved, "final_accounting"), "error"));
      check.equal('{"tokens":{"access_token":"synthetic"}}', readFileSync(auth, "utf8"));
    });
  },

  async test_terminal_usage_is_recorded_before_forwarding() {
    const budget = new Budget();
    const body = enc(dumps(P({ model: "gpt-6-astra", reasoning: { effort: "high" } })));
    const terminal = enc("data: " + dumps(P({ type: "response.completed", response: {
      usage: { input_tokens: INPUT_BUDGET, output_tokens: 2 }, output: [],
    } })) + "\n\n");
    const handler = fakeHandler(body, (chunk) => {
      check.equal(Buffer.from(terminal).toString("latin1"), Buffer.from(chunk).toString("latin1"));
      check.equal(INPUT_BUDGET, snapGet(budget, "input_tokens"));
      check.false(budget.reserve(1));
    });
    const up = fakeUpstream([terminal, new Uint8Array(0)]);
    const old = seams.httpsConnection;
    seams.httpsConnection = up.factory as never;
    try {
      await proxyHandler(budget).POST(handler);
    } finally {
      seams.httpsConnection = old;
    }
    check.equal(1, handler.writes.length);
    check.equal(INPUT_BUDGET, snapGet(budget, "input_tokens"));
    check.equal(2, snapGet(budget, "output_tokens"));
  },

  async test_streamed_calls_count_once_with_sparse_or_repeated_terminal_output() {
    const items = [
      { type: "custom_tool_call", id: "script-item", call_id: "script", name: "splice_exec" },
      { type: "tool_search_call", id: "search-item", call_id: "search" },
    ];
    for (const terminalOutput of [[], items]) {
      const budget = new Budget();
      const events: unknown[] = [];
      items.forEach((item, index) => {
        for (const kind of ["response.output_item.added", "response.output_item.done"]) {
          events.push({ type: kind, output_index: index, item });
        }
      });
      events.push({ type: "response.completed", response: { usage: { input_tokens: 100, output_tokens: 10 }, output: terminalOutput } });
      const chunks = [...events.map((e) => enc("data: " + dumps(P(e)) + "\n\n")), new Uint8Array(0)];
      const body = enc(dumps(P({ model: "gpt-6-astra", reasoning: { effort: "high" } })));
      // Dedupe is response-local: a later response may reuse an item identifier.
      for (const expected of [1, 2]) {
        const handler = fakeHandler(body);
        const up = fakeUpstream(chunks);
        const old = seams.httpsConnection;
        seams.httpsConnection = up.factory as never;
        try {
          await proxyHandler(budget).POST(handler);
        } finally {
          seams.httpsConnection = old;
        }
        check.equal(expected, snapGet(budget, "code_calls"), `terminal_output=${terminalOutput.length > 0}`);
        check.equal(expected, snapGet(budget, "search_calls"), `terminal_output=${terminalOutput.length > 0}`);
      }
    }
  },

  async test_other_model_or_effort_is_rejected_before_vendor_dispatch() {
    for (const payload of [[1], { model: "other" }, { model: "gpt-6-astra", reasoning: null },
      { model: "gpt-6-astra", reasoning: { effort: "low" } }]) {
      const budget = new Budget();
      const handler = fakeHandler(enc(dumps(P(payload))));
      const up = fakeUpstream([]);
      const old = seams.httpsConnection;
      seams.httpsConnection = up.factory as never;
      try {
        await proxyHandler(budget).POST(handler);
        check.equal(0, up.calls.connect, `payload=${JSON.stringify(payload)}`);
      } finally {
        seams.httpsConnection = old;
      }
      check.equal(0, snapGet(budget, "requests"));
      check.equal(1, handler.sendErrorCalls);
    }
  },

  test_request_budget_is_enforced_before_dispatch() {
    const budget = new Budget();
    for (let i = 0; i < MAX_REQUESTS; i++) check.true(budget.reserve(10));
    check.false(budget.reserve(10));
    check.equal(MAX_REQUESTS, snapGet(budget, "requests"));
  },

  test_missing_usage_stops_future_calls() {
    const budget = new Budget();
    budget.finish(null);
    check.false(budget.reserve(1));
  },

  test_token_threshold_stops_future_calls() {
    const budget = new Budget();
    budget.finish(P({ input_tokens: INPUT_BUDGET, output_tokens: 0 }));
    check.false(budget.reserve(1));
  },

  test_incomplete_usage_stops_future_calls() {
    const budget = new Budget();
    budget.finish(P({ input_tokens: 1 }));
    check.false(budget.reserve(1));
  },

  test_invalid_cache_usage_halts_but_keeps_known_token_spend() {
    for (const details of [null, { cached_tokens: -1 }, { cached_tokens: "1" }, { cached_tokens: 11 }]) {
      const budget = new Budget();
      budget.finish(P({ input_tokens: 10, output_tokens: 2, input_tokens_details: details }));
      check.false(budget.reserve(1), `details=${JSON.stringify(details)}`);
      check.equal(10, snapGet(budget, "input_tokens"));
      check.equal(2, snapGet(budget, "output_tokens"));
    }
  },

  test_in_flight_usage_is_accounted_after_halt() {
    const budget = new Budget();
    budget.finish(null);
    budget.finish(P({ input_tokens: 10, output_tokens: 2 }));
    check.false(budget.reserve(1));
    check.equal(10, snapGet(budget, "input_tokens"));
  },

  async test_metrics_failure_retains_executed_case_in_receipt() {
    await withTempDir(async (directory) => {
      const artifact = join(directory, "synthetic.jar");
      writeFileSync(artifact, "synthetic artifact");
      const receipt = join(directory, "receipt.json");
      const args: ComparisonArgs = { artifact, receipt, model: "gpt-6-astra", baseline_port: 1, code_mode_port: 2, metrics_port: 3 };
      const responses: (PyValue | Error)[] = [new Budget().snapshot(), new OSError("private detail")];
      const old = { ...seams };
      try {
        seams.requestJson = (async () => {
          const next = responses.shift();
          if (next instanceof Error) throw next;
          return next;
        }) as never;
        seams.runCase = (async () => obj([["passed", true]])) as never;
        await check.raises((e) => e instanceof OSError, () => runComparison(args));
      } finally {
        Object.assign(seams, old);
      }
      const saved = loadsBytes(readFileSync(receipt));
      check.equal(1, get(saved, "completed_runs"));
      check.false(get((get(saved, "runs") as PyValue[])[0], "accounting_complete"));
      check.equal("OSError", get(saved, "failure"));
      check.notIn("private detail", readFileSync(receipt, "utf8"));
    });
  },

  test_repeated_read_cannot_replace_missing_file_evidence() {
    const workspace = new Workspace();
    for (let i = 0; i < 3; i++) workspace.execute({ name: "Read", input: P({ file_path: "a.txt" }) });
    check.false(workspace.correct("independent-reads", "110"));
    for (const path of ["b.txt", "c.txt"]) workspace.execute({ name: "Read", input: P({ file_path: path }) });
    check.true(workspace.correct("independent-reads", "The sum is 110."));
  },

  async test_tools_are_fake_and_unknown_operations_fail() {
    const workspace = new Workspace();
    check.equal("21", workspace.execute({ name: "Read", input: P({ file_path: "a.txt" }) }));
    await check.raises((e) => e instanceof ValueError, () => workspace.execute({ name: "Bash", input: P({ command: "anything" }) }));
  },

  async test_failed_request_is_a_failed_row_without_exception_content() {
    const old = seams.requestJson;
    seams.requestJson = (async () => {
      throw new ValueError("private vendor content");
    }) as never;
    let row: PyObj;
    try {
      row = await runCase(1, "gpt-6-astra", ...CASES[0]);
    } finally {
      seams.requestJson = old;
    }
    check.false(get(row, "passed"));
    check.equal("ValueError", get(row, "error"));
    check.notIn("private vendor content", dumps(row));
    check.equal(1, get(row, "client_requests"));
  },

  async test_invalid_tool_is_counted_in_failed_row() {
    const response = P({ content: [{ type: "tool_use", id: "call-1", name: "Bash", input: {} }] });
    const old = seams.requestJson;
    seams.requestJson = (async () => response) as never;
    let row: PyObj;
    try {
      row = await runCase(1, "gpt-6-astra", ...CASES[0]);
    } finally {
      seams.requestJson = old;
    }
    check.false(get(row, "passed"));
    check.equal(1, get(row, "tool_calls"));
    check.equal(1, get(row, "failed_tool_calls"));
  },

  test_repeat_counts_use_arguments_not_only_tool_names() {
    const workspace = new Workspace();
    for (const path of ["a.txt", "b.txt", "a.txt"]) workspace.execute({ name: "Read", input: P({ file_path: path }) });
    check.equal(1, workspace.repeated_calls);
  },

  test_notification_is_not_a_poll() {
    const workspace = new Workspace();
    workspace.execute({ name: "Agent", input: P({ prompt: "7 times 8" }) });
    check.isNotNone(workspace.notification);
    check.true((workspace.notification ?? "").includes("56"));
    check.true(workspace.correct("background-result", "56"));
  },
};

const PROG = "code_mode_probe.ts";
const USAGE = `usage: ${PROG} [-h] [--selftest] [--proxy-port PROXY_PORT]
                          [--baseline-port BASELINE_PORT]
                          [--code-mode-port CODE_MODE_PORT]
                          [--metrics-port METRICS_PORT] [--model MODEL]
                          [--artifact ARTIFACT] [--receipt RECEIPT]
`;
const HELP = `${USAGE}
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

if (import.meta.main) {
  const a = argparse(process.argv.slice(2), [
    { flag: "--selftest", kind: "true" },
    { flag: "--proxy-port", kind: "int" },
    { flag: "--baseline-port", kind: "int" },
    { flag: "--code-mode-port", kind: "int" },
    { flag: "--metrics-port", kind: "int" },
    { flag: "--model", kind: "str", dflt: "gpt-6-astra" },
    { flag: "--artifact", kind: "str" },
    { flag: "--receipt", kind: "str" },
  ], PROG, USAGE, HELP);
  if (a.selftest) process.exit(await runUnittest("code_mode_probe", "ProbeTests", tests));
  else if (a.proxy_port) {
    const server = await threadingHTTPServer("127.0.0.1", a.proxy_port as number, proxyHandler(new Budget()), "HTTP/1.1");
    process.stdout.write(`Budget proxy listening on loopback:${server.serverPort}\n`);
    try {
      await server.serveForever();
    } finally {
      await server.serverClose();
    }
  } else if (a.baseline_port && a.code_mode_port && a.metrics_port && a.artifact && a.receipt) {
    await runComparison(a as unknown as ComparisonArgs);
  } else argparseError(PROG, USAGE, "choose --selftest, --proxy-port, or all comparison arguments");
}
