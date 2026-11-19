// cp-tiers: Claude Code names a tier, not a model, for its side calls (titles and summaries on the haiku tier, which
// is also its small-fast model) and for a subagent that names one (opus, sonnet, haiku, fable). In a door session on a
// provider's model, each tier runs on that provider as its head's model_slots map it, and a tier it does not map runs
// its pinned model; none goes to the door's default model on another provider.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { Splice, freePort, mockHead } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { ask, lane, session, toDoor } from "../door/lib/claude.ts";

setDefaultTimeout(120_000);

const TIERS: [string, string][] = [
  ["anthropic.claude-opus-5-5", "glm-big"],
  ["anthropic.claude-sonnet-5-5", "glm-mid"],
  ["anthropic.claude-haiku-5-5", "glm-small"],
  ["anthropic.claude-fable-5-2", "glm-fable"],
];

let other: Upstream;
let team: Upstream;
let pinnedOnly: Upstream;
let s: Splice;

function model(key: string, id: string): string {
  return `
[[providers.${key}.models]]
id = "${id}"
context_window = 65536
`;
}

function ranAs(u: Upstream, marker: string): string | null {
  const seen = u.completions().filter((c) => JSON.stringify(c.body?.messages ?? []).includes(marker));
  return seen.length ? String(seen.at(-1)!.body?.model) : null;
}

beforeAll(async () => {
  other = new Upstream("other", "other-model").start();
  team = new Upstream("acme", "glm-big").start();
  pinnedOnly = new Upstream("solo", "solo-model").start();
  const toml =
    mockHead({ key: "other", command: "claude-other", port: await freePort(), url: other.url, model: "other-model" }) +
    `
[providers.acme]
dialect = "openai-chat"
base_url = "${team.url}"
auth = { kind = "api-key", env = "MOCK_UPSTREAM_KEY" }
${["glm-big", "glm-mid", "glm-small", "glm-fable"].map((m) => model("acme", m)).join("")}
[heads.acme]
provider = "acme"
port = ${await freePort()}
discovery_prefix = "claude-acme--"
pinned_model = "glm-big"
model_slots = { opus = "glm-big", sonnet = "glm-mid", haiku = "glm-small", fable = "glm-fable" }
` +
    mockHead({ key: "solo", command: "claude-solo", port: await freePort(), url: pinnedOnly.url, model: "solo-model" });
  s = await Splice.boot(toml);
  toDoor(s);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  other?.stop();
  team?.stop();
  pinnedOnly?.stop();
});

describe("cp-tiers", () => {
  test("the door's default model is on another provider", () => {
    expect(lane(s).default_model).toBe("claude-other--other-model");
  });

  test("in a session on the provider's model, every tier runs on it as model_slots map it", async () => {
    const x = await session(s);
    const id = crypto.randomUUID();
    expect((await ask(x, { model: "claude-acme--glm-big", session: id })).status).toBe(200);
    for (const [tier, mapped] of TIERS) {
      const marker = `cp-tiers-${crypto.randomUUID()}`;
      const t = await ask(x, { model: tier, session: id, content: marker });
      expect(t.status, `${tier}: ${t.body.slice(0, 300)}`).toBe(200);
      expect([tier, ranAs(team, marker)]).toEqual([tier, mapped]);
      expect(ranAs(other, marker)).toBeNull();
    }
  });

  test("in a session on a provider that maps no tier, every tier runs its pinned model there", async () => {
    const x = await session(s);
    const id = crypto.randomUUID();
    expect((await ask(x, { model: "claude-solo--solo-model", session: id })).status).toBe(200);
    for (const [tier] of TIERS) {
      const marker = `cp-tiers-solo-${crypto.randomUUID()}`;
      const t = await ask(x, { model: tier, session: id, content: marker });
      expect(t.status).toBe(200);
      expect([tier, ranAs(pinnedOnly, marker)]).toEqual([tier, "solo-model"]);
      expect(ranAs(other, marker)).toBeNull();
    }
  });
});
