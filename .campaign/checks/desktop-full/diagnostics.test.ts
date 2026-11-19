// df-diagnostics: `splice desktop` and `splice doctor` say which mode Claude Desktop is in (off, standard, app, or
// third-party mode with a configuration that is not splice's), whether Desktop has been reopened since splice changed
// its configuration, the rows of Desktop's picker, and the last turn sent through the door in each mode.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { Splice, freePort, mockHead } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { type Door, doorTurn, slottedHead, turnOn } from "../desktop/lib/desktop.ts";
import { allModels, appOnYes, base, customHeaders, deskJson, gatewayTurn, modelsPage, runHelper, theirConfig } from "./lib/full.ts";

setDefaultTimeout(180_000);

const ASTRA = "claude-astra--astra-large";
const ASTRA_SMALL = "claude-astra--astra-small";

let astra: Upstream;
let vega: Upstream;
let heads: string;
let s: Splice;
let d: Door;

beforeAll(async () => {
  astra = new Upstream("astra", "astra-large").start();
  vega = new Upstream("vega", "grok-vega").start();
  heads = slottedHead({ key: "astra", command: "claude-astra", port: await freePort(), url: astra.url, big: "astra-large", small: "astra-small" });
  s = await Splice.boot(heads);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  astra?.stop();
  vega?.stop();
});

/** `splice desktop` and `splice doctor` in words. */
function said(): { desktop: string; doctor: string } {
  const a = s.cli(["desktop"]);
  const b = s.cli(["doctor"]);
  return { desktop: a.out + a.err, doctor: b.out + b.err };
}

describe("df-diagnostics", () => {
  test("off: both say Desktop is off", () => {
    expect(deskJson(s).mode).toBe("off");
    expect(deskJson(s).relaunched).toBeNull();
    const w = said();
    expect(w.desktop).toMatch(/^Desktop: off/m);
    expect(w.doctor).toMatch(/Claude Desktop: off/);
  });

  test("standard mode: both say the Code tab runs on splice and Chat stays on claude.ai", () => {
    d = turnOn(s, "--model", ASTRA);
    expect(deskJson(s).mode).toBe("standard");
    const w = said();
    expect(w.desktop).toMatch(/^Desktop: on, standard mode/m);
    expect(w.doctor).toMatch(/Claude Desktop: on, standard mode/);
  });

  test("app mode: not reopened until Desktop asks the door after the change, and both say to quit and reopen it", async () => {
    appOnYes(s);
    expect(deskJson(s).mode).toBe("app");
    expect(deskJson(s).relaunched).toBe(false);
    let w = said();
    expect(w.desktop).toMatch(/^Desktop: on, app mode/m);
    expect(w.desktop).toMatch(/quit and reopen/i);
    expect(w.doctor).toMatch(/Claude Desktop: on, app mode/);
    expect(w.doctor).toMatch(/quit and reopen/i);
    const p = await modelsPage(base(s), { key: runHelper(s).out, headers: customHeaders(s) });
    expect(p.status).toBe(200);
    expect(deskJson(s).relaunched).toBe(true);
    w = said();
    expect(w.desktop).not.toMatch(/quit and reopen/i);
  });

  test("the picker rows Desktop is given are said, as discovery answers them", async () => {
    const rows = await allModels(base(s), runHelper(s).out);
    expect((deskJson(s).picker ?? []).map((r) => r.id)).toEqual(rows.map((r) => r.id));
    for (const r of rows) expect(said().desktop).toContain(r.id);
  });

  test("the last turn of each mode is kept apart", async () => {
    const store = crypto.randomUUID();
    const app = crypto.randomUUID();
    expect((await doorTurn(d.url, { key: d.key, model: ASTRA_SMALL, session: store })).status).toBe(200);
    expect((await gatewayTurn(s, { key: runHelper(s).out, model: ASTRA, session: app })).status).toBe(200);
    const last = deskJson(s).last_turns ?? {};
    expect(last.standard).toEqual(expect.objectContaining({ session_id: store, head: "astra", model_asked: ASTRA_SMALL }));
    expect(last.app).toEqual(expect.objectContaining({ session_id: app, head: "astra", model_asked: ASTRA }));
    const w = said().desktop;
    expect(w).toContain(store);
    expect(w).toContain(app);
  });

  test("a head added after Desktop read its picker: both say the picker changed and to quit and reopen, until Desktop reads it again", async () => {
    expect(deskJson(s).picker_current).toBe(true);
    expect(said().desktop).not.toMatch(/picker changed/i);
    s.writeConfig(heads + mockHead({ key: "vega", command: "claude-vega", port: await freePort(), url: vega.url, model: "grok-vega" }));
    await s.restart();
    expect(deskJson(s).mode).toBe("app");
    expect(deskJson(s).picker_current).toBe(false);
    let w = said();
    expect(w.desktop).toMatch(/picker changed since Claude Desktop last read it; quit and reopen Claude Desktop/i);
    expect(w.doctor).toMatch(/picker changed since Claude Desktop last read it; quit and reopen Claude Desktop/i);
    const p = await modelsPage(base(s), { key: runHelper(s).out, headers: customHeaders(s) });
    expect(p.status).toBe(200);
    expect(p.data.map((r) => r.id)).toContain("claude-vega--grok-vega");
    expect(deskJson(s).picker_current).toBe(true);
    w = said();
    expect(w.desktop).not.toMatch(/picker changed/i);
  }, 240_000);

  test("a configuration of someone else's applied in Desktop's library is named, in both", () => {
    s.cli(["desktop", "off"]);
    theirConfig(s);
    turnOn(s, "--model", ASTRA);
    expect(deskJson(s).mode).toBe("other");
    const w = said();
    expect(w.desktop).toMatch(/^Desktop: .*third-party mode.*Work Bedrock/m);
    expect(w.doctor).toMatch(/Claude Desktop: .*third-party mode.*Work Bedrock/);
  });
});
