// NEW: a saved foreign-model session resumes through real splice onto a smaller head and recovers overflow.
// bun tools/probes/claude-code-resume-smaller.ts /absolute/claude /absolute/splice.jar
// The daemon, mock upstream and both real clients share one bwrap network namespace and a fresh tmpfs home.
import { spawn, spawnSync, type ChildProcessWithoutNullStreams } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, realpathSync, writeFileSync } from "node:fs";
import { createServer, type Server } from "node:http";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const session = "f8f8f8f8-0000-4000-8000-000000000008";
const project = "/tmp/resume-smaller-project";
const largeModel = "claude-sonnet-5";
const smallModel = "claude-haiku-4-5";
const deadlineMs = 90_000;
type Mode = "prompt_too_long" | "unrelated_400" | "no_overflow" | "corrupt_original";
const modes: Mode[] = ["prompt_too_long", "unrelated_400", "no_overflow", "corrupt_original"];
type Seen = { head: "large" | "small"; status: number; summary: boolean; savedHistory: boolean };
type Recipe = { env: Record<string, string>; unset: string[]; argv: string[] };
type ClientResult = { exit: number | null; compactBoundary: boolean; done: boolean; sessionMatches: boolean;
  events: string[]; diagnostic: string };
const digest = (bytes: Uint8Array) => createHash("sha256").update(bytes).digest("hex");

async function listen(server: Server): Promise<number> {
  return new Promise((resolve) => server.listen(0, "127.0.0.1", () => {
    const address = server.address();
    if (!address || typeof address === "string") throw new Error("fixture listener unavailable");
    resolve(address.port);
  }));
}
async function close(server: Server): Promise<void> {
  await new Promise<void>((resolve) => server.close(() => resolve()));
}
async function ports(): Promise<number[]> {
  const holders = [createServer(), createServer(), createServer()];
  const selected = await Promise.all(holders.map(listen));
  await Promise.all(holders.map(close));
  return selected;
}

function upstream(seen: Seen[], mode: Mode): Server {
  let overflowSent = false;
  return createServer(async (request, response) => {
    const chunks: Buffer[] = [];
    for await (const chunk of request) chunks.push(Buffer.from(chunk));
    const answer = (status: number, payload: string, type = "application/json") => {
      response.writeHead(status, { "Content-Type": type });
      response.end(payload);
    };
    if (request.url?.endsWith("/models")) {
      answer(200, JSON.stringify({ data: [largeModel, smallModel].map((id) => ({ id, type: "model" })) }));
      return;
    }
    if (!request.url?.includes("/messages")) {
      answer(404, '{"error":"local fixture only"}');
      return;
    }
    const body = JSON.parse(Buffer.concat(chunks).toString("utf8")) as Record<string, unknown>;
    if (request.url?.includes("count_tokens")) {
      answer(200, '{"input_tokens":100}');
      return;
    }
    const prompt = JSON.stringify([body.system, body.messages]);
    const messages = body.messages as Array<{ role?: string; content?: unknown }>;
    const summaryPrompt = JSON.stringify([body.system, messages.filter((message) => message.role === "user").at(-1)])
      .toLowerCase();
    const summary = ["tasked with summarizing conversations",
      "your task is to create a detailed summary of this conversation",
      "your task is to create a detailed summary of the conversation",
      "your task is to create a detailed summary of the recent portion",
      "summarize this portion of a claude code session transcript"].some((marker) => summaryPrompt.includes(marker));
    const savedHistory = messages.some((message) => message.role === "assistant"
      && JSON.stringify(message.content).includes("READY"));
    const probe = !summary && !prompt.includes("Say READY") && !prompt.includes("Say DONE");
    const head = body.model === smallModel ? "small" : "large";
    if (head === "small" && !summary && !probe && !overflowSent && mode !== "no_overflow") {
      overflowSent = true;
      seen.push({ head, status: 400, summary, savedHistory });
      answer(400, JSON.stringify({ type: "error", error: {
        type: "invalid_request_error", message: mode === "unrelated_400" ? "unrelated invalid fixture parameter"
          : "prompt is too long: 80000 tokens > 64000 maximum",
      } }));
      return;
    }
    if (!probe) seen.push({ head, status: 200, summary, savedHistory });
    const text = summary ? "Local summary: the previous greeting was acknowledged."
      : head === "large" && !probe ? "READY" : probe ? "OK" : "DONE";
    const usage = { input_tokens: 100, output_tokens: 10,
      cache_creation_input_tokens: 0, cache_read_input_tokens: 0 };
    const message = { type: "message", id: "msg_synthetic", role: "assistant", model: body.model,
      content: [{ type: "text", text }], stop_reason: "end_turn", stop_sequence: null, usage };
    if (!body.stream) {
      answer(200, JSON.stringify(message));
      return;
    }
    const events: [string, object][] = [
      ["message_start", { type: "message_start", message: { ...message, content: [], stop_reason: null } }],
      ["content_block_start", { type: "content_block_start", index: 0, content_block: { type: "text", text: "" } }],
      ["content_block_delta", { type: "content_block_delta", index: 0, delta: { type: "text_delta", text } }],
      ["content_block_stop", { type: "content_block_stop", index: 0 }],
      ["message_delta", { type: "message_delta", delta: { stop_reason: "end_turn", stop_sequence: null },
        usage: { output_tokens: 10 } }],
      ["message_stop", { type: "message_stop" }],
    ];
    answer(200, events.map(([event, data]) => "event: " + event + "\ndata: " + JSON.stringify(data) + "\n\n").join(""),
      "text/event-stream");
  });
}

function configure(mockPort: number, selected: number[]): string {
  const [control, large, small] = selected;
  if (control === undefined || large === undefined || small === undefined) throw new Error("fixture ports unavailable");
  mkdirSync("/tmp/config", { recursive: true });
  mkdirSync(project, { recursive: true });
  const path = "/tmp/config/splice.toml";
  writeFileSync(path, `[daemon]
control_port = ${control}
state_dir = "/tmp/state"
mcp_hosting = false
[claude]
share = ["projects"]
[providers.fixture]
dialect = "anthropic-passthrough"
base_url = "http://127.0.0.1:${mockPort}"
auth = { kind = "api-key", env = "RESUME_FIXTURE_API_KEY" }
discovery = { exclude = ["*"] }
[[providers.fixture.models]]
id = "${largeModel}"
context_window = 500000
[[providers.fixture.models]]
id = "${smallModel}"
context_window = 64000
[heads.large]
provider = "fixture"
port = ${large}
discovery_prefix = "claude-large--"
pinned_model = "${largeModel}"
models = [{ id = "${largeModel}" }]
claude = { command = "synthetic-large", config_dir = "/tmp/.claude-large" }
[heads.small]
provider = "fixture"
port = ${small}
discovery_prefix = "claude-small--"
pinned_model = "${smallModel}"
models = [{ id = "${smallModel}" }]
claude = { command = "synthetic-small", config_dir = "/tmp/.claude-small" }
`, { mode: 0o600 });
  return path;
}

function baseEnv(): NodeJS.ProcessEnv {
  const env: NodeJS.ProcessEnv = {};
  for (const key of ["PATH", "LANG", "LC_ALL", "TERM", "JAVA_HOME"]) if (process.env[key]) env[key] = process.env[key];
  return { ...env, HOME: "/tmp", DISABLE_TELEMETRY: "1", DISABLE_ERROR_REPORTING: "1", DISABLE_AUTOUPDATER: "1",
    CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC: "1" };
}
async function ready(control: number, daemon: ChildProcessWithoutNullStreams): Promise<void> {
  const started = Date.now();
  while (Date.now() - started < 60_000) {
    if (daemon.exitCode !== null) throw new Error("fixture daemon exited before readiness");
    try {
      const reply = await fetch("http://127.0.0.1:" + control + "/health");
      const body = await reply.json() as { readyHeads?: number };
      if (reply.ok && body.readyHeads === 2) return;
    } catch { /* only the fixture's bounded startup wait */ }
    await Bun.sleep(100);
  }
  throw new Error("fixture daemon readiness timed out");
}
async function recipe(control: number, head: string, args: string[]): Promise<Recipe> {
  const token = readFileSync("/tmp/state/mgmt-key", "utf8").trim();
  const reply = await fetch("http://127.0.0.1:" + control + "/launch/" + head, { method: "POST",
    headers: { Authorization: "Bearer " + token, "Content-Type": "application/json" },
    body: JSON.stringify({ args, cwd: project }) });
  if (!reply.ok) throw new Error("fixture launch refused with status " + reply.status + ": "
    + (await reply.text()).replaceAll(token, "[redacted]"));
  return await reply.json() as Recipe;
}
async function clientRun(binary: string, launch: Recipe, resume: boolean): Promise<ClientResult> {
  const env = baseEnv();
  for (const key of launch.unset) delete env[key];
  Object.assign(env, launch.env);
  const child = spawn(binary, [
    ...launch.argv.slice(1),
    "--safe-mode", "--strict-mcp-config", "--tools", "", "--permission-mode", "dontAsk",
    "--system-prompt", "Local protocol test. Reply briefly.",
    ...(resume ? [] : ["--session-id", session]),
    "-p", "--verbose", "--input-format", "stream-json", "--output-format", "stream-json",
  ], { cwd: project, env, stdio: ["pipe", "pipe", "pipe"] });
  let buffer = "";
  let compactBoundary = false;
  let done = false;
  let sessionMatches = false;
  const events: string[] = [];
  let diagnostic = "";
  child.stderr.on("data", (chunk: Buffer) => { diagnostic = (diagnostic + chunk.toString()).slice(-4096); });
  child.stdout.on("data", (chunk: Buffer) => {
    buffer += chunk.toString();
    while (buffer.includes("\n")) {
      const at = buffer.indexOf("\n");
      const line = buffer.slice(0, at);
      buffer = buffer.slice(at + 1);
      let event: Record<string, unknown>;
      try { event = JSON.parse(line); } catch { continue; }
      events.push(String(event.type) + (event.subtype ? "/" + String(event.subtype) : ""));
      if (event.subtype === "compact_boundary") compactBoundary = true;
      if (event.subtype === "init") sessionMatches = event.session_id === session;
      if (event.type === "result") {
        done = event.is_error !== true && String(event.result ?? "").includes(resume ? "DONE" : "READY");
        child.stdin.end();
      }
    }
  });
  child.stdin.write(JSON.stringify({ type: "user", message: {
    role: "user", content: resume ? "Say DONE and stop." : "Say READY and stop.",
  } }) + "\n");
  const timer = setTimeout(() => child.kill("SIGTERM"), deadlineMs);
  try {
    const exit = await new Promise<number | null>((resolve) => child.once("close", resolve));
    return { exit, compactBoundary, done, sessionMatches, events, diagnostic: diagnostic
      .replaceAll(launch.env.ANTHROPIC_AUTH_TOKEN ?? "LOCAL_FIXTURE_PLACEHOLDER", "[redacted]") };
  } finally { clearTimeout(timer); }
}

async function isolated(binary: string, jar: string, mode: Mode): Promise<object> {
  const seen: Seen[] = [];
  const mock = upstream(seen, mode);
  const mockPort = await listen(mock);
  const selected = await ports();
  const config = configure(mockPort, selected);
  const control = selected[0];
  if (control === undefined) throw new Error("control port unavailable");
  const daemon = spawn("java", ["-Xmx512m", "-Duser.home=/tmp", "-jar", jar, "daemon"], {
    cwd: "/tmp", env: { ...baseEnv(), SPLICE_CONFIG: config, RESUME_FIXTURE_API_KEY: "LOCAL_FIXTURE_PLACEHOLDER" },
    stdio: ["pipe", "pipe", "pipe"],
  });
  daemon.stdout.resume();
  let bootLog = "";
  let phase = "daemon boot";
  const safeLog = (value: string): string => {
    const key = existsSync("/tmp/state/mgmt-key") ? readFileSync("/tmp/state/mgmt-key", "utf8").trim() : "";
    return (key ? value.replaceAll(key, "[redacted]") : value)
      .replaceAll("LOCAL_FIXTURE_PLACEHOLDER", "[redacted]");
  };
  daemon.stderr.on("data", (chunk: Buffer) => { bootLog = (bootLog + chunk.toString()).slice(-16_384); });
  try {
    try {
      await ready(control, daemon);
    } catch {
      return { verdict: "FAIL", phase, exit: daemon.exitCode, diagnostic: safeLog(bootLog) };
    }
    phase = "large launch";
    const first = await recipe(control, "large", []);
    const source = await clientRun(binary, first, false);
    if (!source.done) return { verdict: "FAIL", phase: "source", source, seen };
    const file = join(first.env.CLAUDE_CONFIG_DIR ?? "/tmp/.claude-large", "projects",
      "-tmp-resume-smaller-project", session + ".jsonl");
    const before = readFileSync(file);
    phase = "small launch";
    const second = await recipe(control, "small", ["-r", session]);
    const saved = join("/tmp/state/transcript-originals", "-tmp-resume-smaller-project", session + ".jsonl");
    const keptBefore = existsSync(saved) && digest(readFileSync(saved)) === digest(before);
    const resumedFile = join(second.env.CLAUDE_CONFIG_DIR ?? "/tmp/.claude-small", "projects",
      "-tmp-resume-smaller-project", session + ".jsonl");
    const transcriptModels = readFileSync(resumedFile, "utf8").split("\n").filter(Boolean).map((line) => JSON.parse(line))
      .filter((row) => row.type === "assistant").map((row) => row.message?.model);
    const rewritten = transcriptModels.length > 0 && transcriptModels.every((model) => model === smallModel);
    if (mode === "corrupt_original" && existsSync(saved)) {
      // Mutation control: only the synthetic retained evidence changes, never the live transcript.
      const corrupt = readFileSync(saved);
      if (corrupt.length) corrupt[0] = (corrupt[0] ?? 0) ^ 1;
      writeFileSync(saved, corrupt);
    }
    const resumed = await clientRun(binary, second, true);
    const originalKept = keptBefore && existsSync(saved) && digest(readFileSync(saved)) === digest(before);
    const sourceWindow = Number(first.env.CLAUDE_CODE_MAX_CONTEXT_TOKENS);
    const targetWindow = Number(second.env.CLAUDE_CODE_MAX_CONTEXT_TOKENS);
    const windowsMatch = sourceWindow === 500_000 && targetWindow === 64_000;
    const modelsMatch = first.env.ANTHROPIC_MODEL === largeModel && second.env.ANTHROPIC_MODEL === smallModel;
    const small = seen.filter((entry) => entry.head === "small");
    const replies = small.map((entry) => entry.status);
    const summaries = small.map((entry) => entry.summary);
    const equals = (expected: number[]) => JSON.stringify(replies) === JSON.stringify(expected);
    const recoveryObserved = resumed.exit === 0 && resumed.done && resumed.compactBoundary
      && equals([400, 200, 200]) && JSON.stringify(summaries) === JSON.stringify([false, true, false]);
    const common = source.exit === 0 && source.sessionMatches && resumed.sessionMatches && originalKept
      && rewritten && windowsMatch && modelsMatch && small[0]?.savedHistory === true;
    const expected = mode === "prompt_too_long" || mode === "corrupt_original" ? recoveryObserved
      : mode === "no_overflow" ? resumed.exit === 0 && resumed.done && !resumed.compactBoundary
        && equals([200]) && !summaries.some(Boolean) && !recoveryObserved
        : resumed.exit === 0 && resumed.done && !resumed.compactBoundary
          && equals([400, 200]) && !summaries.some(Boolean) && small[1]?.savedHistory === true && !recoveryObserved;
    return { verdict: common && expected ? "PASS" : "FAIL", mode, source, resumed, originalKept,
      rewritten, transcriptModels, windowsMatch, modelsMatch, recoveryObserved, sourceWindow, targetWindow, seen };
  } catch (error) {
    return { verdict: "FAIL", phase, diagnostic: safeLog(String(error)), daemonLog: safeLog(bootLog), seen };
  } finally {
    daemon.kill("SIGTERM");
    await new Promise<void>((resolve) => {
      if (daemon.exitCode !== null || daemon.signalCode !== null) resolve();
      else daemon.once("close", () => resolve());
    });
    await close(mock);
  }
}

async function main(): Promise<void> {
  const binary = process.argv[2];
  const artifact = process.argv[3];
  if (!binary || !artifact) throw new Error("usage: claude-code-resume-smaller.ts /absolute/claude /absolute/splice.jar");
  const client = realpathSync(binary);
  const jar = realpathSync(artifact);
  const clientHash = digest(readFileSync(client));
  const artifactHash = digest(readFileSync(jar));
  const mode = process.argv[4] as Mode | undefined;
  if (process.argv[5] === "--isolated" && mode && modes.includes(mode)) {
    console.log(JSON.stringify(await isolated(client, jar, mode)));
    return;
  }
  const script = realpathSync(fileURLToPath(import.meta.url));
  const checks = modes.map((selected) => {
    const result = spawnSync("bwrap", [
      "--unshare-net", "--unshare-pid", "--die-with-parent", "--ro-bind", "/", "/", "--tmpfs", "/tmp", "--dev-bind", "/dev", "/dev",
      "--proc", "/proc", "--chdir", "/tmp", "--",
      realpathSync(process.execPath), script, client, jar, selected, "--isolated",
    ], { env: baseEnv(), encoding: "utf8", timeout: 240_000 });
    const check = result.status === 0 ? JSON.parse(result.stdout) as Record<string, unknown>
      : { verdict: "FAIL", mode: selected, phase: "isolated fixture", exit: result.status,
        diagnostic: String(result.stderr).replaceAll("LOCAL_FIXTURE_PLACEHOLDER", "[redacted]").slice(-8192) };
    const expectedVerdict = selected === "corrupt_original" ? "FAIL" : "PASS";
    const controlPass = selected === "corrupt_original" ? check.verdict === "FAIL" && check.originalKept === false
      && check.recoveryObserved === true && check.rewritten === true && check.windowsMatch === true
      && check.modelsMatch === true : check.verdict === "PASS";
    return { ...check, expectedVerdict, controlPass };
  });
  const artifactsUnchanged = clientHash === digest(readFileSync(client)) && artifactHash === digest(readFileSync(jar));
  const pass = artifactsUnchanged && checks.every((check) => check.controlPass);
  console.log(JSON.stringify({ client_sha256: clientHash, artifact_sha256: artifactHash,
    artifactsUnchanged, loopback_only: true, product_path: true, verdict: pass ? "PASS" : "FAIL", checks }, null, 2));
  if (!pass) process.exitCode = 1;
}
await main();
