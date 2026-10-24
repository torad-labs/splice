/** Real Claude Code 2.1.310 -> isolated jar -> loopback scripted Responses (WS and SSE).
 * No live model, inherited credentials, raw request/answer capture, or production writes.
 * See probe-client.md. Importing this module does not start processes or listeners.
 */
import { spawn, type ChildProcess } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import { createReadStream, existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { interactiveClient } from "./interactive-client";

const CLIENT = "/home/marcos/.local/share/claude/versions/2.1.310";
const MODEL = "gpt-6.1-sol";
const SUMMARY = "PROBE_READABLE_REASONING: inspect the callback file, then run the Bash tool exactly once.";
const ENVELOPE = "synthetic-probe-reasoning-envelope-not-a-real-credential";
const FINAL = "PROBE_FINISHED";
const HOLD_MS = 2000;
const PROBE_VERSION = 3;
type Obj = Record<string, any>;
type Scenario = "success" | "late-failure";
type Mark = { event: string; epochMs: number; elapsedMs: number; [key: string]: unknown };

function safeEnv(home: string): Record<string, string> {
  // An allowlist: no OAuth, cloud credentials, node injection, Java options or proxy inheritance.
  return {
    PATH: process.env.PATH ?? "/usr/bin:/bin", HOME: home,
    LANG: "C.UTF-8", TMPDIR: join(home, "tmp"),
    XDG_CONFIG_HOME: join(home, ".config"), XDG_DATA_HOME: join(home, ".local/share"),
    XDG_STATE_HOME: join(home, ".local/state"), XDG_CACHE_HOME: join(home, ".cache"),
    CLAUDE_CONFIG_DIR: join(home, "claude"), CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC: "1",
    DISABLE_AUTOUPDATER: "1", DISABLE_TELEMETRY: "1", DISABLE_ERROR_REPORTING: "1",
    CLAUDE_CODE_SIMPLE: "1", TERM: "dumb",
  };
}
function child(cmd: string[], cwd: string, env: Record<string, string>): ChildProcess {
  const p = spawn(cmd[0]!, cmd.slice(1), { cwd, env, detached: true, stdio: ["ignore", "pipe", "pipe"] });
  // Consume output without saving potentially credential-bearing startup diagnostics.
  p.stderr?.on("data", () => {});
  p.on("error", () => {});
  return p;
}
async function stop(p?: ChildProcess): Promise<void> {
  if (!p?.pid) return;
  const signal = (s: NodeJS.Signals) => { try { process.kill(-p.pid!, s); } catch { /* exited */ } };
  signal("SIGTERM");
  for (let i = 0; i < 40 && p.exitCode === null && p.signalCode === null; i++) await Bun.sleep(100);
  // Also kills any remaining code-mode workers in this probe-owned process group.
  signal("SIGKILL");
}
async function sha(path: string): Promise<string> {
  const h = createHash("sha256");
  for await (const chunk of createReadStream(path)) h.update(chunk);
  return h.digest("hex");
}
async function freePort(): Promise<number> {
  const s = Bun.serve({ hostname: "127.0.0.1", port: 0, fetch: () => new Response("reserved") });
  const port = s.port!; await s.stop(true); return port;
}
function parseEvents(line: string): Obj | undefined {
  const s = line.startsWith("data:") ? line.slice(5).trim() : line;
  try { return JSON.parse(s); } catch { return undefined; }
}
export function toolDefinitions(body: Obj): Obj[] {
  const additional = (Array.isArray(body.input) ? body.input : [])
    .filter((i: Obj) => i.type === "additional_tools").flatMap((i: Obj) => i.tools ?? []);
  return [...(body.tools ?? []), ...additional].flatMap((t: Obj) => t.type === "namespace"
    ? (t.tools ?? []).map((n: Obj) => ({ ...n, namespace: t.name })) : [t]);
}
export function declaredTools(description: string): string[] {
  // Inspect TypeScript declarations, permitting whitespace/newlines rather than formatter spelling.
  return [...description.matchAll(/declare\s+const\s+tools\s*:\s*\{\s*([A-Za-z_$][A-Za-z0-9_$]*)\s*\(/g)].map(m => m[1]!);
}

async function scenario(jar: string, name: Scenario, root: string): Promise<Obj> {
  const home = join(root, name); const task = join(home, "task");
  for (const d of [home, task, join(home, "tmp"), join(home, "claude"), join(home, "state")]) mkdirSync(d, { recursive: true, mode: 0o700 });
  const effects = join(task, "effects.txt");
  const start = performance.now(); const marks: Mark[] = []; const requests: Obj[] = [];
  const mark = (event: string, extra: Obj = {}) => marks.push({ event, epochMs: Date.now(), elapsedMs: Math.round(performance.now() - start), ...extra });
  let scripts = 0, finals = 0, lateFailures = 0, seq = 0, clientEvents = 0, redacted = 0, titles = 0;
  let summaryVisibleAt: number | null = null, finalVisible = false, toolOpenAt: number | null = null;
  let clientVersion: string | null = null;
  let wireThinking = "", cliThinking = "";
  let wireThinkingDeltas = 0;
  let daemon: ChildProcess | undefined;
  let releaseFirstTerminal!: () => void;
  const firstTerminal = new Promise<void>(r => { releaseFirstTerminal = r; });
  const pending = new Set<Promise<void>>(); const errors: string[] = [];
  const completedResponses = new Map<string, { definitions: Obj[]; reasoningPresent: boolean }>();
  const run = async (raw: Obj, send: (event: Obj) => void): Promise<void> => {
    const b = raw.response ?? raw;
    const input: Obj[] = Array.isArray(b.input) ? b.input : [];
    const explicitDefs = toolDefinitions(b);
    const prior = typeof b.previous_response_id === "string" ? completedResponses.get(b.previous_response_id) : undefined;
    if (b.previous_response_id && !prior) errors.push("mock received unknown previous_response_id");
    const defs = explicitDefs.length ? explicitDefs : prior?.definitions ?? [];
    const exec = defs.find(t => t.name === "exec" && t.type === "custom");
    const manualTools = declaredTools(exec?.description ?? "");
    const outputs = input.filter(i => ["custom_tool_call_output", "function_call_output"].includes(i.type));
    requests.push({ epochMs: Date.now(), model: b.model, generate: b.generate,
      reasoning: b.reasoning, include: b.include, inputItems: input.length,
      reasoningItems: input.filter(i => i.type === "reasoning").length,
      fixtureReasoningPresent: input.some(i => i.type === "reasoning" && i.encrypted_content === ENVELOPE),
      previousResponseIdPresent: !!b.previous_response_id,
      previousResponseIdKnown: !!prior,
      effectiveFixtureReasoningPresent: !!prior?.reasoningPresent || input.some(i => i.type === "reasoning" && i.encrypted_content === ENVELOPE),
      explicitExecPresent: explicitDefs.some(t => t.name === "exec" && t.type === "custom"),
      toolOutputs: outputs.length, execPresent: !!exec,
      toolNames: defs.map(t => ({ name: t.name, type: t.type, namespace: t.namespace ?? null })),
      declaredToolNames: manualTools, bashInManual: manualTools.includes("Bash") });
    mark("upstream_request", { number: requests.length, toolOutputs: outputs.length });
    const emit = (e: Obj) => { send({ ...e, sequence_number: ++seq }); };
    if (b.generate === false) {
      emit({ type: "response.completed", response: { id: `prefill_${seq}`, status: "completed", output: [], usage: { input_tokens: 1, output_tokens: 0 } } });
      return;
    }
    // The real TUI names its session through a separate, tool-free structured-output request.
    const format = b.text?.format;
    const titleRequest = !exec && format?.name === "structured_output" &&
      Object.keys(format.schema?.properties ?? {}).join() === "title" &&
      input.some(i => i.role === "developer" && typeof i.content === "string" &&
        i.content.includes("You are naming a coding session"));
    if (titleRequest && ++titles <= 4) {
      requests.at(-1)!.requestKind = "session-title";
      const id = `fixture_title_${titles}`, text = JSON.stringify({ title: "Fixture callback" });
      const item = { type: "message", id: `${id}_message`, role: "assistant", status: "completed",
        content: [{ type: "output_text", text, annotations: [] }] };
      emit({ type: "response.created", response: { id, status: "in_progress", output: [] } });
      emit({ type: "response.output_item.added", output_index: 0, item: { ...item, status: "in_progress", content: [] } });
      emit({ type: "response.output_text.delta", output_index: 0, item_id: item.id, content_index: 0, delta: text });
      emit({ type: "response.output_text.done", output_index: 0, item_id: item.id, content_index: 0, text });
      emit({ type: "response.output_item.done", output_index: 0, item });
      emit({ type: "response.completed", response: { id, status: "completed", output: [item], usage: { input_tokens: 1, output_tokens: 1 } } });
      return;
    }
    if (!exec || !manualTools.includes("Bash")) {
      errors.push("exec custom tool / tools.Bash declaration missing from actual manual");
      emit({ type: "response.failed", response: { id: "bad_manual", status: "failed", error: { code: "invalid_request_error", message: "probe requires exec manual with tools.Bash" } } });
      return;
    }
    requests.at(-1)!.requestKind = "fixture-turn";
    if (++finals > 12 || scripts > 3) throw new Error("bounded request budget exceeded");
    const id = outputs.length ? `answer_${finals}` : `script_${scripts + 1}`;
    emit({ type: "response.created", response: { id, status: "in_progress", output: [] } });
    if (outputs.length) {
      // Keep the real client alive until the first round's delayed failure/completion has happened.
      // This is one-way bounded gating: the first round never waits for this request or the tool.
      await firstTerminal;
      const item = { type: "message", id: `msg_${finals}`, role: "assistant", status: "completed", content: [{ type: "output_text", text: FINAL, annotations: [] }] };
      emit({ type: "response.output_item.added", output_index: 0, item: { ...item, content: [] } });
      emit({ type: "response.content_part.added", output_index: 0, item_id: item.id, content_index: 0, part: { type: "output_text", text: "", annotations: [] } });
      emit({ type: "response.output_text.delta", output_index: 0, item_id: item.id, content_index: 0, delta: FINAL });
      emit({ type: "response.output_text.done", output_index: 0, item_id: item.id, content_index: 0, text: FINAL });
      emit({ type: "response.output_item.done", output_index: 0, item });
      completedResponses.set(id, { definitions: defs, reasoningPresent: !!prior?.reasoningPresent || input.some(i => i.type === "reasoning" && i.encrypted_content === ENVELOPE) });
      emit({ type: "response.completed", response: { id, status: "completed", output: [item], usage: { input_tokens: 100, output_tokens: 10 } } });
      mark("final_response_sent"); return;
    }
    scripts++;
    // Stable call/item identities on a retry, with an intentionally unguarded append effect.
    const reasoning = { type: "reasoning", id: "rs_probe", summary: [{ type: "summary_text", text: SUMMARY }], encrypted_content: ENVELOPE };
    emit({ type: "response.output_item.added", output_index: 0, item: { ...reasoning, summary: [] } });
    emit({ type: "response.reasoning_summary_part.added", output_index: 0, item_id: reasoning.id, summary_index: 0, part: { type: "summary_text", text: "" } });
    emit({ type: "response.reasoning_summary_text.delta", output_index: 0, item_id: reasoning.id, summary_index: 0, delta: SUMMARY });
    mark("summary_sent"); await Bun.sleep(50);
    emit({ type: "response.reasoning_summary_text.done", output_index: 0, item_id: reasoning.id, summary_index: 0, text: SUMMARY });
    emit({ type: "response.reasoning_summary_part.done", output_index: 0, item_id: reasoning.id, summary_index: 0, part: reasoning.summary[0] });
    emit({ type: "response.output_item.done", output_index: 0, item: reasoning });
    // task comes from mkdtemp, shell-quote nevertheless; no substitution of user-provided shell code.
    const quoted = "'" + effects.replaceAll("'", "'\\''") + "'";
    const script = `text(await tools.Bash(${JSON.stringify({ command: `date +%s%3N >> ${quoted}`, description: "Record deterministic probe callback timestamp", timeout: 10000 })}));`;
    const item: Obj = { type: "custom_tool_call", id: "exec_probe", call_id: "call_probe", name: "exec", input: script, status: "completed" };
    if (exec.namespace) item.namespace = exec.namespace;
    emit({ type: "response.output_item.added", output_index: 1, item: { ...item, input: "", status: "in_progress" } });
    // No tool may execute on this incomplete item.
    emit({ type: "response.custom_tool_call_input.delta", output_index: 1, item_id: item.id, delta: script.slice(0, Math.floor(script.length / 2)) });
    await Bun.sleep(100);
    emit({ type: "response.custom_tool_call_input.delta", output_index: 1, item_id: item.id, delta: script.slice(Math.floor(script.length / 2)) });
    emit({ type: "response.custom_tool_call_input.done", output_index: 1, item_id: item.id, input: script });
    emit({ type: "response.output_item.done", output_index: 1, item }); mark("exec_item_done", { attempt: scripts });
    await Bun.sleep(HOLD_MS);
    if (name === "late-failure" && lateFailures === 0) {
      lateFailures++; mark("late_failure_sent");
      emit({ type: "response.failed", response: { id, status: "failed", error: { code: "server_error", message: "scripted late probe failure" }, output: [reasoning, item] } });
    } else {
      mark("response_completed_sent", { attempt: scripts });
      completedResponses.set(id, { definitions: defs, reasoningPresent: true });
      emit({ type: "response.completed", response: { id, status: "completed", output: [reasoning, item], usage: { input_tokens: 100, output_tokens: 30 } } });
    }
    releaseFirstTerminal();
  };
  const schedule = (b: Obj, send: (e: Obj) => void): Promise<void> => {
    const p = run(b, send).catch(() => { releaseFirstTerminal(); errors.push("mock stream failed or request budget exceeded"); });
    pending.add(p); void p.finally(() => pending.delete(p)); return p;
  };
  const upstream = Bun.serve<{ transport: string }>({ hostname: "127.0.0.1", port: 0, idleTimeout: 30,
    fetch: async (req, server) => {
      const u = new URL(req.url);
      if (u.pathname.endsWith("/models")) return Response.json({ object: "list", data: [{ id: MODEL, slug: MODEL, object: "model", display_name: MODEL, context_window: 200000, max_context_window: 200000, tool_mode: "code_mode_only", supported_reasoning_levels: [{ effort: "max", description: "max" }] }], models: [{ slug: MODEL, display_name: MODEL, context_window: 200000, tool_mode: "code_mode_only" }] });
      if (!u.pathname.endsWith("/responses")) return new Response("probe endpoint only", { status: 404 });
      if (req.headers.get("upgrade")?.toLowerCase() === "websocket") {
        if (server.upgrade(req, { data: { transport: "ws" } })) return;
        return new Response("upgrade failed", { status: 400 });
      }
      let raw = Buffer.from(await req.arrayBuffer());
      if (req.headers.get("content-encoding") === "zstd") raw = Buffer.from(Bun.zstdDecompressSync(raw));
      const b = JSON.parse(raw.toString());
      return new Response(new ReadableStream<Uint8Array>({ start(c) {
        void schedule(b, e => c.enqueue(new TextEncoder().encode(`data: ${JSON.stringify(e)}\n\n`))).finally(() => { try { c.close(); } catch {} });
      } }), { headers: { "Content-Type": "text/event-stream", "Cache-Control": "no-cache" } });
    },
    websocket: { message(ws, message) {
      const b = JSON.parse(typeof message === "string" ? message : Buffer.from(message).toString());
      void schedule(b, e => { ws.send(JSON.stringify(e)); });
    } },
  });
  const controlPort = await freePort(); const headPort = await freePort();
  const config = join(home, "splice.toml"); const auth = join(home, "synthetic-auth.json");
  // auth=none cannot activate the Codex code-mode arm (Topology.codeModeShape).
  writeFileSync(auth, JSON.stringify({ tokens: { access_token: "synthetic-probe-only", account_id: "probe-account" } }), { mode: 0o600 });
  writeFileSync(config, `[daemon]\ncontrol_port = ${controlPort}\nstate_dir = ${JSON.stringify(join(home, "state"))}\nshow_reasoning = "text"\nsummary = "detailed"\neffort = "max"\nreplay_reasoning = true\nmirror_reasoning = false\nfold_reasoning_models = []\nmcp_hosting = false\nmcp_share = false\nauto_update = false\n[providers.probe]\ndialect = "openai-responses"\nbase_url = "http://127.0.0.1:${upstream.port}"\nauth = { kind = "chatgpt-oauth", file = ${JSON.stringify(auth)} }\nmodels = [{ id = "${MODEL}", context_window = 200000 }]\n[providers.probe.quirks]\ncode_mode = true\ncode_mode_models = ["${MODEL}"]\nwebsocket = true\nwebsocket_prefill = false\nzstd_request_body = false\nsummary_delivery = "off"\n[heads.probe]\nprovider = "probe"\nport = ${headPort}\ndiscovery_prefix = "probe--"\npinned_model = "${MODEL}"\n`, { mode: 0o600 });

  // A transparent local Anthropic relay observes exact candidate output, independently of CLI rendering.
  const relay = Bun.serve({ hostname: "127.0.0.1", port: 0, idleTimeout: 120, async fetch(req) {
    const path = new URL(req.url).pathname;
    if (!path.startsWith("/v1/")) return new Response("probe relay only", { status: 404 });
    clientVersion ??= req.headers.get("user-agent");
    const headers = new Headers(req.headers); headers.delete("host");
    headers.set("authorization", `Bearer ${readFileSync(join(home, "state/mgmt-key"), "utf8").trim()}`);
    headers.delete("x-api-key");
    const response = await fetch(`http://127.0.0.1:${headPort}${path}`, { method: req.method, headers,
      body: req.method === "GET" ? undefined : await req.arrayBuffer(), signal: AbortSignal.timeout(90000) });
    if (!response.body) return response;
    let buffer = ""; const decoder = new TextDecoder();
    const inspect = (line: string) => {
      const e = parseEvents(line); if (!e) return; clientEvents++;
      if (e.type === "content_block_delta" && e.delta?.type === "thinking_delta") {
        wireThinking += e.delta.thinking ?? ""; wireThinkingDeltas++;
      }
      if (e.content_block?.type === "redacted_thinking") redacted++;
      const text = e.delta?.thinking ?? e.delta?.text ?? e.content_block?.text ?? "";
      // Retain a rolling suffix only; never persist message or signature bodies.
      visibleTail = (visibleTail + text).slice(-2000);
      if (visibleTail.includes(SUMMARY) && summaryVisibleAt === null) { summaryVisibleAt = Date.now(); mark("readable_summary_on_client_wire"); }
      if (visibleTail.includes(FINAL)) finalVisible = true;
      if (e.content_block?.type === "tool_use" && toolOpenAt === null) { toolOpenAt = Date.now(); mark("tool_open_on_client_wire", { name: e.content_block.name }); }
    };
    return new Response(new ReadableStream<Uint8Array>({ async start(c) {
      try { for await (const chunk of response.body!) {
        buffer += decoder.decode(chunk, { stream: true });
        const lines = buffer.split("\n"); buffer = lines.pop() ?? "";
        for (const line of lines) inspect(line); c.enqueue(chunk);
      } if (buffer) inspect(buffer); c.close(); } catch { c.error(new Error("probe relay stream ended")); }
    } }), { status: response.status, headers: response.headers });
  } });
  let visibleTail = "";
  let cliSummary = false, cliRedacted = 0, cliFinal = false, cliResultError = false, cliTerminalThinking = false;
  let cliExit: number | null = null;
  try {
    const env = safeEnv(home);
    daemon = child(["java", "-Dsplice.noSystemBrowser=1", `-Duser.home=${home}`, "-jar", jar, "daemon"], task, { ...env, SPLICE_CONFIG: config, SPLICE_STATE_DIR: join(home, "state") });
    daemon.stdout?.on("data", () => {});
    let ready = false;
    for (let i = 0; i < 240; i++) {
      if (daemon.exitCode !== null) throw new Error("isolated daemon exited before readiness");
      if (existsSync(join(home, "state/mgmt-key"))) {
        try { const r = await fetch(`http://127.0.0.1:${headPort}/v1/models`, { headers: { authorization: `Bearer ${readFileSync(join(home, "state/mgmt-key"), "utf8").trim()}` }, signal: AbortSignal.timeout(500) }); if (r.ok) { ready = true; break; } } catch {}
      }
      await Bun.sleep(250);
    }
    if (!ready) throw new Error("isolated head not ready in 60 seconds");
    mark("daemon_ready");
    const sessionId = randomUUID();
    writeFileSync(join(home, "claude/.claude.json"), JSON.stringify({
      hasCompletedOnboarding: true, theme: "dark", hasAcknowledgedCostThreshold: true,
      projects: { [task]: { hasTrustDialogAccepted: true, hasCompletedProjectOnboarding: true } },
    }), { mode: 0o600 });
    const client = await interactiveClient({
      command: [CLIENT, "--bare", "--verbose", "--permission-mode", "default", "--model", `probe--${MODEL}`, "--tools", "Bash", "--allowedTools", "Bash", "--strict-mcp-config", "--mcp-config", '{"mcpServers":{}}', "--setting-sources", "", "--session-id", sessionId, "--system-prompt", "You are running a deterministic local fixture. Execute the requested Bash tool and report its result.", "Run the fixture tool once, then finish."],
      cwd: task, env: { ...env, ANTHROPIC_BASE_URL: `http://127.0.0.1:${relay.port}`, ANTHROPIC_API_KEY: "synthetic-probe-client-only", API_TIMEOUT_MS: "90000" },
      sessionId, finalText: FINAL, expectedThinking: SUMMARY, timeoutMs: 90000,
    });
    cliExit = client.exitCode; cliThinking = client.thinking;
    cliSummary = cliThinking.includes(SUMMARY); cliRedacted = client.redactedBlocks;
    cliFinal = client.finalInTranscript && client.finalOnTerminal;
    cliTerminalThinking = client.thinkingOnTerminal;
    cliResultError = client.timedOut || !client.transcriptFound;
    if (client.timedOut) errors.push("interactive client timed out after 90 seconds");
    mark("interactive_client_finished", { terminalBytes: client.terminalBytes, transcriptFound: client.transcriptFound });
    await Promise.all([...pending]);
  } catch (e) { errors.push(e instanceof Error ? e.message : "probe infrastructure failure"); }
  finally { releaseFirstTerminal(); await stop(daemon); await upstream.stop(true); await relay.stop(true); }

  const callbackTimes = existsSync(effects) ? readFileSync(effects, "utf8").trim().split("\n").filter(Boolean).map(Number) : [];
  const done = marks.find(m => m.event === "exec_item_done")?.epochMs;
  const terminal = marks.find(m => m.event === "response_completed_sent" || m.event === "late_failure_sent")?.epochMs;
  const effect = callbackTimes[0];
  const checks = {
    clientExitedSuccessfully: cliExit === 0 && !cliResultError,
    readableReasoningOnWire: summaryVisibleAt !== null,
    readableReasoningInRealClient: cliSummary,
    readableReasoningReceivedByTerminal: cliTerminalThinking,
    fixtureThinkingExactlyOnceOnWire: wireThinking === SUMMARY,
    fixtureThinkingExactlyOnceInClientTranscript: cliThinking === SUMMARY,
    noRedactedPlaceholders: redacted === 0 && cliRedacted === 0,
    exactlyOneEffect: callbackTimes.length === 1 && Number.isFinite(effect),
    toolRanAfterWholeItemBeforeTerminal: effect !== undefined && done !== undefined && terminal !== undefined && effect >= done && effect < terminal,
    summaryDeliveredBeforeTerminal: summaryVisibleAt !== null && terminal !== undefined && summaryVisibleAt < terminal,
    finalAnswerInRealClient: finalVisible && cliFinal,
    replayReasoningOnNextRequest: requests.some((r, i) => i > 0 && r.toolOutputs > 0 && r.effectiveFixtureReasoningPresent),
    lateFailureInjected: name !== "late-failure" || lateFailures === 1,
    noInfrastructureErrors: errors.length === 0,
  };
  return { scenario: name, passed: Object.values(checks).every(Boolean), checks, errors, taskDir: task,
    clientUserAgent: clientVersion, cliExit, holdMs: HOLD_MS, callbackTimes, scriptAttempts: scripts,
    upstreamRequests: requests, clientEvents, redactedBlocks: redacted, cliRedactedBlocks: cliRedacted,
    thinkingDelivery: {
      expectedChars: SUMMARY.length,
      wire: { chars: wireThinking.length, deltaEvents: wireThinkingDeltas, fixtureOccurrences: wireThinking.split(SUMMARY).length - 1, exactFixtureEquality: wireThinking === SUMMARY },
      clientTranscript: { chars: cliThinking.length, fixtureOccurrences: cliThinking.split(SUMMARY).length - 1, exactFixtureEquality: cliThinking === SUMMARY },
    },
    timing: { summaryVisibleAt, toolOpenAt, itemDoneAt: done ?? null, terminalAt: terminal ?? null,
      callbackMinusItemDoneMs: effect !== undefined && done !== undefined ? effect - done : null,
      terminalMinusCallbackMs: effect !== undefined && terminal !== undefined ? terminal - effect : null }, marks,
    coverage: "Success and late response.failed, bounded automatic retry if candidate/client retries. No forced retry and no restart/resume coverage. Real interactive PTY with persisted client blocks and terminal final-answer observation; no paint-latency claim." };
}

export async function main(argv: string[]): Promise<number> {
  const jarIndex = argv.indexOf("--jar"), outIndex = argv.indexOf("--out");
  if (jarIndex < 0 || outIndex < 0 || !argv[jarIndex + 1] || !argv[outIndex + 1]) {
    console.error("Usage: bun probe-client.ts --jar PATH --out PATH"); return 2;
  }
  const jar = resolve(argv[jarIndex + 1]!); const out = resolve(argv[outIndex + 1]!);
  if (existsSync(out)) throw new Error("receipt already exists; choose a fresh --out path to preserve evidence");
  if (!existsSync(jar)) throw new Error("candidate jar does not exist");
  const root = mkdtempSync(join(tmpdir(), "splice-client-probe-"));
  const env = safeEnv(root); mkdirSync(env.TMPDIR!, { recursive: true });
  const v = Bun.spawnSync([CLIENT, "--version"], { cwd: root, env, stdout: "pipe", stderr: "pipe" });
  const version = v.stdout.toString().trim();
  if (v.exitCode !== 0 || !version.startsWith("2.1.310 ")) throw new Error("required Claude Code version is not 2.1.310");
  const receipt: Obj = { kind: "splice-real-client-parity-probe", startedAt: new Date().toISOString(),
    probeVersion: PROBE_VERSION, probeSha256: await sha(fileURLToPath(import.meta.url)),
    invocationMode: "interactive-pty-v1", terminalColumns: 160, terminalRows: 60,
    permissionMode: "default", allowedTools: ["Bash"],
    interactiveClientSha256: await sha(fileURLToPath(new URL("./interactive-client.ts", import.meta.url))),
    jar, jarSha256: await sha(jar), clientPath: CLIENT, clientVersion: version, clientSha256: await sha(CLIENT),
    root, status: "running", scenarios: [], auth: "isolated synthetic OAuth fixture required for Codex arm; no inherited credentials", limits: "Loopback configuration, not an OS network sandbox. Candidate jar is trusted to honor configured endpoints." };
  mkdirSync(dirname(out), { recursive: true });
  const save = () => writeFileSync(out, JSON.stringify(receipt, null, 2) + "\n", { mode: 0o600 });
  save();
  for (const name of ["success", "late-failure"] as const) {
    const result = await scenario(jar, name, root); receipt.scenarios.push(result); save();
    console.log(`${name}: ${result.passed ? "PASS" : "FAIL"} (${Object.entries(result.checks).filter(([, ok]) => !ok).map(([key]) => key).join(", ") || "all checks"})`);
  }
  receipt.status = receipt.scenarios.every((s: Obj) => s.passed) ? "passed" : "failed";
  receipt.finishedAt = new Date().toISOString(); save(); console.log(`receipt: ${out}`);
  return receipt.status === "passed" ? 0 : 1;
}
if (import.meta.main) process.exitCode = await main(process.argv.slice(2));
