// cp-parity: everything a head of the provider does today, a door turn on that provider does the same. A provider with
// `local`, `stream_usage`, `slot_affinity`, an effort vocabulary, `extra_headers` and `forward_headers` is sent the
// same request for the same turn whether Claude Code sent it to the head's own port (the deprecated head command) or
// through the door: the same body, byte for byte as JSON, and the same headers, the fixed ones and the forwarded one.
// The session sees the same answer.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { Splice, freePort } from "../server/lib/harness.ts";
import { launch, sessionKey } from "../speed/lib/session.ts";
import { session, toDoor } from "../door/lib/claude.ts";
import { Gateway, MODEL, type Seen, TeamCli, doorRaw } from "./lib/team.ts";

setDefaultTimeout(120_000);

let cli: TeamCli;
let gw: Gateway;
let s: Splice;

/** One turn as Claude Code sends it, with an earlier step and its reasoning, a thinking budget (the /effort pick) and a
 *  header the provider forwards. */
function turnBody(marker: string): Record<string, unknown> {
  return {
    model: "",
    max_tokens: 4096,
    stream: true,
    thinking: { type: "enabled", budget_tokens: 12_000 },
    system: [{ type: "text", text: "You are Claude Code." }],
    messages: [
      { role: "user", content: "First question." },
      {
        role: "assistant",
        content: [
          { type: "thinking", thinking: "The person asked a first question; answer it briefly.", signature: "" },
          { type: "text", text: "First answer." },
        ],
      },
      { role: "user", content: `Second question. ${marker}` },
    ],
  };
}

const HOP = new Set(["host", "content-length", "connection", "accept-encoding"]);

function headersOf(seen: Seen): Record<string, string> {
  return Object.fromEntries(Object.entries(seen.headers).filter(([k]) => !HOP.has(k)));
}

function bodyOf(seen: Seen): string {
  return JSON.stringify(seen.body);
}

beforeAll(async () => {
  cli = new TeamCli();
  gw = new Gateway(cli).start();
  gw.open = true;
  // the window the local server reports is the one declared, so the boot probe of a local server takes it
  gw.servedWindow = 65_536;
  s = await Splice.boot(`
[providers.acme]
dialect = "openai-chat"
base_url = "${gw.url}"
auth = { kind = "api-key", env = "MOCK_UPSTREAM_KEY" }
local = true
extra_headers = { x-acme-team = "core" }
forward_headers = ["x-acme-trace"]

[providers.acme.quirks]
stream_usage = true
slot_affinity = true
chat_effort_vocabulary = { default = "medium", levels = { low = "low", medium = "medium", high = "high", xhigh = "high", max = "high" } }

[[providers.acme.models]]
id = "${MODEL}"
context_window = 65536

[heads.acme]
provider = "acme"
port = ${await freePort()}
discovery_prefix = "claude-acme--"
pinned_model = "${MODEL}"
[heads.acme.claude]
command = "claude-acme-head"
`);
  toDoor(s);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  gw?.stop();
  cli?.dispose();
});

describe("cp-parity", () => {
  test("the provider is sent the same request for the same turn on the head's port and through the door", async () => {
    const session_id = crypto.randomUUID();
    const marker = `cp-parity-${crypto.randomUUID()}`;
    const extra = { "x-acme-trace": "trace-4242" };

    const head = await launch(s, "claude-acme-head");
    const viaHead = await fetch(`${head.sets.ANTHROPIC_BASE_URL}/v1/messages`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "anthropic-version": "2023-06-01",
        "user-agent": "claude-cli/2.1.326 (external, cli)",
        "x-app": "cli",
        "x-api-key": sessionKey(head),
        "x-claude-code-session-id": session_id,
        ...extra,
      },
      body: JSON.stringify({ ...turnBody(marker), model: head.sets.ANTHROPIC_MODEL }),
    });
    const headText = await viaHead.text();
    expect(viaHead.status, headText).toBe(200);

    const viaDoor = await doorRaw(await session(s), { ...turnBody(marker), model: `claude-acme--${MODEL}` }, { session: session_id, headers: extra });
    expect(viaDoor.status, viaDoor.body).toBe(200);

    const [a, b] = gw.turnsWith(marker);
    expect(a).toBeDefined();
    expect(b).toBeDefined();
    expect(bodyOf(b!)).toBe(bodyOf(a!));
    expect(headersOf(b!)).toEqual(headersOf(a!));
    expect(a!.headers["x-acme-team"]).toBe("core");
    expect(a!.headers["x-acme-trace"]).toBe("trace-4242");
  });

  test("the session sees the same answer text on both", async () => {
    const marker = `cp-parity-answer-${crypto.randomUUID()}`;
    const head = await launch(s, "claude-acme-head");
    const viaHead = await fetch(`${head.sets.ANTHROPIC_BASE_URL}/v1/messages`, {
      method: "POST",
      headers: { "content-type": "application/json", "anthropic-version": "2023-06-01", "x-api-key": sessionKey(head) },
      body: JSON.stringify({ ...turnBody(marker), model: head.sets.ANTHROPIC_MODEL, stream: false }),
    });
    const viaDoor = await doorRaw(await session(s), { ...turnBody(marker), model: `claude-acme--${MODEL}`, stream: false });
    const text = (body: string) => ((JSON.parse(body) as { content?: { type: string; text?: string }[] }).content ?? []).filter((c) => c.type === "text").map((c) => c.text).join("");
    expect(viaHead.status).toBe(200);
    expect(viaDoor.status).toBe(200);
    expect(text(viaDoor.body)).toBe(text(await viaHead.text()));
  });
});
