// cp-output: a model's `max_output_tokens` is the most it is asked to write. Claude Code asks a model it does not know
// for 32,000 tokens or more, which a self-hosted model serving less refuses outright; a turn on such a model asks the
// provider for at most its max_output_tokens, on its head's port and on the door alike, and a turn that asks for less
// asks for what it asked. A model that declares none is asked for what the turn asked.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { Splice, freePort } from "../server/lib/harness.ts";
import { session, toDoor } from "../door/lib/claude.ts";
import { Gateway, MODEL, TeamCli, doorRaw } from "./lib/team.ts";

setDefaultTimeout(120_000);

let cli: TeamCli;
let gw: Gateway;
let s: Splice;

/** What the provider was asked for, for a door turn on `model` that asks for `asked` tokens. */
async function askedFor(model: string, asked: number): Promise<number> {
  const marker = `cp-output-${crypto.randomUUID()}`;
  const r = await doorRaw(await session(s), { model, max_tokens: asked, stream: true, messages: [{ role: "user", content: marker }] });
  expect(r.status).toBe(200);
  const body = gw.turnsWith(marker).at(-1)?.body ?? {};
  return Number(body.max_completion_tokens ?? body.max_tokens);
}

beforeAll(async () => {
  cli = new TeamCli();
  gw = new Gateway(cli).start();
  gw.open = true;
  s = await Splice.boot(`
[providers.acme]
dialect = "openai-chat"
base_url = "${gw.url}"
auth = { kind = "api-key", env = "MOCK_UPSTREAM_KEY" }
local = false

[[providers.acme.models]]
id = "${MODEL}"
context_window = 65536
max_output_tokens = 8192

[[providers.acme.models]]
id = "glm-open"
context_window = 65536

[heads.acme]
provider = "acme"
port = ${await freePort()}
discovery_prefix = "claude-acme--"
pinned_model = "${MODEL}"
`);
  toDoor(s);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  gw?.stop();
  cli?.dispose();
});

describe("cp-output", () => {
  test("a turn asking for more than the model's max_output_tokens asks the provider for that many", async () => {
    expect(await askedFor(`claude-acme--${MODEL}`, 32_000)).toBe(8192);
  });

  test("a turn asking for less asks for what it asked", async () => {
    expect(await askedFor(`claude-acme--${MODEL}`, 4000)).toBe(4000);
  });

  test("a model that declares no max_output_tokens is asked for what the turn asked", async () => {
    expect(await askedFor("claude-acme--glm-open", 32_000)).toBe(32_000);
  });
});
