// Exec target for the real launch recipe. Never imports a client SDK or reads a real login.
import assert from "node:assert/strict";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

type Hook = { type: string; command: string };
type Overlay = { hooks?: { SessionStart?: { matcher?: string; hooks: Hook[] }[] } };

function flag(args: string[], name: string): string | undefined {
  const i = args.indexOf(name);
  return i < 0 ? undefined : args[i + 1];
}

async function sessionStart(args: string[], session: string, source: string, transcript: string): Promise<number> {
  const payload = JSON.stringify({
    session_id: session, transcript_path: transcript,
    cwd: process.cwd(), hook_event_name: "SessionStart", source,
  });
  let hooked = 0;
  const settings = join(process.env.CLAUDE_CONFIG_DIR ?? join(process.env.HOME!, ".claude"), "settings.json");
  const values = args.flatMap((arg, i) => arg === "--settings" ? [args[i + 1]!] : []);
  if (existsSync(settings)) values.unshift(settings);
  for (const value of values) {
    const overlay = JSON.parse(existsSync(value) ? readFileSync(value, "utf8") : value) as Overlay;
    for (const group of overlay.hooks?.SessionStart ?? []) {
      if (group.matcher && !new RegExp(`^(${group.matcher})$`).test(source)) continue;
      for (const hook of group.hooks) {
        assert.equal(hook.type, "command");
        const child = Bun.spawn(["/bin/sh", "-c", hook.command], {
          stdin: new Blob([payload]), stdout: "ignore", stderr: "inherit",
        });
        assert.equal(await child.exited, 0, "SessionStart hook failed");
        hooked++;
      }
    }
  }
  return hooked;
}

export interface Answer {
  text: string;
  frames: string;
  firstMs: number;
  totalMs: number;
}

async function turn(url: URL, headers: Headers, session: string, prompt: string): Promise<Answer> {
  const started = performance.now();
  const res = await fetch(url, {
    method: "POST", headers,
    body: JSON.stringify({
      model: process.env.ANTHROPIC_MODEL, max_tokens: 8192, stream: true,
      metadata: { user_id: JSON.stringify({ session_id: session }) },
      messages: [{ role: "user", content: prompt }],
    }),
  });
  assert.equal(res.status, 200, await (res.ok ? Promise.resolve("") : res.text()));
  assert.ok(res.headers.get("content-type")?.includes("text/event-stream"));
  assert.ok(res.body);
  let raw = "";
  let firstMs = -1;
  const decoder = new TextDecoder();
  for await (const chunk of res.body) {
    if (firstMs < 0) firstMs = performance.now() - started;
    raw += decoder.decode(chunk, { stream: true });
  }
  raw += decoder.decode();
  const totalMs = performance.now() - started;
  const frames = raw.split("\n").filter(line => line.startsWith("data: ")).map(line => JSON.parse(line.slice(6)));
  assert.equal(frames.at(-1)?.type, "message_stop", "the answer did not finish");
  assert.ok(!frames.some(f => f.type === "error"), "the stream carried an error");
  assert.ok(frames.some(f => f.type === "message_delta" && f.delta?.stop_reason === "end_turn"));
  const text = frames.flatMap(f => f.delta?.type === "text_delta" ? [f.delta.text] : []).join("");
  // The gateway assigns a fresh message ID to every answer. Compare everything else on the wire.
  for (const frame of frames) if (frame.type === "message_start") frame.message.id = "<message-id>";
  return { text, frames: JSON.stringify(frames), firstMs, totalMs };
}

async function main(): Promise<void> {
  const args = process.argv.slice(2);
  const resumed = flag(args, "--resume");
  const session = resumed ?? flag(args, "--session-id");
  assert.ok(session, "fake client needs a session id");
  const base = process.env.ANTHROPIC_BASE_URL;
  assert.ok(base);
  const url = new URL(base.replace(/\/$/, "") + "/v1/messages");
  assert.equal(url.hostname, "127.0.0.1", "fake client only calls the scratch gateway");
  const headers = new Headers({
    "content-type": "application/json", "anthropic-version": "2023-06-01",
    "x-claude-code-session-id": session,
  });
  if (process.env.ANTHROPIC_AUTH_TOKEN) headers.set("authorization", `Bearer ${process.env.ANTHROPIC_AUTH_TOKEN}`);
  for (const line of (process.env.ANTHROPIC_CUSTOM_HEADERS ?? "").split("\n")) {
    const colon = line.indexOf(":");
    if (colon > 0) headers.set(line.slice(0, colon), line.slice(colon + 1).trim());
  }
  assert.ok(headers.get("x-splice-launch-owner"), "the real recipe did not plant the launch-owner header");
  const copies = process.env.MOD_E2E_HEADERS;
  if (copies) for (const [key, value] of Object.entries(JSON.parse(copies) as Record<string, string>)) headers.set(key, value);
  if (!args.includes("-p")) {
    const dir = join(process.env.HOME!, ".claude", "sessions");
    const now = Date.now();
    mkdirSync(dir, { recursive: true });
    writeFileSync(join(dir, process.pid + ".json"), JSON.stringify({
      pid: process.pid, sessionId: session, cwd: process.cwd(), startedAt: now, updatedAt: now, status: "idle",
    }));
  }
  const root = process.env.CLAUDE_CONFIG_DIR ?? join(process.env.HOME!, ".claude");
  assert.ok(root.startsWith(process.env.HOME! + "/"), "fake client only writes within its scratch home");
  const project = join(root, "projects", process.cwd().replace(/[^a-zA-Z0-9]/g, "-"));
  const transcript = join(project, session + ".jsonl");
  const input = Bun.stdin.stream();
  const hooks = await sessionStart(args, session, resumed ? "resume" : "startup", transcript);
  if (process.env.MOD_E2E_WAIT_FOR_HOOK === "1") {
    const reader = input.getReader();
    try { assert.ok(!(await reader.read()).done, "harness closed stdin before releasing the turn"); }
    finally { reader.releaseLock(); }
  }
  const answers = [];
  for (const prompt of JSON.parse(process.env.MOD_E2E_PROMPTS!) as string[]) {
    answers.push(await turn(url, headers, session, prompt));
  }
  await Bun.write(Bun.stdout, JSON.stringify({ hooks, answers, model: process.env.ANTHROPIC_MODEL }) + "\n");
  if (!args.includes("-p")) {
    // The harness owns stdin. An open pipe models a client still sitting at its prompt.
    mkdirSync(project, { recursive: true });
    if (!existsSync(transcript)) writeFileSync(transcript, "");
    for await (const _ of input) { /* idle until EOF */ }
  }
}

if (import.meta.main) await main();
