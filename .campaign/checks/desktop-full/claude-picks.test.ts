// df-claude-picks: a Claude model picked in Claude Desktop runs on Claude, on the Claude accounts added to splice (the
// claude head), whenever a head serves that very model, in every spelling Desktop's Code tab sends it (Mantle's in
// standard mode, Claude's own in 3P mode). The door's saved default stands in only for a Claude model no head serves.
// Tier aliases and side calls follow the head the session is on.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { Provider } from "../harness/lib/capability.ts";
import { Splice, freePort } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { type Door, doorTurn, slottedHead, turnOn } from "../desktop/lib/desktop.ts";
import { addClaudeAccount, claudeHeadToml } from "./lib/full.ts";

setDefaultTimeout(120_000);

const ASTRA_BIG = "claude-astra--astra-large";
const TOKEN = "sk-ant-oat01-an-account-added-to-splice";

let anthropic: Provider;
let astra: Upstream;
let s: Splice;
let d: Door;

beforeAll(async () => {
  anthropic = new Provider("anthropic").start();
  astra = new Upstream("astra", "astra-large").start();
  s = await Splice.create(
    slottedHead({ key: "astra", command: "claude-astra", port: await freePort(), url: astra.url, big: "astra-large", small: "astra-small" }) +
      claudeHeadToml({ port: await freePort(), url: anthropic.url }),
  );
  addClaudeAccount(s, "claude", "work", TOKEN);
  await s.start();
  d = turnOn(s, "--model", ASTRA_BIG);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  anthropic?.stop();
  astra?.stop();
});

/** Where one turn of `session` naming `model` ran: `claude` with the model Anthropic was asked for, or `astra` with
 *  the model astra was asked for. */
async function ranOn(model: string, session: string): Promise<[string, unknown]> {
  const atAnthropic = anthropic.turns.length;
  const atAstra = astra.completions().length;
  const t = await doorTurn(d.url, { key: d.key, model, session });
  expect([model, t.status]).toEqual([model, 200]);
  if (anthropic.turns.length > atAnthropic) {
    const seen = anthropic.turns[atAnthropic]!;
    expect(JSON.stringify(seen.headers)).toContain(TOKEN);
    return ["claude", seen.body.model];
  }
  if (astra.completions().length > atAstra) return ["astra", astra.completions()[atAstra]!.body?.model];
  throw new Error(`no provider saw the turn naming ${model}`);
}

describe("df-claude-picks", () => {
  test("the default model is a GPT-like head's, not Claude", () => {
    expect(d.model).toBe(ASTRA_BIG);
  });

  test("Claude Opus picked in Desktop runs Claude Opus on the added account, in every spelling the Code tab sends", async () => {
    for (const asked of ["anthropic.claude-opus-5-5", "us.anthropic.claude-opus-5-5-v1:0", "claude-opus-5-5"]) {
      expect([asked, await ranOn(asked, crypto.randomUUID())]).toEqual([asked, ["claude", "claude-opus-5-5"]]);
    }
  });

  test("the session is on the claude head from then: its side calls and tier aliases run Claude's tier models there", async () => {
    const session = crypto.randomUUID();
    expect(await ranOn("anthropic.claude-opus-5-5", session)).toEqual(["claude", "claude-opus-5-5"]);
    expect(await ranOn("anthropic.claude-haiku-5-5", session)).toEqual(["claude", "claude-haiku-5-5"]);
    expect(await ranOn("haiku", session)).toEqual(["claude", "claude-haiku-5-5"]);
    expect(await ranOn("opus", session)).toEqual(["claude", "claude-opus-5-5"]);
  });

  test("a Claude model no head serves runs the saved default, and is not refused", async () => {
    expect(await ranOn("anthropic.claude-sonnet-5-5", crypto.randomUUID())).toEqual(["astra", "astra-large"]);
  });

  test("a session on the default model's head keeps its tiers there", async () => {
    const session = crypto.randomUUID();
    expect(await ranOn(ASTRA_BIG, session)).toEqual(["astra", "astra-large"]);
    expect(await ranOn("anthropic.claude-haiku-5-5", session)).toEqual(["astra", "astra-small"]);
  });
});
