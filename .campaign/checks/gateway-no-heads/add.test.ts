// gnh-add: what splice writes itself puts on the provider what the Splice gateway reads there. `splice add` with two
// models of different windows writes their windows and the tiers' models on `[providers.<key>]`, and the head it adds
// carries only what its own command needs; the file it leaves boots without a finding, and with the head removed the
// gateway lists both models.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { toDoor } from "../door/lib/claude.ts";
import { Splice } from "../server/lib/harness.ts";
import { listed, selector, tomlOf } from "./lib/providers.ts";

setDefaultTimeout(240_000);

const WIDE = "Onyx-4-Wide";
const NARROW = "Onyx-4-Narrow";

let endpoint: ReturnType<typeof Bun.serve>;
let s: Splice;
let added = { code: -1, out: "" };

beforeAll(async () => {
  // an OpenAI-compatible endpoint that lists both models and answers a completion
  endpoint = Bun.serve({
    port: 0,
    hostname: "127.0.0.1",
    fetch: async (req) => {
      const path = new URL(req.url).pathname;
      if (path.endsWith("/models")) return Response.json({ object: "list", data: [WIDE, NARROW].map((id) => ({ id, object: "model" })) });
      if (path.endsWith("/chat/completions")) {
        const body = (await req.json().catch(() => ({}))) as { model?: string };
        return Response.json({
          id: "chatcmpl-onyx",
          object: "chat.completion",
          created: Math.floor(Date.now() / 1000),
          model: body.model,
          choices: [{ index: 0, message: { role: "assistant", content: "pong" }, finish_reason: "stop" }],
          usage: { prompt_tokens: 10, completion_tokens: 1, total_tokens: 11 },
        });
      }
      return Response.json({ error: { message: `no route ${path}` } }, { status: 404 });
    },
  });
  s = await Splice.boot("", { ONYX_API_KEY: "sk-onyx-check" });
  const url = `http://127.0.0.1:${endpoint.port}/v1`;
  const r = s.cli(["add", "api-key", "--name", "onyx", "--base-url", url, "--model", `${WIDE}:400000`, "--model", `${NARROW}:100000`, "--command", "claude-onyx", "--yes"]);
  added = { code: r.code, out: `${r.out}\n${r.err}` };
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  endpoint?.stop(true);
});

describe("gnh-add", () => {
  test("the add writes the windows and the tiers on the provider, and the head only what its command needs", () => {
    expect(added.code, added.out).toBe(0);
    const toml = tomlOf(s);
    const head = toml.heads?.onyx;
    expect(head).toBeDefined();
    for (const key of ["context_window", "model_slots", "models", "system_prompt_mode", "overrides"]) expect(head[key]).toBeUndefined();
    const rows = (toml.providers.onyx.models ?? []) as { id: string; context_window: number }[];
    expect(rows.map((m) => [m.id, m.context_window])).toEqual([
      [WIDE, 400000],
      [NARROW, 100000],
    ]);
  });

  test("the file it leaves boots without a finding", () => {
    const r = s.cli(["check-config"]);
    expect(r.code, `${r.out}\n${r.err}`).toBe(0);
  });

  test("with the head removed, the gateway lists both models", async () => {
    const toml = tomlOf(s);
    await s.stop();
    const text = (await Bun.file(s.config).text()).split(/\n(?=\[heads\.onyx)/)[0]!;
    s.writeConfig(text.replace(/^\[daemon\][\s\S]*?\n\n/, ""));
    await s.start();
    toDoor(s);
    expect(toml.providers.onyx).toBeDefined();
    expect(listed(s).map((r) => r.model)).toEqual([selector("onyx", WIDE), selector("onyx", NARROW)]);
  }, 300_000);
});
