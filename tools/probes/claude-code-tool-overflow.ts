// NEW: V4-446 — offline real-client tool-loop overflow against two real Splice jars.
// bun tools/probes/claude-code-tool-overflow.ts CLIENT PRE_GATE_JAR GATED_JAR [delayMs]
import { spawn, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { createServer, request as httpRequest } from "node:http";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const limitChars = 20_000;
const windowTokens = 272_000;
const reserveTokens = 27_611; // gpt-5.6-sol bundled calibration: 7830 + 19781
const usage = { prompt_tokens: 100, completion_tokens: 30, total_tokens: 130 };
type Profile = "overflow" | "reserve" | "oversize";
type Seen = { status: number; retry: string; summary: boolean; stream: boolean;
  bytes: number; atMs: number; progressAtMs?: number; error?: string };
function sse(model: string, deltas: object[], stop: string, bill = usage): Response {
  const frame = (delta: object, reason: string | null, end = false): string =>
    "data: " + JSON.stringify({ id: "chatcmpl-local", object: "chat.completion.chunk", model,
      choices: [{ index: 0, delta, finish_reason: reason }], ...(end ? { usage: bill } : {}) }) + "\n\n";
  return new Response(frame({ role: "assistant", content: null }, null)
    + deltas.map((item) => frame(item, null)).join("")
    + frame({}, stop, true) + "data: [DONE]\n\n",
  { headers: { "Content-Type": "text/event-stream" } });
}
function reply(model: string, stream: boolean, text: string, bill = usage): Response {
  if (stream) return sse(model, [{ content: text }], "stop", bill);
  return Response.json({ choices: [{ index: 0,
    message: { role: "assistant", content: text }, finish_reason: "stop" }], usage: bill });
}
function tool(model: string, stream: boolean, n: number, bill = usage): Response {
  const args = JSON.stringify({ command: "bun -e 'process.stdout.write(\"Q\".repeat(25000))'",
    description: "Emit synthetic local output" });
  const id = "call_local_" + n;
  if (stream) return sse(model, [
    { tool_calls: [{ index: 0, id, type: "function", function: { name: "Bash", arguments: "" } }] },
    { tool_calls: [{ index: 0, function: { arguments: args } }] },
  ], "tool_calls", bill);
  return Response.json({ choices: [{ index: 0, message: { role: "assistant", content: null,
    tool_calls: [{ id, type: "function", function: { name: "Bash", arguments: args } }] },
    finish_reason: "tool_calls" }], usage: bill });
}
async function port(): Promise<number> {
  const server = createServer();
  await new Promise<void>((done) => server.listen(0, "127.0.0.1", done));
  const address = server.address();
  if (!address || typeof address === "string") throw new Error("no loopback port");
  await new Promise<void>((done) => server.close(() => done()));
  return address.port;
}
async function ready(head: number, child: ReturnType<typeof spawn>, log: () => string): Promise<void> {
  for (let i = 0; i < 120; i++) {
    if (child.exitCode !== null) throw new Error("daemon exited: " + log().slice(-300));
    try { if ((await fetch("http://127.0.0.1:" + head + "/health")).ok) return; } catch {}
    await Bun.sleep(250);
  }
  throw new Error("daemon did not open head: " + log().slice(-300));
}
async function inside(client: string, jar: string, delayMs: number, profile: Profile): Promise<object> {
  const beganAt = performance.now();
  const measuredWindow = profile !== "overflow";
  const dir = mkdtempSync(join(tmpdir(), "splice-tool-overflow-"));
  const headPort = await port();
  const controlPort = await port();
  let calls = 0;
  let summaries = 0;
  let summaryAttempts = 0;
  let delayed = false;
  let overflowAtMs: number | null = null;
  const streamCancellations: number[] = [];
  let maxToolChars = 0;
  const upstreamInputs: Array<{ stream: boolean; q: number; messageCount: number;
    bytes: number; estimatedTokens: number; compact: boolean }> = [];
  const upstream = Bun.serve({ hostname: "127.0.0.1", port: 0, fetch: async (request, server) => {
    const path = new URL(request.url).pathname;
    if (path.endsWith("/models")) return Response.json({ object: "list", data: [{ id: "mock-chat" }] });
    if (!path.endsWith("/chat/completions")) return Response.json({ error: "not found" }, { status: 404 });
    const body = await request.json() as { model?: string; stream?: boolean; system?: unknown;
      messages?: Array<{ role?: string; content?: unknown }> };
    const messages = body.messages ?? [];
    const serialized = JSON.stringify(body);
    const toolChars = serialized.split("Q").length - 1;
    const estimatedTokens = Math.ceil(Buffer.byteLength(serialized) / 4);
    const summary = /summari|compact/i.test(JSON.stringify([body.system, messages]));
    upstreamInputs.push({ stream: body.stream === true, q: toolChars,
      messageCount: messages.length, bytes: Buffer.byteLength(serialized), estimatedTokens,
      compact: summary });
    maxToolChars = Math.max(maxToolChars, toolChars);
    const bill = measuredWindow && profile === "reserve"
      ? { prompt_tokens: estimatedTokens, completion_tokens: 30, total_tokens: estimatedTokens + 30 }
      : usage;
    if (summaries) return reply(body.model ?? "mock-chat", Boolean(body.stream), "RECOVERED", bill);
    if (summary) {
      summaryAttempts++;
      if (measuredWindow && estimatedTokens > windowTokens) {
        return Response.json({ error: { code: "context_length_exceeded",
          message: "prompt is too long: summary input exceeds synthetic window" } }, { status: 400 });
      }
      summaries++;
      return reply(body.model ?? "mock-chat", Boolean(body.stream), "Local summary.", bill);
    }
    if (measuredWindow && estimatedTokens > windowTokens) {
      overflowAtMs ??= Math.round(performance.now() - beganAt);
      return Response.json({ error: { code: "context_length_exceeded",
        message: "prompt is too long: synthetic total input exceeds model context" } }, { status: 400 });
    }
    if (!measuredWindow && toolChars >= limitChars) {
      const error = { error: { code: "context_length_exceeded",
        message: "prompt is too long: synthetic tool output exceeds model context" } };
      if (delayMs && !delayed && body.stream) {
        delayed = true;
        server.timeout(request, 0); // quiet SSE must outlive Bun's default idle cutoff
        let canceled = false;
        const stream = new ReadableStream<Uint8Array>({
          start(controller) {
            // Deliver upstream headers/bytes at once, but no model event until the deadline.
            controller.enqueue(new TextEncoder().encode(": upstream ready\n\n"));
            setTimeout(() => {
              if (canceled) return;
              overflowAtMs ??= Math.round(performance.now() - beganAt);
              controller.enqueue(new TextEncoder().encode("data: " + JSON.stringify(error) + "\n\n"));
              controller.close();
            }, delayMs);
          },
          cancel() {
            canceled = true;
            streamCancellations.push(Math.round(performance.now() - beganAt));
          },
        });
        return new Response(stream, { headers: { "Content-Type": "text/event-stream" } });
      }
      overflowAtMs ??= Math.round(performance.now() - beganAt);
      return Response.json(error, { status: 400 });
    }
    if (++calls > (measuredWindow ? 80 : 12)) {
      return reply(body.model ?? "mock-chat", Boolean(body.stream), "NO_OVERFLOW", bill);
    }
    return tool(body.model ?? "mock-chat", Boolean(body.stream), calls, bill);
  } });
  const config = join(dir, "splice.toml");
  writeFileSync(config, "[daemon]\ncontrol_port = " + controlPort
    + "\n[providers.mock]\ndialect = \"openai-chat\"\nbase_url = \"http://127.0.0.1:" + upstream.port
    + "\"\nauth = { kind = \"api-key\", env = \"MOCK_CHAT_API_KEY\" }\n"
    + "[[providers.mock.models]]\nid = \"mock-chat\"\ncontext_window = "
    + (measuredWindow ? windowTokens : 32000) + "\n"
    + (measuredWindow ? "compaction_reserve_tokens = " + reserveTokens + "\n" : "")
    + "[heads.mock]\nprovider = \"mock\"\nport = " + headPort
    + "\ndiscovery_prefix = \"claude-mock--\"\npinned_model = \"mock-chat\"\n"
    + "[heads.mock.claude]\ncommand = \"claude-mock\"\n");
  const env: NodeJS.ProcessEnv = {};
  for (const name of ["PATH", "LANG", "LC_ALL", "TERM", "JAVA_HOME"])
    if (process.env[name]) env[name] = process.env[name];
  Object.assign(env, { HOME: dir, CLAUDE_CONFIG_DIR: join(dir, "claude"),
    SPLICE_CONFIG: config, SPLICE_STATE_DIR: join(dir, "state"), CLAUDEX_STATE_DIR: join(dir, "state"),
    MOCK_CHAT_API_KEY: "local-fixture-key", ANTHROPIC_API_KEY: "LOCAL_FIXTURE_PLACEHOLDER",
    DISABLE_TELEMETRY: "1", DISABLE_ERROR_REPORTING: "1", DISABLE_AUTOUPDATER: "1",
    CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC: "1" });
  let log = "";
  const daemon = spawn("java", ["-Xmx1024m", "-jar", jar, "daemon"], { cwd: dir, env,
    stdio: ["ignore", "pipe", "pipe"] });
  for (const stream of [daemon.stdout, daemon.stderr])
    stream.on("data", (part: Buffer) => { log = (log + part.toString()).slice(-4000); });
  const seen: Seen[] = [];
  const proxy = createServer((req, res) => {
    const parts: Buffer[] = [];
    req.on("data", (part: Buffer) => parts.push(part));
    req.on("end", () => {
      const bytes = Buffer.concat(parts);
      if (measuredWindow && req.url?.includes("/count_tokens")) {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ input_tokens: profile === "reserve" ? Math.ceil(bytes.length / 4) : 100 }));
        return;
      }
      const headers = { ...req.headers, host: "127.0.0.1:" + headPort, "content-length": String(bytes.length) };
      delete headers["transfer-encoding"];
      const forwarded = httpRequest({ hostname: "127.0.0.1", port: headPort, path: req.url,
        method: req.method, headers }, (reply) => {
        if (req.url?.startsWith("/v1/messages") && !req.url.includes("count_tokens")) {
          const body = JSON.parse(bytes.toString()) as { system?: unknown; messages?: unknown; stream?: boolean };
          const recorded: Seen = { status: reply.statusCode ?? 0,
            retry: String(reply.headers["x-should-retry"] ?? ""),
            summary: /summari|compact/i.test(JSON.stringify([body.system, body.messages])),
            stream: body.stream === true, bytes: bytes.length,
            atMs: Math.round(performance.now() - beganAt) };
          let wire = "";
          reply.on("data", (part: Buffer) => {
            wire = (wire + part.toString()).slice(-2000);
            if (recorded.progressAtMs === undefined && wire.includes("[splice] holding this turn open")) {
              recorded.progressAtMs = Math.round(performance.now() - beganAt);
            }
            if ((reply.statusCode ?? 0) >= 400) recorded.error = wire.slice(0, 250);
          });
          seen.push(recorded);
        }
        res.writeHead(reply.statusCode ?? 502, reply.headers);
        reply.pipe(res);
      });
      forwarded.on("error", () => { res.writeHead(502); res.end(); });
      forwarded.end(bytes);
    });
  });
  let compactBoundary = false;
  let resultError: boolean | null = null;
  let resultText = "";
  let clientExit: number | null = null;
  let failure: string | undefined;
  try {
    await ready(headPort, daemon, () => log);
    await new Promise<void>((done) => proxy.listen(0, "127.0.0.1", done));
    const address = proxy.address();
    if (!address || typeof address === "string") throw new Error("no proxy port");
    const headKey = readFileSync(join(dir, "state", "mgmt-key"), "utf8").trim();
    const child = spawn(client, ["--bare", "--strict-mcp-config", "--tools", "Bash",
      "--permission-mode", "bypassPermissions", "--model",
      measuredWindow ? "mock-chat" : "claude-mock--mock-chat",
      "--system-prompt", "Offline local fixture. Execute the requested Bash calls.",
      "-p", "--verbose", "--input-format", "stream-json", "--output-format", "stream-json",
    ], { cwd: dir, env: { ...env, ANTHROPIC_API_KEY: headKey,
      ...(measuredWindow ? { CLAUDE_CODE_MAX_CONTEXT_TOKENS: String(windowTokens) } : {}),
      ANTHROPIC_BASE_URL: "http://127.0.0.1:" + address.port },
      stdio: ["pipe", "pipe", "pipe"] });
    child.stdin.write(JSON.stringify({ type: "user",
      message: { role: "user", content: "Run the requested local tool until RECOVERED." } }) + "\n");
    let buffer = "";
    let stderr = "";
    child.stderr.on("data", (part: Buffer) => { stderr = (stderr + part.toString()).slice(-500); });
    child.stdout.on("data", (part: Buffer) => {
      buffer += part.toString();
      while (buffer.includes("\n")) {
        const at = buffer.indexOf("\n");
        const line = buffer.slice(0, at);
        buffer = buffer.slice(at + 1);
        try {
          const event = JSON.parse(line) as { type?: string; subtype?: string;
            result?: string; is_error?: boolean };
          if (event.subtype === "compact_boundary") compactBoundary = true;
          if (event.type === "result") {
            resultError = event.is_error === true;
            resultText = String(event.result ?? "").slice(0, 160);
            child.stdin.end();
          }
        } catch {}
      }
    });
    const timer = setTimeout(() => child.kill("SIGTERM"), measuredWindow ? 450_000 : Math.max(100_000, delayMs + 80_000));
    clientExit = await new Promise<number | null>((done) => child.once("close", done));
    clearTimeout(timer);
    if (resultError === null && stderr) failure = "client exited without result: " + stderr.slice(-250);
  } catch (error) {
    failure = String(error).slice(0, 300);
  } finally {
    proxy.close();
    upstream.stop(true);
    daemon.kill("SIGTERM");
    await Promise.race([new Promise<void>((done) => daemon.once("close", () => done())), Bun.sleep(3000)]);
  }
  const digest = (file: string) => createHash("sha256").update(readFileSync(file)).digest("hex");
  return { jarSha256: digest(jar), clientSha256: digest(client), delayMs, profile,
    ...(measuredWindow ? { windowTokens, reserveTokens, reservePoint: windowTokens - reserveTokens } : {}),
    calls, maxToolChars, overflowAtMs, streamCancellations, upstreamInputs, seen,
    summaries, summaryAttempts, compactBoundary, resultError, resultText, clientExit,
    headSignals: log.split("\n").filter((line) => /turn ERROR|watchdog|first.byte|upstream stream|retry|reissue|conn-reset/i.test(line)).slice(-12).map((line) => line.slice(0, 180)),
    ...(failure ? { failure } : {}) };
}
async function main(): Promise<void> {
  const [clientArg, baselineArg, gatedArg, delayArg, marker, isolatedProfile] = process.argv.slice(2);
  if (!clientArg || !baselineArg || !gatedArg) throw new Error("CLIENT PRE_GATE_JAR GATED_JAR [delayMs] [reserve|oversize]");
  const client = resolve(clientArg);
  const baseline = resolve(baselineArg);
  const gated = resolve(gatedArg);
  const delayMs = Number(delayArg ?? "0");
  const profile = (marker === "--isolated" ? isolatedProfile : marker) as Profile | undefined ?? "overflow";
  if (!["overflow", "reserve", "oversize"].includes(profile)) throw new Error("invalid profile: " + profile);
  if (marker === "--isolated") { process.stdout.write(JSON.stringify(await inside(client, baseline, delayMs, profile)) + "\n"); return; }
  const script = fileURLToPath(import.meta.url);
  const env: NodeJS.ProcessEnv = {};
  for (const name of ["PATH", "LANG", "LC_ALL", "TERM", "JAVA_HOME"])
    if (process.env[name]) env[name] = process.env[name];
  for (const jar of baseline === gated ? [baseline] : [baseline, gated]) {
    const run = spawnSync("bwrap", ["--unshare-net", "--ro-bind", "/", "/", "--tmpfs", "/tmp",
      "--dev-bind", "/dev", "/dev", "--proc", "/proc", "--chdir", "/tmp",
      "--setenv", "HOME", "/tmp", "--", process.execPath, script, client, jar, jar,
      String(delayMs), "--isolated", profile], { env, encoding: "utf8",
      timeout: profile === "overflow" ? Math.max(150_000, delayMs + 120_000) : 480_000 });
    if (run.status !== 0) {
      process.stdout.write(JSON.stringify({ jar, exit: run.status, error: String(run.stderr ?? run.error).slice(-500) }) + "\n");
      process.exitCode = 1;
    } else {
      process.stdout.write(run.stdout);
      const result = JSON.parse(run.stdout) as { failure?: string; maxToolChars: number;
        compactBoundary: boolean; resultError: boolean | null; resultText: string;
        clientExit: number | null; overflowAtMs: number | null; seen: Seen[];
        upstreamInputs: Array<{ compact: boolean; estimatedTokens: number; messageCount: number }> };
      const toolTurns = result.upstreamInputs.filter((entry) => !entry.compact && entry.messageCount > 3);
      const sawReserveTurn = toolTurns.some((entry) => entry.estimatedTokens > windowTokens - reserveTokens
        && entry.estimatedTokens <= windowTokens);
      const sawOversizeTurn = toolTurns.some((entry) => entry.estimatedTokens > windowTokens);
      const sawSummary = result.upstreamInputs.some((entry) => entry.compact);
      const progressBeforeOverflow = result.seen.some((reply) => reply.progressAtMs !== undefined
        && result.overflowAtMs !== null && reply.progressAtMs < result.overflowAtMs);
      const valid = profile === "reserve" ? sawReserveTurn && sawSummary && result.compactBoundary
        : profile === "oversize" ? sawOversizeTurn && sawSummary && result.compactBoundary
          : result.maxToolChars >= limitChars && result.compactBoundary
            && result.resultError === false && result.resultText === "RECOVERED"
            && (delayMs > 0 ? progressBeforeOverflow : result.seen.some((reply) => reply.status === 400));
      if (result.failure || !valid || result.clientExit === null) process.exitCode = 1;
    }
  }
}
await main();
