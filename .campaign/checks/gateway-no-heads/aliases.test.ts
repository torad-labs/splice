// gnh-aliases: the selectors callers use today keep working through `[gateway] aliases`, which maps an older head's
// prefix to a provider, and nothing of it is read from a head. An aliased selector runs on its provider as the bare
// model id and is never listed beside the canonical one. Removing every `[heads.*]` block leaves the gateway listing and
// serving exactly the same models, under every spelling it answered before.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { lane, session, toDoor } from "../door/lib/claude.ts";
import { modelsOf } from "../catalog/lib/gateway.ts";
import { Splice, freePort } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { PEBBLE, SLATE, listed, provider, selector, send } from "./lib/providers.ts";

setDefaultTimeout(120_000);

let pebble: Upstream;
let slate: Upstream;
let s: Splice;
let providers = "";

const ALIASES = `
[gateway]
aliases = { "claude-rock--" = "pebble", "zeta--" = "slate" }
`;

/** Every spelling a caller may name today, and the mock and model id it must reach. */
const spellings = () => [
  { model: selector("pebble", PEBBLE), to: pebble, id: PEBBLE },
  { model: `claude-rock--${PEBBLE}`, to: pebble, id: PEBBLE },
  { model: selector("slate", SLATE), to: slate, id: SLATE },
  { model: `zeta--${SLATE}`, to: slate, id: SLATE },
];

async function servesEvery(): Promise<void> {
  const x = await session(s);
  for (const { model, to, id } of spellings()) {
    const before = to.completions().length;
    const r = await send(x, model);
    expect(r.status, `${model}: ${r.body}`).toBe(200);
    const seen = to.completions().slice(before);
    expect(seen.length).toBe(1);
    expect(seen[0]!.body?.model).toBe(id);
  }
}

beforeAll(async () => {
  pebble = new Upstream("pebble", PEBBLE).start();
  slate = new Upstream("slate", SLATE).start();
  providers =
    provider({ key: "pebble", url: pebble.url, model: PEBBLE, window: 65536, env: "PEBBLE_KEY" }) +
    provider({ key: "slate", url: slate.url, model: SLATE, window: 262144, env: "SLATE_KEY" }) +
    ALIASES;
  // the heads a person had before: `rock` on pebble and `zeta` on slate, each with its own older prefix
  const heads = `
[heads.rock]
provider = "pebble"
port = ${await freePort()}
discovery_prefix = "claude-rock--"
pinned_model = "${PEBBLE}"
[heads.rock.claude]
command = "claude-rock"

[heads.zeta]
provider = "slate"
port = ${await freePort()}
discovery_prefix = "zeta--"
pinned_model = "${SLATE}"
[heads.zeta.claude]
command = "zeta"
`;
  s = await Splice.boot(providers + heads, { PEBBLE_KEY: "sk-pebble-check", SLATE_KEY: "sk-slate-check" });
  toDoor(s);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  pebble?.stop();
  slate?.stop();
});

describe("gnh-aliases", () => {
  let before: string[] = [];

  test("the list holds each model once, under its provider's selector, never an aliased spelling", () => {
    before = listed(s).map((r) => r.model);
    expect(before).toEqual([selector("pebble", PEBBLE), selector("slate", SLATE)]);
  });

  test("the gateway's own model list says the same", async () => {
    const door = lane(s).door!;
    const x = await session(s);
    const r = await modelsOf(door, x.key);
    expect(r.status).toBe(200);
    expect(r.rows.map((row) => row.id)).toEqual(before);
  });

  test("today's selectors and the canonical ones each run on their provider as the bare model id", async () => {
    await servesEvery();
  });

  test("`splice claude door --model` takes an aliased selector", () => {
    const r = s.cli(["claude", "door", "--model", `zeta--${SLATE}`]);
    expect(r.code, `${r.out}\n${r.err}`).toBe(0);
  });

  test("with every head removed, the gateway lists and serves exactly the same", async () => {
    await s.stop();
    s.writeConfig(providers);
    await s.start();
    toDoor(s);
    expect(listed(s).map((r) => r.model)).toEqual(before);
    await servesEvery();
  }, 300_000);
});
