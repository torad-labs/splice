// cp-identity: a team's gateway can tell which client is calling, to turn an outdated one away with a message of its
// own. A provider with `send_version = true` is sent, on every request, `x-splice-client: <splice's version>` (the
// header and value server-client-header sends a splice server) beside its Via line, and the wrapper's identity,
// `x-splice-wrapper: <name>/<version>`, when the session sends it. A wrapper sends it by setting it in Claude Code's
// ANTHROPIC_CUSTOM_HEADERS, which a launch on the door leaves as the wrapper set it. A provider without send_version is
// sent neither.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { recipe } from "../desktop/lib/desktop.ts";
import { Splice, freePort } from "../server/lib/harness.ts";
import { session, toDoor } from "../door/lib/claude.ts";
import { Gateway, MODEL, TeamCli, doorRaw } from "./lib/team.ts";

setDefaultTimeout(120_000);

const WRAPPER = "acme-ai/1.4.2";

let cli: TeamCli;
let told: Gateway;
let quiet: Gateway;
let s: Splice;

function provider(key: string, gw: Gateway, port: number, extra = ""): string {
  return `
[providers.${key}]
dialect = "openai-chat"
base_url = "${gw.url}"
auth = { kind = "api-key", env = "MOCK_UPSTREAM_KEY" }
local = false
${extra}

[[providers.${key}.models]]
id = "${MODEL}"
context_window = 65536

[heads.${key}]
provider = "${key}"
port = ${port}
discovery_prefix = "claude-${key}--"
pinned_model = "${MODEL}"
`;
}

async function seen(key: string, gw: Gateway, headers: Record<string, string>): Promise<Record<string, string>> {
  const marker = `cp-identity-${crypto.randomUUID()}`;
  const r = await doorRaw(await session(s), { model: `claude-${key}--${MODEL}`, max_tokens: 256, stream: true, messages: [{ role: "user", content: marker }] }, { headers });
  expect(r.status).toBe(200);
  return gw.turnsWith(marker).at(-1)!.headers;
}

async function version(): Promise<string> {
  const j = (await (await fetch(`http://127.0.0.1:${s.controlPort}/health`)).json()) as { version?: string };
  expect(j.version ?? "").not.toBe("");
  return j.version!;
}

beforeAll(async () => {
  cli = new TeamCli();
  told = new Gateway(cli).start();
  quiet = new Gateway(cli).start();
  told.open = true;
  quiet.open = true;
  s = await Splice.boot(provider("acme", told, await freePort(), "send_version = true") + provider("quiet", quiet, await freePort()));
  toDoor(s);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  told?.stop();
  quiet?.stop();
  cli?.dispose();
});

describe("cp-identity", () => {
  test("a provider with send_version is told splice's version and the wrapper's", async () => {
    const h = await seen("acme", told, { "x-splice-wrapper": WRAPPER });
    expect(h["x-splice-client"]).toBe(await version());
    expect(h["x-splice-wrapper"]).toBe(WRAPPER);
  });

  test("with no wrapper, it is told splice's version alone", async () => {
    const h = await seen("acme", told, {});
    expect(h["x-splice-client"]).toBe(await version());
    expect(h["x-splice-wrapper"]).toBeUndefined();
  });

  test("a provider without send_version is told neither", async () => {
    const h = await seen("quiet", quiet, { "x-splice-wrapper": WRAPPER });
    expect(h["x-splice-client"]).toBeUndefined();
    expect(h["x-splice-wrapper"]).toBeUndefined();
  });

  test("a launch on the door leaves the wrapper's ANTHROPIC_CUSTOM_HEADERS as it set them", async () => {
    const r = await recipe(s, "claude");
    expect(r.unset).not.toContain("ANTHROPIC_CUSTOM_HEADERS");
    expect(r.env.ANTHROPIC_CUSTOM_HEADERS).toBeUndefined();
  });
});
