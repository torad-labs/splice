#!/usr/bin/env bun
/** Loopback release fixture and JSON assertions for the modern Docker upgrade. */
import { appendFileSync, readFileSync } from "node:fs";
import { join } from "node:path";

function check(ok: unknown, why: string): asserts ok {
  if (!ok) throw new Error(why);
}

function serve(dir: string, log: string): void {
  const names = new Set([
    "sha256sums.txt", "splice.jar", "splice-linux-amd64.jar", "splice-launch", "splice-mcp-holder", "splice-mcp-front",
  ]);
  const server = Bun.serve({
    port: 0,
    hostname: "127.0.0.1",
    async fetch(req) {
      const parts = new URL(req.url).pathname.split("/");
      const name = parts[2] ?? "";
      if (req.method !== "GET" || parts.length !== 3 || parts[1] !== "candidate" || !names.has(name)) {
        return new Response("not found", { status: 404 });
      }
      const asset = Bun.file(join(dir, name));
      if (!(await asset.exists())) return new Response("not found", { status: 404 });
      appendFileSync(log, name + "\n");
      return new Response(asset);
    },
  });
  console.log(JSON.stringify({ port: server.port }));
}

interface Answer {
  content?: { text?: string }[];
  data?: { id: string; max_input_tokens?: number }[];
  effective?: { maxQueued?: unknown };
  usage?: { input_tokens?: number; cache_read_input_tokens?: number; cache_creation_input_tokens?: number };
}

async function request(url: string, keyFile: string, session?: string, model?: string): Promise<Answer> {
  const headers: Record<string, string> = {
    authorization: "Bearer " + readFileSync(keyFile, "utf8").trim(),
  };
  if (session) headers["x-claude-code-session-id"] = session;
  if (model) headers["content-type"] = "application/json";
  const resp = await fetch(url, {
    headers,
    method: model ? "POST" : "GET",
    body: model ? JSON.stringify({ model, max_tokens: 64, stream: false, messages: [{ role: "user", content: "Rehearse." }] })
      : undefined,
    signal: AbortSignal.timeout(30_000),
  });
  check(resp.ok, `${url} (${model ?? "GET"}): HTTP ${resp.status}`);
  return (await resp.json()) as Answer;
}

async function answered(base: string, key: string, session: string, model: string, expected: string): Promise<Answer> {
  const body = await request(base + "/v1/messages", key, session, model);
  check(body.content?.some((b) => b.text?.includes(expected)), `${base}: ${model} did not return its mock's text`);
  return body;
}

async function turn(base: string, key: string, session: string, model: string, expected: string): Promise<void> {
  await answered(base, key, session, model, expected);
  console.log(`${base}: ${model} completed a real turn`);
}

function logged(log: string): { model: string; system: string[] }[] {
  return readFileSync(log, "utf8").trim().split("\n").filter(Boolean).map((line) =>
    JSON.parse(line) as { model: string; system: string[] }
  );
}

const slots = { opus: "mock-chat", sonnet: "mock-chat-2-big", haiku: "mock-chat-2" };
const NATIVE_MILLION = 1_000_000;

async function realinfer(
  base: string, key: string, session: string, log: string, prompt: string, window: string, laneFile?: string,
): Promise<void> {
  const modelData = await request(base + "/v1/models", key);
  const row = modelData.data?.find((m) => m.id.replace(/\[1m\]$/i, "").endsWith("--mock-chat"));
  check(row, `${base}: the RealInfer model is not in the catalog`);
  if (!laneFile) {
    check(row.max_input_tokens === Number(window), `${base}: RealInfer catalog window is ${row.max_input_tokens}, wanted ${window}`);
    console.log(`${base}: RealInfer catalog has the old head's context window ${window}`);
  }
  // Claude Code runs opus-5-5 and sonnet-5-5 at a native million window without a beta, so their
  // counts are sized for that window; the other selectors use the window the terminal declared.
  for (const [selector, model, native] of [
    ["realinfer--mock-chat", slots.opus, 0],
    ["claude-opus-5-5", slots.opus, NATIVE_MILLION],
    ["claude-sonnet-5-5", slots.sonnet, NATIVE_MILLION],
    ["claude-haiku-4-5", slots.haiku, 0],
  ] as const) {
    const before = logged(log).length;
    const body = await answered(base, key, session, selector, "END");
    if (laneFile) terminalWindow(body, laneFile, Number(window), native);
    const received = logged(log).slice(before);
    check(received.length > 0 && received.every((r) => r.model === model && r.system.some((s) => s.includes(prompt))),
      `${base}: ${selector} did not reach RealInfer as ${model} with the standing prompt`);
    console.log(`${selector} sent ${model} with the old standing prompt`);
  }
}

// The terminal catalog carries no window metadata. Its client observes the window through the
// billed input count: 12 raw tokens from the chat fixture, scaled from model to the lane's window.
function terminalWindow(body: Answer, laneFile: string, window: number, native: number): void {
  const lane = JSON.parse(readFileSync(laneFile, "utf8")) as { window?: number };
  check(lane.window && lane.window > 0, "the terminal never declared its window");
  const usage = body.usage;
  check(typeof usage?.input_tokens === "number", "no input usage on a completed terminal turn");
  const input = usage.input_tokens + (usage.cache_read_input_tokens ?? 0) + (usage.cache_creation_input_tokens ?? 0);
  const client = Math.max(lane.window, native);
  const expected = Math.floor(12 * client / window);
  check(input === expected, `terminal counted ${input} input tokens, wanted ${expected} at the retained window ${window}`);
  console.log(`terminal scaled 12 input tokens to ${input} (${client} client / ${window} model window)`);
}

function fixed(path: string, prompt: string, window: string): void {
  const config = Bun.TOML.parse(readFileSync(path, "utf8")) as {
    providers?: { realinfer?: {
      context_window?: number; model_slots?: Record<string, string>; system_prompt?: string; system_prompt_mode?: string;
      overrides?: { maxInflight?: string };
    } };
  };
  const row = config.providers?.realinfer;
  check(row?.context_window === Number(window) && row.system_prompt === prompt && row.system_prompt_mode === "append",
    "the RealInfer window and standing prompt did not move together to its provider");
  check(Object.entries(slots).every(([tier, model]) => row.model_slots?.[tier] === model), "the tier map did not move");
  check(row.overrides?.maxInflight === "3", "maxInflight did not move");
  console.log("provider now holds window, tier map, standing prompt/mode and maxInflight");
}

async function knob(base: string, key: string): Promise<void> {
  const data = await request(base + "/api/config", key);
  check(String(data.effective?.maxQueued) === "64", "live effective maxQueued is not the old saved value");
  console.log("candidate applies the old saved maxQueued");
}

function legacyAuth(file: string): void {
  const recipe = JSON.parse(readFileSync(file, "utf8")) as { env?: Record<string, string> };
  const config = recipe.env?.["CLAUDE_CONFIG_DIR"];
  check(config, "baseline launch recipe does not name its head config directory");
  const settings = JSON.parse(readFileSync(join(config, "settings.json"), "utf8")) as { apiKeyHelper?: string };
  console.log("saved recipe has bearer:", Boolean(recipe.env?.["ANTHROPIC_AUTH_TOKEN"]),
    "; head settings have apiKeyHelper now:", Boolean(settings.apiKeyHelper));
}

const verbs: Record<string, (...args: string[]) => void | Promise<void>> = {
  serve, turn, realinfer, fixed, knob, "legacy-auth": legacyAuth,
};
const [verb = "", ...args] = process.argv.slice(2);
try {
  check(Object.hasOwn(verbs, verb), "unknown upgrade_064.ts command");
  await verbs[verb]!(...args);
} catch (err) {
  console.error(err instanceof Error ? err.message : String(err));
  process.exitCode = 1;
}
