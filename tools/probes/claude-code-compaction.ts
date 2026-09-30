// NEW: V4-446 — re-runnable local proof of Claude Code's prompt-too-long compaction reaction.
// Run with: bun tools/probes/claude-code-compaction.ts /absolute/path/to/claude
// Every case is launched under bwrap --unshare-net. No provider traffic or real key is possible.
import { spawn, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { readFileSync, realpathSync } from "node:fs";
import { createServer } from "node:http";
import { fileURLToPath } from "node:url";

type Mode = "prompt_too_long" | "unrelated_400" | "first_exchange";
type Seen = { reply: number; summary: boolean };
type Result = { mode: Mode; pass: boolean; replies: number[]; summaryRequests: boolean[]; compactBoundary: boolean; clientExit: number | null };
const modes: Mode[] = ["prompt_too_long", "unrelated_400", "first_exchange"];
const timeoutMs = 70_000;

async function isolated(client: string, mode: Mode): Promise<Result> {
  const seen: Seen[] = [];
  const server = createServer(async (request, response) => {
    const chunks: Buffer[] = [];
    for await (const chunk of request) chunks.push(Buffer.from(chunk));
    let body: Record<string, unknown> = {};
    try { body = JSON.parse(Buffer.concat(chunks).toString("utf8")); } catch { /* malformed fixture request */ }
    function answer(status: number, value: string, type = "application/json"): void {
      response.writeHead(status, { "Content-Type": type, "Content-Length": Buffer.byteLength(value) });
      response.end(value);
    }
    if (request.url?.startsWith("/v1/messages/count_tokens")) {
      answer(200, JSON.stringify({ input_tokens: 100 }));
      return;
    }
    if (!request.url?.startsWith("/v1/messages")) {
      answer(404, JSON.stringify({ type: "error", error: { type: "not_found_error", message: "local fixture only" } }));
      return;
    }
    const attempt = seen.length + 1;
    const prompt = JSON.stringify([body.system, body.messages]).toLowerCase();
    const summary = prompt.includes("summari");
    if ((mode === "first_exchange" && attempt === 1) || (mode !== "first_exchange" && attempt === 2)) {
      seen.push({ reply: 400, summary });
      const message = mode === "unrelated_400"
        ? "unrelated invalid request: fixture parameter"
        : "prompt is too long: 210000 tokens > 200000 maximum";
      answer(400, JSON.stringify({ type: "error", error: { type: "invalid_request_error", message } }));
      return;
    }
    seen.push({ reply: 200, summary });
    const content = attempt === 3 ? "Local summary: previous greeting acknowledged." : attempt === 1 ? "READY" : "DONE";
    const usage = { input_tokens: 100, output_tokens: 10, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 };
    const message = { type: "message", id: "msg_local_" + attempt, role: "assistant",
      model: body.model ?? "claude-sonnet-5", content: [{ type: "text", text: content }],
      stop_reason: "end_turn", stop_sequence: null, usage };
    if (!body.stream) {
      answer(200, JSON.stringify(message));
      return;
    }
    const events: [string, object][] = [
      ["message_start", { type: "message_start", message: { ...message, content: [], stop_reason: null,
        usage: { ...usage, output_tokens: 0 } } }],
      ["content_block_start", { type: "content_block_start", index: 0, content_block: { type: "text", text: "" } }],
      ["content_block_delta", { type: "content_block_delta", index: 0, delta: { type: "text_delta", text: content } }],
      ["content_block_stop", { type: "content_block_stop", index: 0 }],
      ["message_delta", { type: "message_delta", delta: { stop_reason: "end_turn", stop_sequence: null },
        usage: { output_tokens: 10 } }],
      ["message_stop", { type: "message_stop" }],
    ];
    const payload = events.map(([type, event]) => "event: " + type + "\ndata: " + JSON.stringify(event) + "\n\n").join("");
    answer(200, payload, "text/event-stream");
  });
  const port = await new Promise<number>((resolve) => {
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      if (address == null || typeof address === "string") throw new Error("loopback port unavailable");
      resolve(address.port);
    });
  });
  const env: NodeJS.ProcessEnv = {};
  for (const key of ["PATH", "LANG", "LC_ALL", "TERM"]) if (process.env[key]) env[key] = process.env[key];
  Object.assign(env, {
    ANTHROPIC_BASE_URL: "http://127.0.0.1:" + port,
    ANTHROPIC_API_KEY: "local-fixture-placeholder",
    HOME: "/tmp", CLAUDE_CONFIG_DIR: "/tmp/claude-compaction-proof",
    DISABLE_TELEMETRY: "1", DISABLE_ERROR_REPORTING: "1", DISABLE_AUTOUPDATER: "1",
    CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC: "1",
  });
  const child = spawn(client, [
    "--bare", "--safe-mode", "--strict-mcp-config", "--tools", "", "--permission-mode", "dontAsk",
    "--model", "claude-sonnet-5", "--system-prompt", "Local protocol test. Reply briefly.",
    "-p", "--verbose", "--input-format", "stream-json", "--output-format", "stream-json",
  ], { cwd: "/tmp", env, stdio: ["pipe", "pipe", "pipe"] });
  let compactBoundary = false;
  let buffer = "";
  const results: { isError: boolean; text: string }[] = [];
  let secondSent = false;
  function send(content: string): void {
    child.stdin.write(JSON.stringify({ type: "user", message: { role: "user", content } }) + "\n");
  }
  send("Say READY and stop.");
  child.stdout.on("data", (chunk: Buffer) => {
    buffer += chunk.toString("utf8");
    while (buffer.includes("\n")) {
      const at = buffer.indexOf("\n");
      const line = buffer.slice(0, at);
      buffer = buffer.slice(at + 1);
      let event: Record<string, unknown>;
      try { event = JSON.parse(line); } catch { continue; }
      if (event.subtype === "compact_boundary") compactBoundary = true;
      if (event.type !== "result") continue;
      results.push({ isError: event.is_error === true, text: String(event.result ?? "") });
      if (mode === "first_exchange" || results.length > 1 || event.is_error === true) {
        child.stdin.end();
      } else if (!secondSent) {
        send("Say DONE and stop.");
        secondSent = true;
      }
    }
  });
  const timer = setTimeout(() => child.kill("SIGTERM"), timeoutMs);
  const exit = await new Promise<number | null>((resolve) => child.once("close", resolve));
  clearTimeout(timer);
  await new Promise<void>((resolve) => server.close(() => resolve()));
  const replies = seen.map((item) => item.reply);
  const summaryRequests = seen.map((item) => item.summary);
  const equals = (expected: number[]) => JSON.stringify(replies) === JSON.stringify(expected);
  let pass: boolean;
  if (mode === "prompt_too_long") {
    const final = results[1];
    pass = equals([200, 400, 200, 200]) && summaryRequests[2] === true && compactBoundary
      && results.length === 2 && final !== undefined && !final.isError && final.text.includes("DONE");
  } else {
    pass = equals(mode === "first_exchange" ? [400] : [200, 400])
      && !compactBoundary && !summaryRequests.some(Boolean);
  }
  return { mode, pass, replies, summaryRequests, compactBoundary, clientExit: exit };
}

async function main(): Promise<void> {
  const binary = process.argv[2];
  if (!binary) throw new Error("usage: bun tools/probes/claude-code-compaction.ts /absolute/path/to/claude");
  const client = realpathSync(binary);
  const script = realpathSync(fileURLToPath(import.meta.url));
  const scenario = process.argv[3] as Mode | undefined;
  if (process.argv[4] === "--isolated" && scenario && modes.includes(scenario)) {
    console.log(JSON.stringify(await isolated(client, scenario)));
    return;
  }
  const digest = createHash("sha256").update(readFileSync(client)).digest("hex");
  const env: NodeJS.ProcessEnv = {};
  for (const key of ["PATH", "LANG", "LC_ALL", "TERM"]) if (process.env[key]) env[key] = process.env[key];
  const checks = modes.map((mode) => {
    const run = spawnSync("bwrap", [
      "--unshare-net", "--ro-bind", "/", "/", "--tmpfs", "/tmp", "--dev-bind", "/dev", "/dev",
      "--proc", "/proc", "--chdir", "/tmp", "--setenv", "HOME", "/tmp", "--",
      realpathSync(process.execPath), script, client, mode, "--isolated",
    ], { env, encoding: "utf8", timeout: timeoutMs + 20_000 });
    if (run.status !== 0) return { mode, pass: false, error: String(run.stderr ?? run.error).slice(-300) };
    return JSON.parse(run.stdout) as Result;
  });
  const pass = checks.every((check) => check.pass);
  console.log(JSON.stringify({ client_sha256: digest, verdict: pass ? "PASS" : "FAIL",
    loopback_only: true, checks }, null, 2));
  if (!pass) process.exitCode = 1;
}

await main();
