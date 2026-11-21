// cp-picker: in the /model of a `claude` on the door, a provider's model is listed under its own label, with its own
// description before `splice · <command>`, and a provider or a head says for itself whether it is in the picker:
// `picker = false` keeps it out (its selectors still run when named, and splice claude says why it is not listed),
// `picker = true` lists every model a key head's endpoint lists beside the ones splice.toml declares. A head's own
// `picker` wins over its provider's. Absent, the rule of door-default.md decision 4 holds.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { Splice, freePort, until } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { ask, lane, picker, session, toDoor } from "../door/lib/claude.ts";
import { DESCRIPTION, LABEL, MODEL } from "./lib/team.ts";

setDefaultTimeout(120_000);

let team: Upstream;
let hidden: Upstream;
let wide: Upstream;
let s: Splice;

/** A provider on an API key and one head of it. `models` are its declared rows; `provider` and `head` more lines. */
function keyed(o: { key: string; url: string; port: number; models: { id: string; label?: string; description?: string }[]; provider?: string; head?: string; pinned?: string }): string {
  const rows = o.models
    .map(
      (m) => `
[[providers.${o.key}.models]]
id = "${m.id}"
${m.label ? `label = "${m.label}"` : ""}
${m.description ? `description = "${m.description}"` : ""}
context_window = 65536
`,
    )
    .join("");
  return `
[providers.${o.key}]
dialect = "openai-chat"
base_url = "${o.url}"
auth = { kind = "api-key", env = "MOCK_UPSTREAM_KEY" }
${o.provider ?? ""}
${rows}
[heads.${o.key}]
provider = "${o.key}"
port = ${o.port}
discovery_prefix = "claude-${o.key}--"
pinned_model = "${o.pinned ?? o.models[0]!.id}"
${o.head ?? ""}
[heads.${o.key}.claude]
command = "claude-${o.key}"
`;
}

const rows = async () => picker(await session(s)) ?? [];

beforeAll(async () => {
  team = new Upstream("acme", MODEL).start();
  hidden = new Upstream("hidden", "hidden-model").start();
  // what the endpoint lists is wide-listed; splice.toml declares wide-declared
  wide = new Upstream("wide", "wide-listed").start();
  const toml =
    keyed({ key: "acme", url: team.url, port: await freePort(), models: [{ id: MODEL, label: LABEL, description: DESCRIPTION }, { id: "glm-bare" }] }) +
    keyed({ key: "hidden", url: hidden.url, port: await freePort(), models: [{ id: "hidden-model" }], provider: "picker = false" }) +
    keyed({ key: "allof", url: wide.url, port: await freePort(), models: [{ id: "wide-declared" }], provider: "picker = true" }) +
    keyed({ key: "declared", url: wide.url, port: await freePort(), models: [{ id: "wide-declared" }] }) +
    keyed({ key: "headout", url: team.url, port: await freePort(), models: [{ id: MODEL }], provider: "picker = true", head: "picker = false" });
  s = await Splice.boot(toml);
  toDoor(s);
  await until("the picker lists the endpoint's own model on a head that asks for it", async () => (await rows()).some((r) => r.model === "claude-allof--wide-listed"), 60_000);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  team?.stop();
  hidden?.stop();
  wide?.stop();
});

describe("cp-picker", () => {
  test("a provider's model is listed under its own label, its description before splice · <command>", async () => {
    const row = (await rows()).find((r) => r.model === `claude-acme--${MODEL}`);
    expect(row).toBeDefined();
    expect(row!.label).toBe(LABEL);
    expect(row!.description).toBe(`${DESCRIPTION} · splice · claude-acme`);
    expect(lane(s).models!.find((m) => m.model === `claude-acme--${MODEL}`)!.label).toBe(LABEL);
  });

  test("a model with no label or description is listed by its id, described as splice · <command>", async () => {
    const row = (await rows()).find((r) => r.model === "claude-acme--glm-bare");
    expect(row).toBeDefined();
    expect(row!.label).toBe("glm-bare");
    expect(row!.description).toBe("splice · claude-acme");
  });

  test("picker = false keeps a provider out of /model, says why, and its selector still runs when named", async () => {
    expect((await rows()).some((r) => r.model.startsWith("claude-hidden--"))).toBe(false);
    const out = (lane(s).left_out ?? []).find((h) => h.head === "hidden");
    expect(out).toBeDefined();
    expect(out!.reason).toContain("picker = false");
    const t = await ask(await session(s), { model: "claude-hidden--hidden-model" });
    expect(t.status).toBe(200);
    expect(t.reply).toBe(hidden.reply());
  });

  test("picker = true lists what a key head's endpoint lists beside what splice.toml declares", async () => {
    const listed = (await rows()).map((r) => r.model);
    expect(listed).toContain("claude-allof--wide-declared");
    expect(listed).toContain("claude-allof--wide-listed");
    // with no picker key, a key head that declares models lists only those
    expect(listed).toContain("claude-declared--wide-declared");
    expect(listed).not.toContain("claude-declared--wide-listed");
  });

  test("a head's own picker = false wins over its provider's picker = true", async () => {
    expect((await rows()).some((r) => r.model.startsWith("claude-headout--"))).toBe(false);
    expect((lane(s).left_out ?? []).find((h) => h.head === "headout")?.reason ?? "").toContain("picker = false");
  });

  test("splice claude --json lists exactly the rows /model lists", async () => {
    expect(lane(s).models!.map((m) => m.model)).toEqual((await rows()).map((r) => r.model));
  });
});
