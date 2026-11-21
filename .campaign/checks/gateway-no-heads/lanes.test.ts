// gnh-lanes: the Splice gateway's list is read from the providers in splice.toml, never from a head. With no `[heads.*]`
// at all, every provider whose login splice holds is listed, each model once, under `claude-<provider>--<model id>`, in
// splice.toml's order; a provider whose endpoint is down keeps its declared model in the list; a provider with no key is
// left out, named, with what to do. `splice claude --json`, `splice desktop --json` and the gateway's own
// `GET /v1/models` say the same, and serving them opens no port: the daemon listens on its control port alone, as it
// does today with no head.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { lane, session, toDoor } from "../door/lib/claude.ts";
import { modelsOf } from "../catalog/lib/gateway.ts";
import { Splice, freePort, json } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { PEBBLE, SLATE, leftOut, listed, listening, provider, selector } from "./lib/providers.ts";

setDefaultTimeout(120_000);

const BASALT = "Basalt-7-Instruct";
const GRANITE = "Granite-3-Mini";

let pebble: Upstream;
let slate: Upstream;
let s: Splice;

beforeAll(async () => {
  pebble = new Upstream("pebble", PEBBLE).start();
  slate = new Upstream("slate", SLATE).start();
  const down = `http://127.0.0.1:${await freePort()}/v1`;
  const toml =
    provider({ key: "pebble", url: pebble.url, model: PEBBLE, window: 65536, env: "PEBBLE_KEY" }) +
    provider({ key: "slate", url: slate.url, model: SLATE, window: 262144, env: "SLATE_KEY" }) +
    // a tunnel that is down when splice starts
    provider({ key: "basalt", url: down, model: BASALT, window: 32768, env: "BASALT_KEY" }) +
    // a provider whose key splice does not hold
    provider({ key: "granite", url: down, model: GRANITE, window: 32768, env: "GRANITE_KEY" });
  s = await Splice.boot(toml, { PEBBLE_KEY: "sk-pebble-check", SLATE_KEY: "sk-slate-check", BASALT_KEY: "sk-basalt-check" });
  toDoor(s);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  pebble?.stop();
  slate?.stop();
});

describe("gnh-lanes", () => {
  test("with no head, every provider splice holds a login for is listed, each model once, in splice.toml's order", () => {
    const rows = listed(s);
    expect(rows.map((r) => r.model)).toEqual([selector("pebble", PEBBLE), selector("slate", SLATE), selector("basalt", BASALT)]);
    expect(rows.map((r) => r.provider)).toEqual(["pebble", "slate", "basalt"]);
    expect(rows.map((r) => r.label)).toEqual([PEBBLE, SLATE, BASALT]);
    // every model is reached through the one `claude`
    expect(new Set(rows.map((r) => r.command))).toEqual(new Set(["claude"]));
  });

  test("a provider whose key splice does not hold is left out, named, with the variable to set", () => {
    const out = leftOut(s).find((r) => r.provider === "granite");
    expect(out).toBeDefined();
    expect(out!.reason).toContain("GRANITE_KEY");
    expect(listed(s).some((r) => r.model.includes(GRANITE))).toBe(false);
  });

  test("the gateway's own model list, as Claude Code's gateway mode reads it, holds the same models in the same order", async () => {
    const door = lane(s).door!;
    const x = await session(s);
    const r = await modelsOf(door, x.key);
    expect(r.status).toBe(200);
    expect(r.rows.map((row) => row.id)).toEqual(listed(s).map((m) => m.model));
  });

  test("splice desktop --json lists the same models in the same order", () => {
    const desktop = json<{ models: { model: string }[] }>(s.cli(["desktop", "--json"]));
    expect(desktop.models.map((m) => m.model)).toEqual(listed(s).map((r) => r.model));
  });

  test("a session on the gateway starts on a model the gateway lists", () => {
    const r = s.cli(["claude", "--json"]);
    const starting = json<{ default_model?: string }>(r).default_model;
    expect(listed(s).map((m) => m.model)).toContain(String(starting));
  });

  test("serving the providers opens no port: the daemon listens on its control port alone", () => {
    expect(listening(s)).toEqual([s.controlPort]);
  });
});
