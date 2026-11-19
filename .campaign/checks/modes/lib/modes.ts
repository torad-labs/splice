// What the modes checks stand on (the `modes` feature): the template mode as its folder says it, a splice.toml
// `[modes.<name>]` table, a scratch copy of this checkout where a check drops a mode folder in and builds it the way a
// person would, a turn sent through splice's door with tools, and a stand-in model server that speaks OpenAI's Chat
// Completions and the Responses API and calls the template's tool when it is offered.

import { spawnSync } from "node:child_process";
import {
  chmodSync,
  copyFileSync,
  existsSync,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  readlinkSync,
  rmSync,
  statSync,
  symlinkSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import type { Message } from "../../harness/lib/capability.ts";
import { ROOT, type Ran } from "../../server/lib/harness.ts";

export { ROOT };

/** The template mode's folder in this checkout. */
export const TEMPLATE = join(ROOT, "modes", "template");

/** The template's one tool as the model is offered it. */
export const TOOL = "mcp__template__say_back";

/** Fails at once, before anything is built, while this checkout has no template mode. */
export function requireTemplate(): void {
  if (!existsSync(join(TEMPLATE, "mode.toml"))) throw new Error(`no template mode at ${TEMPLATE}: modes/template/mode.toml does not exist`);
}

/** The template's tool as its folder says it: its description file, trimmed, and its schema file. */
export function templateTool(): { name: string; description: string; input_schema: Record<string, unknown> } {
  return {
    name: TOOL,
    description: readFileSync(join(TEMPLATE, "tools", "say_back.md"), "utf8").trim(),
    input_schema: JSON.parse(readFileSync(join(TEMPLATE, "tools", "say_back.json"), "utf8")) as Record<string, unknown>,
  };
}

/** The template's guidance for the model, trimmed. */
export function templatePrompt(): string {
  return readFileSync(join(TEMPLATE, "prompt.md"), "utf8").trim();
}

/** A string key of the template's manifest, read off its own line. */
export function templateKey(key: string): string {
  const m = new RegExp(`^${key}\\s*=\\s*"(.*)"\\s*(#.*)?$`, "m").exec(readFileSync(join(TEMPLATE, "mode.toml"), "utf8"));
  if (!m) throw new Error(`modes/template/mode.toml has no ${key} = "..." line`);
  return m[1]!;
}

/** `[modes.<name>]` in splice.toml with `lines` in it. */
export function modesTable(name: string, ...lines: string[]): string {
  return `\n[modes.${name}]\n${lines.join("\n")}\n`;
}

/** A marker that ties one test's requests together. */
export const mark = (): string => `m${crypto.randomUUID().slice(0, 8)}`;

/** A request's system text, whether it was written as a string or as blocks. */
export function systemText(body: { system?: unknown }): string {
  const s = body.system;
  if (typeof s === "string") return s;
  if (Array.isArray(s)) return s.map((b) => String((b as { text?: unknown }).text ?? "")).join("\n");
  return "";
}

/** How many times `needle` occurs in `hay`. */
export function occurrences(hay: string, needle: string): number {
  if (!needle) return 0;
  let n = 0;
  for (let at = hay.indexOf(needle); at >= 0; at = hay.indexOf(needle, at + needle.length)) n++;
  return n;
}

/** `text` as it reads inside a JSON string. */
export const inJson = (text: string): string => JSON.stringify(text).slice(1, -1);

const DEVICE = "c0ffee".repeat(11).slice(0, 64);
const READ_TOOL = { name: "Read", description: "Read a file.", input_schema: { type: "object", properties: { file_path: { type: "string" } }, required: ["file_path"] } };

/** One streamed turn with tools through splice's door (the terminal's or Claude Desktop's), as Claude Code sends it in
 *  Mantle mode: the door's address, its key as a Bearer, the session in Claude Code's header and in the metadata. */
export async function doorSend(url: string, key: string, session: string, messages: Message[], model: string): Promise<{ status: number; body: string }> {
  const user = JSON.stringify({ device_id: DEVICE, account_uuid: "", session_id: session });
  const r = await fetch(`${url}/v1/messages?beta=true`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "anthropic-version": "2023-06-01",
      authorization: `Bearer ${key}`,
      "user-agent": "claude-cli/2.1.326 (external, cli)",
      "x-app": "cli",
      "x-claude-code-session-id": session,
    },
    body: JSON.stringify({ model, max_tokens: 1024, stream: true, metadata: { user_id: user }, tools: [READ_TOOL], messages }),
  });
  return { status: r.status, body: await r.text() };
}

/** Every command this module runs to its end, with its output. No time limit: a build runs until it finishes. */
function run(cmd: string, args: string[], cwd: string, input?: string): Ran {
  const r = spawnSync(cmd, args, { cwd, encoding: "utf8", input, maxBuffer: 1 << 28, env: process.env });
  return { code: r.status ?? -1, out: r.stdout ?? "", err: `${r.stderr ?? ""}${r.error ? String(r.error) : ""}` };
}

/** One JUnit report's counts and every failure's text. */
export interface TestReport {
  tests: number;
  failures: number;
  /** Each failure's message and its text, entities decoded. */
  said: string;
}

const decode = (s: string): string =>
  s.replaceAll("&lt;", "<").replaceAll("&gt;", ">").replaceAll("&quot;", '"').replaceAll("&apos;", "'").replaceAll("&#10;", "\n").replaceAll("&amp;", "&");

/**
 * A scratch copy of this checkout, as `git ls-files` names it (tracked files and new ones not ignored), made a git
 * repository of its own with one commit, with this checkout's node_modules linked in. A check changes it the way a
 * person would (copies a mode folder, edits a file), builds it with its own Gradle wrapper, and boots splice from the
 * jar it builds through the server checks' harness as this copy has it.
 */
export class Tree {
  private constructor(readonly dir: string) {}

  static make(): Tree {
    const dir = mkdtempSync(join(tmpdir(), "splice-modes-tree-"));
    const listed = run("git", ["ls-files", "-z", "-co", "--exclude-standard"], ROOT);
    if (listed.code !== 0) throw new Error(`git ls-files failed in ${ROOT}: ${listed.err}`);
    for (const file of listed.out.split("\0").filter(Boolean)) {
      if (/(^|\/)build\//.test(file)) continue;
      const from = join(ROOT, file);
      let st;
      try {
        st = lstatSync(from);
      } catch {
        continue;
      }
      const to = join(dir, file);
      mkdirSync(dirname(to), { recursive: true });
      if (st.isSymbolicLink()) symlinkSync(readlinkSync(from), to);
      else if (st.isFile()) {
        copyFileSync(from, to);
        chmodSync(to, st.mode & 0o777);
      }
    }
    if (existsSync(join(ROOT, "node_modules"))) symlinkSync(join(ROOT, "node_modules"), join(dir, "node_modules"));
    const t = new Tree(dir);
    t.git(["init", "-q"]);
    t.commit("the checkout as it was");
    return t;
  }

  path(rel: string): string {
    return join(this.dir, rel);
  }

  git(args: string[]): Ran {
    const r = run("git", ["-c", "user.name=modes-check", "-c", "user.email=modes-check@localhost", "-c", "commit.gpgsign=false", ...args], this.dir);
    if (r.code !== 0) throw new Error(`git ${args.join(" ")} failed in ${this.dir}:\n${r.out}\n${r.err}`);
    return r;
  }

  /** Commits every change, so restore() comes back to here. */
  commit(message: string): void {
    this.git(["add", "-A"]);
    this.git(["commit", "-q", "--allow-empty", "--no-verify", "-m", message]);
  }

  /** Back to the last commit: edits undone, new files gone (build output, which git ignores, stays). */
  restore(): void {
    this.git(["reset", "-q", "--hard"]);
    this.git(["clean", "-fdq"]);
  }

  read(rel: string): string {
    return readFileSync(this.path(rel), "utf8");
  }

  write(rel: string, text: string): void {
    mkdirSync(dirname(this.path(rel)), { recursive: true });
    writeFileSync(this.path(rel), text);
  }

  edit(rel: string, change: (text: string) => string): void {
    this.write(rel, change(this.read(rel)));
  }

  remove(rel: string): void {
    rmSync(this.path(rel), { recursive: true, force: true });
  }

  /**
   * modes/<from> copied to modes/<to> and renamed the way the README's steps say: every `from` in a file's text and in
   * a path becomes `to`, in lower case, capitalised and upper case (its manifest's name, server and handler, its
   * package, its classes). Build output is not copied.
   */
  copyMode(from: string, to: string): void {
    const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);
    const rename = (s: string) => s.replaceAll(from, to).replaceAll(cap(from), cap(to)).replaceAll(from.toUpperCase(), to.toUpperCase());
    const walk = (src: string, dst: string) => {
      mkdirSync(dst, { recursive: true });
      for (const name of readdirSync(src)) {
        if (name === "build" || name === ".gradle") continue;
        const s = join(src, name);
        const d = join(dst, rename(name));
        if (statSync(s).isDirectory()) walk(s, d);
        else writeFileSync(d, rename(readFileSync(s, "utf8")));
      }
    };
    const src = this.path(`modes/${from}`);
    if (!existsSync(join(src, "mode.toml"))) throw new Error(`no mode at modes/${from} to copy`);
    walk(src, this.path(`modes/${to}`));
  }

  /** `./gradlew <args>` in this copy, run to its end. */
  gradle(args: string[]): Ran {
    return run("./gradlew", ["--console=plain", ...args], this.dir);
  }

  /** `bun tools/gate <args>` in this copy. */
  gate(args: string[]): Ran {
    return run(process.execPath, ["tools/gate", ...args], this.dir);
  }

  /** The fat jar of this copy, built now. */
  jar(): string {
    const r = this.gradle([":app:shadowJar"]);
    if (r.code !== 0) throw new Error(`the copy's build failed:\n${`${r.out}${r.err}`.slice(-6000)}`);
    return this.path("app/build/libs/app-all.jar");
  }

  /** The JUnit reports of the module in `moduleDir` (repo-relative), all of them together. */
  testReport(moduleDir: string): TestReport {
    const dir = this.path(`${moduleDir}/build/test-results/test`);
    const report: TestReport = { tests: 0, failures: 0, said: "" };
    if (!existsSync(dir)) return report;
    for (const name of readdirSync(dir).filter((n) => n.endsWith(".xml"))) {
      const xml = readFileSync(join(dir, name), "utf8");
      const suite = /<testsuite\b[^>]*>/.exec(xml)?.[0] ?? "";
      const count = (attr: string) => Number(new RegExp(`\\b${attr}="(\\d+)"`).exec(suite)?.[1] ?? 0);
      report.tests += count("tests");
      report.failures += count("failures") + count("errors");
      for (const m of xml.matchAll(/<(failure|error)\b([^>]*)>([\s\S]*?)<\/\1>/g)) {
        report.said += `${decode(/message="([^"]*)"/.exec(m[2] ?? "")?.[1] ?? "")}\n${decode((m[3] ?? "").replace(/<!\[CDATA\[|\]\]>/g, ""))}\n`;
      }
    }
    return report;
  }

  /** The server checks' harness as this copy has it: its Splice builds and runs this copy's jar. */
  async harness(): Promise<typeof import("../../server/lib/harness.ts")> {
    return (await import(this.path(".campaign/checks/server/lib/harness.ts"))) as typeof import("../../server/lib/harness.ts");
  }

  dispose(): void {
    if (process.env.SPLICE_CHECK_KEEP === "1") console.error(`kept ${this.dir}`);
    else rmSync(this.dir, { recursive: true, force: true });
  }
}

/** One file inside a jar, read with unzip or, where there is none, with the JDK's jar tool. */
export function jarEntry(jar: string, entry: string): string {
  const unzip = spawnSync("unzip", ["-p", jar, entry], { encoding: "utf8", maxBuffer: 1 << 26 });
  if (!unzip.error && unzip.status === 0) return unzip.stdout;
  const out = mkdtempSync(join(tmpdir(), "splice-modes-jar-"));
  try {
    const x = spawnSync("jar", ["xf", jar, entry], { cwd: out, encoding: "utf8" });
    if (x.error || x.status !== 0) throw new Error(`neither unzip nor jar could read ${entry} from ${jar}: ${unzip.stderr ?? ""}${x.stderr ?? ""}`);
    const file = join(out, entry);
    return existsSync(file) ? readFileSync(file, "utf8") : "";
  } finally {
    rmSync(out, { recursive: true, force: true });
  }
}

/** A tool among a request's tools, a namespace's members included, by name: Chat's `function` tools and Responses'
 *  flat ones alike, as `{ name, description, parameters }`. */
export function offeredTool(body: Record<string, unknown> | null, name: string): { name: string; description: string; parameters: unknown } | null {
  const flat: Record<string, unknown>[] = [];
  const walk = (tools: unknown) => {
    for (const t of Array.isArray(tools) ? tools : []) {
      const o = t as Record<string, unknown>;
      if (Array.isArray(o.tools)) walk(o.tools);
      else flat.push(o);
    }
  };
  walk(body?.tools);
  for (const o of flat) {
    const f = (o.function as Record<string, unknown> | undefined) ?? o;
    if (f.name === name) return { name, description: String(f.description ?? ""), parameters: f.parameters ?? f.input_schema };
  }
  return null;
}

/** What a Chat Completions or Responses request carries as the result of call `id`, or null. */
export function resultOf(body: Record<string, unknown> | null): string | null {
  for (const m of (body?.messages as Record<string, unknown>[] | undefined) ?? []) {
    if (m.role === "tool") return textOf(m.content);
  }
  for (const i of (body?.input as Record<string, unknown>[] | undefined) ?? []) {
    if (i.type === "function_call_output") return textOf(i.output);
  }
  return null;
}

function textOf(c: unknown): string {
  if (typeof c === "string") return c;
  if (Array.isArray(c)) return c.map((p) => String((p as { text?: unknown }).text ?? "")).join("");
  return "";
}

interface Seen {
  path: string;
  body: Record<string, unknown> | null;
}

const ASKS = /say back (m[0-9a-f]{8})/;

/**
 * A model server that speaks OpenAI's Chat Completions (under /v1) and the Responses API the way Codex's backend does
 * (its /models, /oauth/token and /responses, at the root). A request that offers the template's tool, holds no result
 * for it yet, and asks `say back <marker>` is answered with a call of that tool whose `text` is the marker. A request
 * that holds a result is answered `the mode said: <result>`. Any other is answered `ok from tools`.
 */
export class ToolsUpstream {
  readonly seen: Seen[] = [];
  private server: ReturnType<typeof Bun.serve> | null = null;

  start(): this {
    this.server = Bun.serve({ port: 0, hostname: "127.0.0.1", idleTimeout: 0, fetch: (req) => this.answer(req) });
    return this;
  }

  stop(): void {
    this.server?.stop(true);
  }

  /** The Responses backend's address (a Codex head's backend). */
  get url(): string {
    return `http://127.0.0.1:${this.server!.port}`;
  }

  /** The Chat Completions base address (an openai-chat provider's base_url). */
  get chatUrl(): string {
    return `${this.url}/v1`;
  }

  /** The model requests whose bytes hold `marker`, oldest first. */
  with(marker: string): Seen[] {
    return this.seen.filter((s) => (s.path.endsWith("/chat/completions") || s.path.endsWith("/responses")) && JSON.stringify(s.body).includes(marker));
  }

  private async answer(req: Request): Promise<Response> {
    const path = new URL(req.url).pathname;
    let body: Record<string, unknown> | null = null;
    if (req.method === "POST") {
      const text = await req.text();
      try {
        body = JSON.parse(text) as Record<string, unknown>;
      } catch {
        body = { unparsed: text };
      }
      this.seen.push({ path, body });
    }
    if (path === "/health" || path === "/v1/health") return Response.json({ status: "ok" });
    if (path === "/v1/models") return Response.json({ object: "list", data: [{ id: "chat-large", object: "model", owned_by: "check" }] });
    if (path === "/models") {
      return Response.json({ models: [{ slug: "gpt-6-astra", display_name: "gpt-6-astra", context_window: 400000, service_tiers: [], additional_speed_tiers: [] }] });
    }
    if (path === "/oauth/token") return Response.json({ access_token: token(), refresh_token: "mock-refresh", expires_in: 3600 });
    if (path.endsWith("/chat/completions")) return this.chat(body ?? {});
    if (path.endsWith("/responses")) return this.responses(body ?? {});
    return Response.json({ error: { message: `no route ${path}` } }, { status: 404 });
  }

  /** What to answer: a call of the template's tool with the marker, or a text. */
  private plan(body: Record<string, unknown>, history: unknown, result: string | null): { call: string } | { text: string } {
    if (result !== null) return { text: `the mode said: ${result}` };
    const marker = ASKS.exec(JSON.stringify(history))?.[1];
    if (marker && offeredTool(body, TOOL)) return { call: marker };
    return { text: "ok from tools" };
  }

  private chat(body: Record<string, unknown>): Response {
    const said = this.plan(body, body.messages, resultOf({ messages: body.messages }));
    const id = `chatcmpl-${this.seen.length}`;
    const created = Math.floor(Date.now() / 1000);
    const usage = { prompt_tokens: 1200, completion_tokens: 40, total_tokens: 1240, prompt_tokens_details: { cached_tokens: 0 } };
    const args = "call" in said ? JSON.stringify({ text: said.call }) : "";
    if (body.stream !== true) {
      const message = "call" in said
        ? { role: "assistant", content: null, tool_calls: [{ id: "call_say_1", type: "function", function: { name: TOOL, arguments: args } }] }
        : { role: "assistant", content: said.text };
      return Response.json({ id, object: "chat.completion", created, model: body.model, choices: [{ index: 0, message, finish_reason: "call" in said ? "tool_calls" : "stop" }], usage });
    }
    const chunk = (delta: Record<string, unknown>, finish: string | null, extra: Record<string, unknown> = {}) =>
      `data: ${JSON.stringify({ id, object: "chat.completion.chunk", created, model: body.model, choices: [{ index: 0, delta, finish_reason: finish }], ...extra })}\n\n`;
    const parts: string[] = [];
    if ("call" in said) {
      parts.push(chunk({ role: "assistant", content: null, tool_calls: [{ index: 0, id: "call_say_1", type: "function", function: { name: TOOL, arguments: "" } }] }, null));
      parts.push(chunk({ tool_calls: [{ index: 0, function: { arguments: args } }] }, null));
      parts.push(chunk({}, "tool_calls"));
    } else {
      parts.push(chunk({ role: "assistant", content: "" }, null));
      parts.push(chunk({ content: said.text }, null));
      parts.push(chunk({}, "stop"));
    }
    parts.push(`data: ${JSON.stringify({ id, object: "chat.completion.chunk", created, model: body.model, choices: [], usage })}\n\n`, "data: [DONE]\n\n");
    return new Response(parts.join(""), { headers: { "content-type": "text/event-stream", "cache-control": "no-cache" } });
  }

  private responses(body: Record<string, unknown>): Response {
    const said = this.plan(body, body.input, resultOf({ input: body.input }));
    const n = this.seen.length;
    const id = `resp_${n}`;
    const usage = { input_tokens: 1000, input_tokens_details: { cached_tokens: 0 }, output_tokens: 100, output_tokens_details: { reasoning_tokens: 0 }, total_tokens: 1100 };
    const events: Record<string, unknown>[] = [{ type: "response.created", response: { id, status: "in_progress", output: [], service_tier: "default" } }];
    let item: Record<string, unknown>;
    if ("call" in said) {
      const args = JSON.stringify({ text: said.call });
      item = { type: "function_call", id: `fc_${n}`, call_id: `call_${n}`, name: TOOL, arguments: args, status: "completed" };
      events.push(
        { type: "response.output_item.added", output_index: 0, item: { ...item, arguments: "", status: "in_progress" } },
        { type: "response.function_call_arguments.delta", output_index: 0, item_id: item.id, delta: args },
        { type: "response.function_call_arguments.done", output_index: 0, item_id: item.id, arguments: args },
        { type: "response.output_item.done", output_index: 0, item },
      );
    } else {
      item = { type: "message", id: `msg_${n}`, role: "assistant", status: "completed", content: [{ type: "output_text", text: said.text, annotations: [] }] };
      events.push(
        { type: "response.output_item.added", output_index: 0, item: { ...item, status: "in_progress", content: [] } },
        { type: "response.content_part.added", output_index: 0, content_index: 0, item_id: item.id, part: { type: "output_text", text: "", annotations: [] } },
        { type: "response.output_text.delta", output_index: 0, content_index: 0, item_id: item.id, delta: said.text },
        { type: "response.output_text.done", output_index: 0, content_index: 0, item_id: item.id, text: said.text },
        { type: "response.output_item.done", output_index: 0, item },
      );
    }
    events.push({ type: "response.completed", response: { id, status: "completed", model: body.model, service_tier: "default", output: [item], usage } });
    const text = events.map((e) => `event: ${String(e.type)}\ndata: ${JSON.stringify(e)}\n\n`).join("");
    return new Response(text, { headers: { "content-type": "text/event-stream" } });
  }
}

/** An access token the way a ChatGPT login's file holds one: a JWT whose payload says it is good for an hour. */
function token(): string {
  const payload = Buffer.from(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600 })).toString("base64url");
  return `e30.${payload}.synthetic-refreshed`;
}
