/**
 * `e2e conformance [--artifact JAR] [--class NAME] [--json OUT] [--keep]` — THE CLAUDE CODE CONFORMANCE SUITE (V4-282).
 *
 * WHAT  Boots the REAL daemon (fat jar) with one head per dialect x system_prompt_mode, every head pointed at ONE
 *       loopback fake upstream, drives every class of message a Claude Code session depends on into every head, and
 *       reads what reached the upstream:
 *
 *           fixtures/conformance/<class>.json ──▶ [ head: dialect x mode ] ──▶ fake upstream (records the request)
 *
 *       CLASSES: background-subagent task notifications, SessionStart hook context, cross-session messages, the date
 *       line and <total_tokens> notices (each a mid-conversation role=system message, in the three shapes Claude Code
 *       2.1.283 sends: bare string, text blocks with cache_control, string + clear_at), MCP tools with free-form
 *       input_schema, and reasoning blocks. V4-277 is the defect this leg exists to catch: a replace-mode chat head
 *       dropped every role=system message, so a subagent's result never reached the model.
 *
 * CELLS Every class x dialect x mode is one cell and every one is asserted. A second row per dialect x mode reads the
 *       system seam itself (append keeps the client's text and adds the head's, replace swaps it, strip deletes the
 *       paragraphs a pattern matches), because a mode that quietly stopped applying is the same class of failure.
 *
 * CENSUS The denominators come from the SOURCE, never from this file's own lists: the dialects are the wire names of
 *       `enum class Dialect` and the modes those of `enum class SystemPromptMode`. A dialect with no harness row, a
 *       mode with no head prompt or seam expectation, a class with no checks for a dialect, or a fixture the manifest
 *       does not list each fail BY NAME before anything boots.
 *
 * NO NETWORK, NO QUOTA, NO PRIVATE DATA: the upstream is a loopback server in this process, auth is synthetic, state is
 *       a temp directory, and the corpus is invented text shaped on the client's real requests.
 *
 * EXIT  0 = every cell passes; 1 = at least one cell fails; 2 = harness failure (census, missing jar, boot failure) —
 *       NOT a verdict about the gateway.
 */
import http from "node:http";
import net from "node:net";
import { spawn } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { layout } from "../../../gate/src/lib/repo.ts";
import { daemonEnv } from "../daemon-env.ts";

export const usage =
  "conformance [--artifact JAR] [--class NAME] [--json OUT] [--keep]   " +
  "every Claude Code message class through every dialect x system_prompt_mode against a fake upstream";

export const CONFORMANCE_DIR = resolve(import.meta.dir, "../../../../fixtures/conformance");
export const DEFAULT_JAR = "app/build/libs/app-all.jar";
export const HARNESS_EXIT = 2;
export const DIVERGENCE_EXIT = 1;
const MANIFEST_NAME = "_manifest.json";
const REQUEST_TIMEOUT_MS = 30_000;

class HarnessError extends Error {}

export type Json = null | boolean | number | string | Json[] | { [k: string]: Json };
type Obj = { [k: string]: Json };

// ── the corpus ──────────────────────────────────────────────────────────────────────────────────────────────────────
export type Check =
  | { kind: "item"; role: string; text: string }
  | { kind: "order"; texts: string[] }
  | { kind: "tool"; name: string; schema: Json; keys?: string[] }
  | { kind: "toolcall"; name: string; input: Json }
  | { kind: "thinking"; text: string; signature: string }
  | { kind: "redacted"; data: string }
  | { kind: "absent"; text: string };

interface SeamRow {
  present: string[];
  absent: string[];
}
export interface Manifest {
  shapedOn: string;
  classes: string[];
  clientSystem: string;
  headPrompts: Record<string, string>;
  systemSeam: Record<string, SeamRow>;
}
export interface ClassFixture {
  class: string;
  request: Obj;
  expect: Record<string, Check[]>;
}
export interface Corpus {
  manifest: Manifest;
  fixtures: Record<string, ClassFixture>;
  /** fixture files in the directory, listed or not: the census compares them to the manifest both ways. */
  files: string[];
}

/** A string that is exactly `@id` is replaced by carriers[id], anywhere in the request or the checks. */
function resolveCarriers(value: Json, carriers: Record<string, string>): Json {
  if (typeof value === "string") return value.startsWith("@") && value.slice(1) in carriers ? carriers[value.slice(1)]! : value;
  if (Array.isArray(value)) return value.map((v) => resolveCarriers(v, carriers));
  if (value !== null && typeof value === "object") {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, resolveCarriers(v, carriers)]));
  }
  return value;
}

export function loadCorpus(dir: string = CONFORMANCE_DIR): Corpus {
  const manifestPath = join(dir, MANIFEST_NAME);
  if (!existsSync(manifestPath)) throw new HarnessError(`no ${MANIFEST_NAME} in ${dir}`);
  const manifest = JSON.parse(readFileSync(manifestPath, "utf8")) as Manifest;
  const files = readdirSync(dir).filter((f) => f.endsWith(".json") && f !== MANIFEST_NAME).map((f) => f.replace(/\.json$/, "")).sort();
  const fixtures: Record<string, ClassFixture> = {};
  for (const name of files) {
    const raw = JSON.parse(readFileSync(join(dir, `${name}.json`), "utf8")) as ClassFixture & { carriers?: Record<string, string> };
    fixtures[name] = resolveCarriers(raw as unknown as Json, raw.carriers ?? {}) as unknown as ClassFixture;
  }
  return { manifest, fixtures, files };
}

// ── the census: denominators from the source ────────────────────────────────────────────────────────────────────────
/** The wire names an `enum class <name>` declares with @SerialName, read from the Kotlin source. */
export function sourceEnumWires(source: string, name: string): string[] {
  const body = new RegExp(`enum class ${name}\\b[^{]*\\{([\\s\\S]*?)\\n\\}`).exec(source)?.[1];
  if (body === undefined) throw new HarnessError(`enum class ${name} not found in the source the census reads`);
  return [...body.matchAll(/@SerialName\("([^"]+)"\)/g)].map((m) => m[1]!);
}

export function sourceDialects(root: string = layout().repoRoot): string[] {
  return sourceEnumWires(readFileSync(join(root, "core/src/main/kotlin/splice/core/topology/TopologySchema.kt"), "utf8"), "Dialect");
}

export function sourceModes(root: string = layout().repoRoot): string[] {
  return sourceEnumWires(readFileSync(join(root, "core/src/main/kotlin/splice/core/prompt/HeadSystemPrompt.kt"), "utf8"), "SystemPromptMode");
}

/** Every way the corpus and the harness can fail to cover the source's dialects and modes; empty = complete. */
export function census(corpus: Corpus, dialects: string[], modes: string[], harnessDialects: string[]): string[] {
  const problems: string[] = [];
  for (const d of dialects) if (!harnessDialects.includes(d)) problems.push(`dialect ${d} has no harness row (no provider, stream or head builder)`);
  for (const m of modes) {
    if (!corpus.manifest.headPrompts[m]) problems.push(`mode ${m} has no head prompt in the manifest`);
    if (!corpus.manifest.systemSeam[m]) problems.push(`mode ${m} has no system seam expectation in the manifest`);
  }
  for (const c of corpus.manifest.classes) {
    const fx = corpus.fixtures[c];
    if (!fx) {
      problems.push(`class ${c} is listed in the manifest and has no fixture file`);
      continue;
    }
    for (const d of dialects) if ((fx.expect?.[d] ?? []).length === 0) problems.push(`class ${c} has no checks for dialect ${d}`);
  }
  for (const f of corpus.files) if (!corpus.manifest.classes.includes(f)) problems.push(`fixture ${f}.json is not listed in the manifest`);
  return problems;
}

// ── grading: what the upstream request must look like ───────────────────────────────────────────────────────────────
const isObj = (v: unknown): v is Obj => v !== null && typeof v === "object" && !Array.isArray(v);
const list = (v: Json | undefined): Json[] => (Array.isArray(v) ? v : []);

/** The conversation array on each wire: Anthropic and chat `messages`, Responses `input`. */
export function conversation(dialect: string, body: Json): Obj[] {
  const key = dialect === "openai-responses" ? "input" : "messages";
  return list(isObj(body) ? body[key] : undefined).filter(isObj);
}

/** An item's text: a bare string content, or its text parts joined (thinking and data parts carry none). */
export function itemText(item: Obj): string {
  const c = item["content"];
  if (typeof c === "string") return c;
  return list(c).map((p) => (typeof p === "string" ? p : isObj(p) && typeof p["text"] === "string" ? p["text"] : "")).join("");
}

function canonical(v: Json): string {
  if (Array.isArray(v)) return `[${v.map(canonical).join(",")}]`;
  if (isObj(v)) return `{${Object.keys(v).sort().map((k) => `${JSON.stringify(k)}:${canonical(v[k]!)}`).join(",")}}`;
  return JSON.stringify(v);
}

function strings(v: Json, out: string[] = []): string[] {
  if (typeof v === "string") out.push(v);
  else if (Array.isArray(v)) v.forEach((x) => strings(x, out));
  else if (isObj(v)) Object.values(v).forEach((x) => strings(x, out));
  return out;
}

/** Tool definitions on each wire, namespaces flattened: [name, schema]. */
function toolDefinitions(dialect: string, body: Json): Array<[string, Json | undefined]> {
  const tools = list(isObj(body) ? body["tools"] : undefined).filter(isObj);
  const flat = tools.flatMap((t) => (t["type"] === "namespace" ? list(t["tools"]).filter(isObj) : [t]));
  return flat.map((t): [string, Json | undefined] => {
    if (dialect === "anthropic-passthrough") return [String(t["name"]), t["input_schema"]];
    if (dialect === "openai-chat") return [String((t["function"] as Obj | undefined)?.["name"]), (t["function"] as Obj | undefined)?.["parameters"]];
    return [String(t["name"]), t["parameters"]];
  });
}

/** The tool calls in the conversation on each wire: [name, input]. */
function toolCalls(dialect: string, body: Json): Array<[string, Json]> {
  const parse = (s: Json | undefined): Json => {
    try {
      return JSON.parse(String(s)) as Json;
    } catch {
      return String(s);
    }
  };
  return conversation(dialect, body).flatMap((m): Array<[string, Json]> => {
    if (dialect === "anthropic-passthrough") {
      return list(m["content"]).filter(isObj).filter((b) => b["type"] === "tool_use").map((b): [string, Json] => [String(b["name"]), b["input"] ?? null]);
    }
    if (dialect === "openai-chat") {
      return list(m["tool_calls"]).filter(isObj).map((c): [string, Json] => [String((c["function"] as Obj)["name"]), parse((c["function"] as Obj)["arguments"])]);
    }
    return m["type"] === "function_call" ? [[String(m["name"]), parse(m["arguments"])]] : [];
  });
}

export function gradeCheck(dialect: string, body: Json, check: Check): string | null {
  const items = conversation(dialect, body);
  switch (check.kind) {
    case "item": {
      const hits = items.filter((i) => i["role"] === check.role && itemText(i) === check.text);
      if (hits.length === 1) return null;
      const elsewhere = items.filter((i) => itemText(i).includes(check.text.slice(0, 40)) && i["role"] !== check.role).map((i) => i["role"]);
      const seen = hits.length === 0 && elsewhere.length > 0 ? `; the text arrived under role ${elsewhere.join(",")}` : "";
      return `expected exactly one ${check.role} item with the whole text ${JSON.stringify(check.text.slice(0, 60))}, found ${hits.length}${seen}`;
    }
    case "order": {
      const at = check.texts.map((t) => items.findIndex((i) => itemText(i).includes(t)));
      const missing = check.texts.filter((_, n) => at[n] === -1);
      if (missing.length > 0) return `not delivered: ${missing.map((t) => JSON.stringify(t.slice(0, 40))).join(", ")}`;
      return at.every((p, n) => n === 0 || p > at[n - 1]!) ? null : `delivered out of order (positions ${at.join(",")})`;
    }
    case "tool": {
      const defs = toolDefinitions(dialect, body).filter(([n]) => n === check.name);
      if (defs.length !== 1) return `expected one tool ${check.name}, found ${defs.length}`;
      // `keys` narrows the comparison to the schema's own top-level keys: the Responses dialect re-serializes every
      // client schema the way codex-rs does (ToolSchemaNormalize.kt), so only the constraints that make a tool
      // free-form (its type and additionalProperties) are compared there; every other dialect compares whole.
      const sent = defs[0]![1] ?? null;
      const pick = (v: Json) => (check.keys && isObj(v) ? Object.fromEntries(check.keys.filter((k) => k in v).map((k) => [k, v[k]!])) : v);
      return canonical(pick(sent)) === canonical(pick(check.schema)) ? null : `tool ${check.name}'s schema changed: ${canonical(pick(sent)).slice(0, 100)}`;
    }
    case "toolcall": {
      const calls = toolCalls(dialect, body).filter(([n]) => n === check.name);
      if (calls.length !== 1) return `expected one call to ${check.name}, found ${calls.length}`;
      return canonical(calls[0]![1]) === canonical(check.input) ? null : `call to ${check.name} changed its input: ${canonical(calls[0]![1]).slice(0, 100)}`;
    }
    case "thinking": {
      const blocks = items.flatMap((i) => list(i["content"]).filter(isObj)).filter((b) => b["type"] === "thinking");
      return blocks.some((b) => b["thinking"] === check.text && b["signature"] === check.signature) ? null : "the thinking block did not come back byte for byte";
    }
    case "redacted": {
      const blocks = items.flatMap((i) => list(i["content"]).filter(isObj)).filter((b) => b["type"] === "redacted_thinking");
      return blocks.some((b) => b["data"] === check.data) ? null : "the redacted_thinking block did not come back byte for byte";
    }
    case "absent":
      return strings(body).some((s) => s.includes(check.text)) ? `${JSON.stringify(check.text)} reached the upstream` : null;
  }
}

/** Every failing check of a class in one cell, each named by kind. */
export function gradeChecks(dialect: string, body: Json, checks: Check[]): string[] {
  return checks.flatMap((c, n) => {
    const why = gradeCheck(dialect, body, c);
    return why === null ? [] : [`check ${n + 1} (${c.kind}): ${why}`];
  });
}

/** The system text on each wire: the passthrough's `system`, chat's leading run of system messages, Responses' `instructions` and developer items. */
export function systemText(dialect: string, body: Json): string {
  if (!isObj(body)) return "";
  if (dialect === "anthropic-passthrough") {
    const s = body["system"];
    return typeof s === "string" ? s : list(s).filter(isObj).map((b) => String(b["text"] ?? "")).join("");
  }
  if (dialect === "openai-chat") {
    const msgs = conversation(dialect, body);
    const lead = msgs.findIndex((m) => m["role"] !== "system");
    return msgs.slice(0, lead === -1 ? msgs.length : lead).map(itemText).join("\n");
  }
  // Responses: the client's system text rides in `instructions`; an append layer rides as a trailing developer item
  // (ResponsesSystemPrompt), and this rig's turns are not lite, so no other developer item exists in the input.
  const developer = conversation(dialect, body).filter((i) => i["role"] === "developer").map(itemText);
  return [typeof body["instructions"] === "string" ? body["instructions"] : "", ...developer].join("\n");
}

export function gradeSeam(dialect: string, body: Json, row: SeamRow): string[] {
  const text = systemText(dialect, body);
  return [
    ...row.present.filter((p) => !text.includes(p)).map((p) => `system seam lost ${JSON.stringify(p)}`),
    ...row.absent.filter((a) => text.includes(a)).map((a) => `system seam still carries ${JSON.stringify(a)}`),
  ];
}

// ── the rigs: one per dialect ───────────────────────────────────────────────────────────────────────────────────────
interface Rig {
  short: string;
  pinned: string;
  /** upstream path suffix the head POSTs a turn to */
  suffix: string;
  provider(base: string, authFile: string): string;
  /** the fake upstream's answer: a minimal complete stream on this wire */
  stream: string;
}

const sse = (events: Array<[string | null, unknown]>) =>
  events.map(([name, data]) => `${name ? `event: ${name}\n` : ""}data: ${typeof data === "string" ? data : JSON.stringify(data)}\n\n`).join("");

export const RIGS: Record<string, Rig> = {
  "anthropic-passthrough": {
    short: "pt",
    pinned: "conf-pt",
    suffix: "/v1/messages",
    provider: (base) => `dialect = "anthropic-passthrough"
base_url = "${base}/anthropic"
auth = { kind = "api-key", env = "CONF_PASSTHROUGH_KEY" }`,
    stream: sse([
      ["message_start", { type: "message_start", message: { id: "msg_conf", type: "message", role: "assistant", model: "conf-pt", content: [], stop_reason: null, usage: { input_tokens: 3, output_tokens: 1 } } }],
      ["content_block_start", { type: "content_block_start", index: 0, content_block: { type: "text", text: "" } }],
      ["content_block_delta", { type: "content_block_delta", index: 0, delta: { type: "text_delta", text: "ok" } }],
      ["content_block_stop", { type: "content_block_stop", index: 0 }],
      ["message_delta", { type: "message_delta", delta: { stop_reason: "end_turn", stop_sequence: null }, usage: { output_tokens: 1 } }],
      ["message_stop", { type: "message_stop" }],
    ]),
  },
  "openai-chat": {
    short: "chat",
    pinned: "conf-chat",
    suffix: "/chat/completions",
    provider: (base) => `dialect = "openai-chat"
base_url = "${base}/chat"
auth = { kind = "api-key", env = "CONF_CHAT_KEY" }`,
    stream: sse([
      [null, { id: "chatcmpl-conf", object: "chat.completion.chunk", created: 1, model: "conf-chat", choices: [{ index: 0, delta: { role: "assistant", content: "ok" }, finish_reason: null }] }],
      [null, { id: "chatcmpl-conf", object: "chat.completion.chunk", created: 1, model: "conf-chat", choices: [{ index: 0, delta: {}, finish_reason: "stop" }], usage: { prompt_tokens: 3, completion_tokens: 1, total_tokens: 4 } }],
      [null, "[DONE]"],
    ]),
  },
  "openai-responses": {
    short: "resp",
    pinned: "conf-resp",
    suffix: "/responses",
    provider: (base, authFile) => `dialect = "openai-responses"
base_url = "${base}/responses-api"
auth = { kind = "chatgpt-oauth", file = "${authFile}" }`,
    stream: sse([
      [null, { type: "response.output_text.delta", output_index: 0, delta: "ok" }],
      [null, { type: "response.completed", response: { usage: { input_tokens: 3, output_tokens: 1 } } }],
    ]),
  },
};

const headKey = (dialect: string, mode: string) => `conf-${RIGS[dialect]!.short}-${mode}`;

/** The daemon's whole topology: a provider per dialect and a head per dialect x mode, on the given ports. */
export function topologyToml(
  manifest: Manifest,
  dialects: string[],
  modes: string[],
  ports: { control: number; heads: Record<string, number> },
  base: string,
  authFile: string,
): string {
  const providers = dialects.map((d) => {
    const rig = RIGS[d]!;
    return `[providers.conf-${rig.short}]\n${rig.provider(base, authFile)}\n\n[[providers.conf-${rig.short}.models]]\nid = "${rig.pinned}"\nlabel = "Conformance ${rig.short}"\ncontext_window = 200000\n`;
  });
  const heads = dialects.flatMap((d) =>
    modes.map((m) => {
      const rig = RIGS[d]!;
      const key = headKey(d, m);
      return `[heads.${key}]\nprovider = "conf-${rig.short}"\nport = ${ports.heads[key]}\ndiscovery_prefix = "claude-${key}--"\npinned_model = "${rig.pinned}"\nsystem_prompt = ${JSON.stringify(manifest.headPrompts[m])}\nsystem_prompt_mode = "${m}"\n[heads.${key}.claude]\ncommand = "claude-${key}"\n`;
    }),
  );
  return `# hermetic conformance topology — generated by tools/e2e conformance\n[daemon]\ncontrol_port = ${ports.control}\n\n${providers.join("\n")}\n${heads.join("\n")}`;
}

// ── the fake upstream ───────────────────────────────────────────────────────────────────────────────────────────────
interface Recorded {
  dialect: string;
  body: Json;
}

class FakeUpstream {
  readonly turns: Recorded[] = [];
  readonly server: http.Server;
  constructor() {
    this.server = http.createServer((req, res) => {
      const chunks: Buffer[] = [];
      req.on("data", (c: Buffer) => chunks.push(c));
      req.on("end", () => this.answer(req, res, Buffer.concat(chunks).toString("utf8")));
    });
  }

  private answer(req: http.IncomingMessage, res: http.ServerResponse, raw: string): void {
    const url = req.url ?? "";
    if (req.method !== "POST") return void res.writeHead(404).end();
    if (url.endsWith("/oauth/token")) {
      res.writeHead(200, { "Content-Type": "application/json" });
      return void res.end(JSON.stringify({ access_token: "tok-conf", refresh_token: "refresh-conf", id_token: "id-conf" }));
    }
    const dialect = Object.keys(RIGS).find((d) => url.endsWith(RIGS[d]!.suffix));
    if (!dialect) return void res.writeHead(404).end();
    try {
      this.turns.push({ dialect, body: JSON.parse(raw) as Json });
    } catch {
      res.writeHead(400);
      return void res.end("not json");
    }
    res.writeHead(200, { "Content-Type": "text/event-stream" });
    res.end(RIGS[dialect]!.stream);
  }

  async listen(): Promise<number> {
    await new Promise<void>((ok) => this.server.listen(0, "127.0.0.1", ok));
    return (this.server.address() as net.AddressInfo).port;
  }
}

async function freePorts(n: number): Promise<number[]> {
  const servers = Array.from({ length: n }, () => net.createServer());
  const ports = await Promise.all(servers.map((s) => new Promise<number>((ok) => s.listen(0, "127.0.0.1", () => ok((s.address() as net.AddressInfo).port)))));
  await Promise.all(servers.map((s) => new Promise((ok) => s.close(ok))));
  return ports;
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function waitHttp(port: number, path: string, tries = 240): Promise<void> {
  for (let i = 0; i < tries; i++) {
    try {
      await new Promise<void>((ok, bad) => {
        const q = http.get({ host: "127.0.0.1", port, path, timeout: 1000 }, (r) => {
          r.resume();
          ok();
        });
        q.on("error", bad);
        q.on("timeout", () => {
          q.destroy();
          bad(new Error("t/o"));
        });
      });
      return;
    } catch {
      await sleep(250);
    }
  }
  throw new HarnessError(`nothing answering on :${port}${path} after ${tries / 4}s`);
}

function postTurn(port: number, body: Json, bearer: string): Promise<{ status: number; text: string }> {
  return new Promise((ok, bad) => {
    const req = http.request(
      { host: "127.0.0.1", port, path: "/v1/messages", method: "POST", timeout: REQUEST_TIMEOUT_MS, headers: { "Content-Type": "application/json", Authorization: `Bearer ${bearer}` } },
      (r) => {
        let t = "";
        r.on("data", (c) => (t += c));
        r.on("end", () => ok({ status: r.statusCode ?? 0, text: t }));
      },
    );
    req.on("timeout", () => req.destroy(new HarnessError(`no answer from :${port} in ${REQUEST_TIMEOUT_MS}ms — accepted and never answered`)));
    req.on("error", bad);
    req.end(JSON.stringify(body));
  });
}

// ── the run ─────────────────────────────────────────────────────────────────────────────────────────────────────────
export interface CellResult {
  cell: string;
  pass: boolean;
  problems: string[];
}

/** One turn into one head: the request the class built, the client's answer and the single upstream request it caused. */
async function drive(fake: FakeUpstream, port: number, bearer: string, dialect: string, body: Obj): Promise<{ upstream: Json | null; problems: string[] }> {
  fake.turns.length = 0;
  const reply = await postTurn(port, body, bearer);
  const problems: string[] = [];
  if (reply.status !== 200) problems.push(`the head answered ${reply.status}: ${reply.text.slice(0, 160)}`);
  else if (!reply.text.includes("message_stop")) problems.push("the client stream never reached message_stop");
  const turns = fake.turns.filter((t) => t.dialect === dialect);
  if (turns.length !== 1) problems.push(`expected exactly one upstream turn on the ${dialect} wire, saw ${turns.length}`);
  return { upstream: turns.length === 1 ? turns[0]!.body : null, problems };
}

function clientBody(model: string, manifest: Manifest, request: Obj): Obj {
  return { model, max_tokens: 64, stream: true, system: manifest.clientSystem, ...request };
}

export async function runCells(
  corpus: Corpus,
  dialects: string[],
  modes: string[],
  fake: FakeUpstream,
  ports: Record<string, number>,
  bearer: string,
  only: string | null,
): Promise<CellResult[]> {
  const results: CellResult[] = [];
  for (const dialect of dialects) {
    for (const mode of modes) {
      const key = headKey(dialect, mode);
      const model = RIGS[dialect]!.pinned;
      const seam = await drive(fake, ports[key]!, bearer, dialect, clientBody(model, corpus.manifest, { messages: [{ role: "user", content: "CONF-SEAM hello" }] }));
      const seamProblems = seam.upstream === null ? seam.problems : [...seam.problems, ...gradeSeam(dialect, seam.upstream, corpus.manifest.systemSeam[mode]!)];
      if (!only) results.push({ cell: `system-seam x ${dialect} x ${mode}`, pass: seamProblems.length === 0, problems: seamProblems });
      for (const cls of corpus.manifest.classes) {
        if (only && cls !== only) continue;
        const fx = corpus.fixtures[cls]!;
        const run = await drive(fake, ports[key]!, bearer, dialect, clientBody(model, corpus.manifest, fx.request));
        const problems = run.upstream === null ? run.problems : [...run.problems, ...gradeChecks(dialect, run.upstream, fx.expect[dialect] ?? [])];
        results.push({ cell: `${cls} x ${dialect} x ${mode}`, pass: problems.length === 0, problems });
      }
    }
  }
  return results;
}

interface Options {
  jar: string;
  only: string | null;
  keep: boolean;
  jsonOut: string | null;
}

export async function conformance(argv: readonly string[]): Promise<number> {
  const args = [...argv];
  const valueless: string[] = [];
  const opt = (n: string): string | null => {
    const i = args.indexOf(n);
    if (i < 0) return null;
    const v = args[i + 1];
    if (v === undefined || v.length === 0 || v.startsWith("-")) {
      valueless.push(n);
      return null;
    }
    return v;
  };
  const artifact = opt("--artifact");
  const only = opt("--class");
  const jsonOut = opt("--json");
  if (valueless.length > 0) {
    console.error(`e2e conformance: ${valueless.join(", ")} takes a value — a bare or empty option is refused, never read as the default`);
    return HARNESS_EXIT;
  }
  const options: Options = { jar: artifact !== null ? resolve(artifact) : join(layout().buildRoot, DEFAULT_JAR), only, keep: args.includes("--keep"), jsonOut };
  try {
    return await run(options);
  } catch (e) {
    if (e instanceof HarnessError) {
      console.error(`HARNESS FAILURE: ${e.message}`);
      return HARNESS_EXIT;
    }
    throw e;
  }
}

async function run({ jar, only, keep, jsonOut }: Options): Promise<number> {
  const corpus = loadCorpus();
  const dialects = sourceDialects();
  const modes = sourceModes();
  const gaps = census(corpus, dialects, modes, Object.keys(RIGS));
  if (gaps.length > 0) throw new HarnessError(`the conformance census found ${gaps.length} gap(s):\n  ${gaps.join("\n  ")}`);
  if (only && !corpus.manifest.classes.includes(only)) throw new HarnessError(`--class ${only} is not a class (${corpus.manifest.classes.join(", ")})`);
  if (!existsSync(jar)) throw new HarnessError(`fat jar missing at ${jar} — build it first (bun tools/gate slot <label> -- :app:shadowJar) or pass --artifact`);

  const tmp = mkdtempSync(join(tmpdir(), "splice-conformance-"));
  const fake = new FakeUpstream();
  const fakePort = await fake.listen();
  const heads = dialects.flatMap((d) => modes.map((m) => headKey(d, m)));
  const allPorts = await freePorts(heads.length + 1);
  const ports = { control: allPorts[0]!, heads: Object.fromEntries(heads.map((k, n) => [k, allPorts[n + 1]!])) };
  const authFile = join(tmp, "auth.json");
  writeFileSync(authFile, JSON.stringify({ auth_mode: "chatgpt", tokens: { id_token: "id-1", access_token: "tok-conf", refresh_token: "refresh-1", account_id: "acct-conf" }, last_refresh: "2026-01-01T00:00:00Z" }));
  mkdirSync(join(tmp, "state"), { recursive: true });
  writeFileSync(join(tmp, "splice.toml"), topologyToml(corpus.manifest, dialects, modes, ports, `http://127.0.0.1:${fakePort}`, authFile));

  const env = daemonEnv({
    SPLICE_CONFIG: join(tmp, "splice.toml"),
    CLAUDEX_STATE_DIR: join(tmp, "state"),
    CONF_PASSTHROUGH_KEY: "synthetic-passthrough-key",
    CONF_CHAT_KEY: "synthetic-chat-key",
    CODEX_OAUTH_TOKEN_URL: `http://127.0.0.1:${fakePort}/oauth/token`,
  });
  const daemon = spawn("java", ["-Xmx1024m", "-jar", jar, "daemon"], { env, stdio: ["ignore", "pipe", "pipe"] });
  let dlog = "";
  daemon.stdout.on("data", (c) => (dlog += c));
  daemon.stderr.on("data", (c) => (dlog += c));
  const dead = new Promise<never>((_, bad) => daemon.once("exit", (code) => bad(new HarnessError(`daemon exited (${code}) before it answered\n${dlog.slice(-1500)}`))));
  dead.catch(() => {});
  const kill = () => {
    try {
      daemon.kill("SIGKILL");
    } catch {
      /* already gone */
    }
  };
  process.on("exit", kill);
  for (const sig of ["SIGINT", "SIGTERM", "SIGHUP"] as const) process.on(sig, () => (kill(), process.exit(130)));

  let results: CellResult[] = [];
  try {
    await Promise.race([
      (async () => {
        await waitHttp(ports.control, "/health");
        for (const k of heads) await waitHttp(ports.heads[k]!, "/health").catch(() => waitHttp(ports.heads[k]!, "/"));
      })(),
      dead,
    ]);
    const bearer = readFileSync(join(tmp, "state", "mgmt-key"), "utf8").trim();
    results = await runCells(corpus, dialects, modes, fake, ports.heads, bearer, only);
  } finally {
    kill();
    fake.server.close();
    if (!keep) rmSync(tmp, { recursive: true, force: true });
    else console.error(`conformance: kept ${tmp}`);
  }

  for (const r of results) {
    console.log(`${r.pass ? "PASS" : "FAIL"}  ${r.cell}`);
    for (const p of r.problems) console.log(`        ${p}`);
  }
  const failed = results.filter((r) => !r.pass);
  console.log(`\nconformance: ${results.length - failed.length}/${results.length} cells pass (${corpus.manifest.classes.length} classes x ${dialects.length} dialects x ${modes.length} modes, plus the system seam of each dialect x mode)`);
  if (jsonOut) writeFileSync(jsonOut, JSON.stringify(results, null, 2));
  return failed.length === 0 ? 0 : DIVERGENCE_EXIT;
}
