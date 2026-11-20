// gnh-claude: a provider that runs on added Claude accounts (`auth = { kind = "client" }`) is served by the Splice
// gateway with no head: its accounts are the provider's, its models are listed by their own Anthropic ids, and a turn naming a Claude model, by
// its bare Anthropic id or by `claude-<provider>--<id>`, runs on an added account and never carries the session's key.
// Accounts added before this change under a head on that provider become the provider's at start, so removing the head
// keeps them serving. With no account added the provider is left out, named, with what to do.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { session, toDoor } from "../door/lib/claude.ts";
import { carries } from "../catalog/lib/gateway.ts";
import { addClaudeAccount } from "../catalog/lib/heads.ts";
import { Provider } from "../harness/lib/capability.ts";
import { Splice, freePort } from "../server/lib/harness.ts";
import { leftOut, listed, send } from "./lib/providers.ts";

setDefaultTimeout(120_000);

const WORK = "sk-ant-oat01-gateway-check-work-account";
const OLD = "sk-ant-oat01-gateway-check-account-under-a-head";

function anthropicToml(url: string): string {
  return `
[providers.anthropic]
dialect = "anthropic-passthrough"
base_url = "${url}"
auth = { kind = "client" }
extra_headers = { anthropic-version = "2023-06-01" }
model_slots = { opus = "claude-opus-5-5", sonnet = "claude-opus-5-5", haiku = "claude-haiku-5-5" }

[[providers.anthropic.models]]
id = "claude-opus-5-5"
label = "Claude Opus 5.5"
context_window = 200000

[[providers.anthropic.models]]
id = "claude-haiku-5-5"
label = "Claude Haiku 5.5"
context_window = 200000
`;
}

let anthropic: Provider;
const made: Splice[] = [];

beforeAll(() => {
  anthropic = new Provider("anthropic").start();
});

afterAll(async () => {
  for (const s of made) await s.dispose();
  anthropic?.stop();
});

async function booted(toml: string, accounts: [string, string, string][]): Promise<Splice> {
  const s = await Splice.create(toml);
  made.push(s);
  for (const [owner, label, token] of accounts) addClaudeAccount(s, owner, label, token);
  await s.start();
  toDoor(s);
  return s;
}

describe("gnh-claude", () => {
  test("with no head, an account added to the provider serves its models by bare id and by selector", async () => {
    const s = await booted(anthropicToml(anthropic.url), [["anthropic", "work", WORK]]);
    // a Claude model is listed by its own Anthropic id, so Claude Code folds it into the built-in row that names it
    const mine = listed(s).filter((r) => r.provider === "anthropic").map((r) => r.model);
    expect(mine).toEqual(["claude-opus-5-5", "claude-haiku-5-5"]);
    const x = await session(s);
    for (const model of ["claude-opus-5-5", "claude-anthropic--claude-opus-5-5"]) {
      const before = anthropic.turns.length;
      const r = await send(x, model);
      expect(r.status, `${model}: ${r.body}`).toBe(200);
      const seen = anthropic.turns.slice(before);
      expect(seen.length).toBe(1);
      expect(seen[0]!.body.model).toBe("claude-opus-5-5");
      expect(carries(seen, WORK)).toBe(true);
      expect(carries(seen, x.key)).toBe(false);
    }
  });

  test("an account added under a head on the provider becomes the provider's, and serves once the head is gone", async () => {
    const headed =
      anthropicToml(anthropic.url) +
      `
[gateway]
aliases = { "claude--" = "anthropic" }

[heads.claude]
provider = "anthropic"
port = ${await freePort()}
discovery_prefix = "claude--"
[heads.claude.claude]
command = "claude"
`;
    const s = await booted(headed, [["claude", "old", OLD]]);
    expect(existsSync(join(s.state, "claude-accounts", "anthropic", "old"))).toBe(true);
    // the head goes; the account stays the provider's
    await s.stop();
    s.writeConfig(anthropicToml(anthropic.url) + `\n[gateway]\naliases = { "claude--" = "anthropic" }\n`);
    await s.start();
    toDoor(s);
    const x = await session(s);
    const before = anthropic.turns.length;
    const r = await send(x, "claude-opus-5-5");
    expect(r.status, r.body).toBe(200);
    expect(carries(anthropic.turns.slice(before), OLD)).toBe(true);
  });

  test("with no account added, the provider is left out, named, with where to add one", async () => {
    const s = await booted(anthropicToml(anthropic.url), []);
    const out = leftOut(s).find((r) => r.provider === "anthropic");
    expect(out).toBeDefined();
    expect(out!.reason).toContain("splice console");
    expect(listed(s).some((r) => r.provider === "anthropic")).toBe(false);
  });
});
