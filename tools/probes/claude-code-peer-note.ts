// NEW: V4-444 — re-runnable local proof that a Claude Code version takes the console's note on its inbox socket.
// Run with: bun tools/probes/claude-code-peer-note.ts /absolute/path/to/claude
// The client runs under bwrap --unshare-net with a fake backend inside the same sandbox: no provider traffic or real key is possible.
// It is started with --messaging-socket-path, the frame in the PeerNoteFrameTest fixture is written to that socket, and the proof is the
// client's NEXT model request: it must carry the note, wrapped as another session's message. A second arm sends the same frame with a
// type the client has no handler for and must see nothing arrive, so a pass cannot come from the client reading the note some other way.
import { spawn, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, realpathSync } from "node:fs";
import { createServer } from "node:http";
import { createConnection } from "node:net";
import { dirname } from "node:path";
import { fileURLToPath } from "node:url";

type Arm = "accepted" | "mutant";
type Result = { arm: Arm; pass: boolean; socketAppeared: boolean; wrote: boolean; requests: number; noteInSecondRequest: boolean; clientExit: number | null };

const FIXTURE = "features/sessions/src/test/resources/splice/sessions/note/peer-note-frame.jsonl";
const MARKER = "PROBE-NOTE-7f3a";
const arms: Arm[] = ["accepted", "mutant"];
const timeoutMs = 60_000;
const wait = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms));

function frameFor(arm: Arm, repo: string): string {
  const frame = readFileSync(repo + "/" + FIXTURE, "utf8");
  if (!frame.includes('"type":"user"') || !frame.includes(MARKER)) throw new Error("the frame fixture no longer has the shape this probe sends");
  return arm === "accepted" ? frame : frame.replace('"type":"user"', '"type":"note"');
}

async function isolated(client: string, arm: Arm, repo: string): Promise<Result> {
  const bodies: string[] = [];
  const server = createServer(async (request, response) => {
    const chunks: Buffer[] = [];
    for await (const chunk of request) chunks.push(Buffer.from(chunk));
    let body: Record<string, unknown> = {};
    try { body = JSON.parse(Buffer.concat(chunks).toString("utf8")); } catch { /* malformed fixture request */ }
    function answer(status: number, value: string, type = "application/json"): void {
      response.writeHead(status, { "Content-Type": type, "Content-Length": Buffer.byteLength(value) });
      response.end(value);
    }
    if (request.url?.startsWith("/v1/messages/count_tokens")) return answer(200, JSON.stringify({ input_tokens: 100 }));
    if (!request.url?.startsWith("/v1/messages")) return answer(404, JSON.stringify({ type: "error", error: { type: "not_found_error", message: "local fixture only" } }));
    bodies.push(JSON.stringify(body.messages ?? []));
    const attempt = bodies.length;
    const content = attempt === 1 ? "READY" : "DONE";
    const usage = { input_tokens: 100, output_tokens: 10, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 };
    const message = { type: "message", id: "msg_local_" + attempt, role: "assistant", model: body.model ?? "claude-sonnet-5",
      content: [{ type: "text", text: content }], stop_reason: "end_turn", stop_sequence: null, usage };
    if (!body.stream) return answer(200, JSON.stringify(message));
    const events: [string, object][] = [
      ["message_start", { type: "message_start", message: { ...message, content: [], stop_reason: null, usage: { ...usage, output_tokens: 0 } } }],
      ["content_block_start", { type: "content_block_start", index: 0, content_block: { type: "text", text: "" } }],
      ["content_block_delta", { type: "content_block_delta", index: 0, delta: { type: "text_delta", text: content } }],
      ["content_block_stop", { type: "content_block_stop", index: 0 }],
      ["message_delta", { type: "message_delta", delta: { stop_reason: "end_turn", stop_sequence: null }, usage: { output_tokens: 10 } }],
      ["message_stop", { type: "message_stop" }],
    ];
    answer(200, events.map(([type, event]) => "event: " + type + "\ndata: " + JSON.stringify(event) + "\n\n").join(""), "text/event-stream");
  });
  const port = await new Promise<number>((resolve) => {
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      if (address == null || typeof address === "string") throw new Error("loopback port unavailable");
      resolve(address.port);
    });
  });
  const dir = "/tmp/cc-socks-peer-note";
  mkdirSync(dir, { mode: 0o700, recursive: true });
  const socket = dir + "/inbox.sock";
  const env: NodeJS.ProcessEnv = {};
  for (const key of ["PATH", "LANG", "LC_ALL", "TERM"]) if (process.env[key]) env[key] = process.env[key];
  Object.assign(env, {
    ANTHROPIC_BASE_URL: "http://127.0.0.1:" + port, ANTHROPIC_API_KEY: "LOCAL_FIXTURE_PLACEHOLDER",
    HOME: "/tmp", CLAUDE_CONFIG_DIR: "/tmp/claude-peer-note-proof",
    DISABLE_TELEMETRY: "1", DISABLE_ERROR_REPORTING: "1", DISABLE_AUTOUPDATER: "1",
    CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC: "1",
  });
  const child = spawn(client, [
    "--bare", "--safe-mode", "--strict-mcp-config", "--tools", "", "--permission-mode", "dontAsk",
    "--model", "claude-sonnet-5", "--system-prompt", "Local protocol test. Reply briefly.",
    "--messaging-socket-path", socket, "-p", "--verbose", "--input-format", "stream-json", "--output-format", "stream-json",
  ], { cwd: "/tmp", env, stdio: ["pipe", "pipe", "pipe"] });
  child.stdout.resume();
  child.stderr.resume();
  child.stdin.write(JSON.stringify({ type: "user", message: { role: "user", content: "Say READY and stop." } }) + "\n");
  for (let i = 0; i < 100 && !existsSync(socket); i++) await wait(100);
  const socketAppeared = existsSync(socket);
  // The first turn has to finish before the inbox is idle enough to take a message between turns.
  for (let i = 0; i < 100 && bodies.length < 1; i++) await wait(100);
  await wait(2_000);
  let wrote = false;
  if (socketAppeared) {
    const frame = frameFor(arm, repo);
    await new Promise<void>((resolve) => {
      const connection = createConnection(socket, () => connection.end(frame, () => { wrote = true; resolve(); }));
      connection.on("error", () => resolve());
    });
  }
  const limit = arm === "accepted" ? 150 : 80;
  for (let i = 0; i < limit && bodies.length < 2; i++) await wait(100);
  await wait(1_000);
  child.stdin.end();
  const killer = setTimeout(() => child.kill("SIGTERM"), 5_000);
  const clientExit = await new Promise<number | null>((resolve) => child.once("close", resolve));
  clearTimeout(killer);
  await new Promise<void>((resolve) => server.close(() => resolve()));
  const second = bodies[1] ?? "";
  const noteInSecondRequest = second.includes(MARKER) && second.includes("cross-session-message");
  const pass = socketAppeared && wrote && (arm === "accepted" ? bodies.length >= 2 && noteInSecondRequest : !bodies.some((body, at) => at > 0 && body.includes(MARKER)));
  return { arm, pass, socketAppeared, wrote, requests: bodies.length, noteInSecondRequest, clientExit };
}

async function main(): Promise<void> {
  const binary = process.argv[2];
  if (!binary) throw new Error("usage: bun tools/probes/claude-code-peer-note.ts /absolute/path/to/claude");
  const client = realpathSync(binary);
  const script = realpathSync(fileURLToPath(import.meta.url));
  const repo = dirname(dirname(dirname(script)));
  const arm = process.argv[3] as Arm | undefined;
  if (process.argv[4] === "--isolated" && arm && arms.includes(arm)) {
    console.log(JSON.stringify(await isolated(client, arm, repo)));
    return;
  }
  const digest = createHash("sha256").update(readFileSync(client)).digest("hex");
  const env: NodeJS.ProcessEnv = {};
  for (const key of ["PATH", "LANG", "LC_ALL", "TERM"]) if (process.env[key]) env[key] = process.env[key];
  const checks = arms.map((each) => {
    const run = spawnSync("bwrap", [
      "--unshare-net", "--ro-bind", "/", "/", "--tmpfs", "/tmp", "--dev-bind", "/dev", "/dev",
      "--proc", "/proc", "--chdir", "/tmp", "--setenv", "HOME", "/tmp", "--",
      realpathSync(process.execPath), script, client, each, "--isolated",
    ], { env, encoding: "utf8", timeout: timeoutMs + 20_000 });
    if (run.status !== 0) return { arm: each, pass: false, error: String(run.stderr ?? run.error).slice(-300) };
    return JSON.parse(run.stdout) as Result;
  });
  const pass = checks.every((check) => check.pass);
  console.log(JSON.stringify({ client_sha256: digest, verdict: pass ? "PASS" : "FAIL", loopback_only: true, checks }, null, 2));
  if (!pass) process.exitCode = 1;
}

await main();
