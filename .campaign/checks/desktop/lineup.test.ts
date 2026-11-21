// desktop-lineup: the door serves every model of every head splice holds a credential for, one selector a model, and
// `splice desktop` lists them, read from splice.toml's heads at each use. A head that forwards the caller's own Claude
// login is left out and named with the reason. Nothing of the lineup is written into the terminal's settings file.
// (Rewritten for 0.6.1: the picker the old door wrote into that file is gone.)

import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { Splice, freePort, until } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { launch, sessionKey } from "../speed/lib/session.ts";
import { type Door, clientHead, desktop, doorTurn, readSettings, slottedHead, turnOn } from "./lib/desktop.ts";

let astra: Upstream;
let borealis: Upstream;
let corvus: Upstream;
let cygnus: Upstream;
let s: Splice;
let d: Door;
let astraPort = 0;
let astraHead = "";
let borealisHead = "";
let corvusHead = "";

beforeAll(async () => {
  astra = new Upstream("astra", "astra-large").start();
  borealis = new Upstream("borealis", "borealis-large").start();
  corvus = new Upstream("corvus", "claude-opus-5-5").start();
  cygnus = new Upstream("cygnus", "cygnus-large").start();
  astraPort = await freePort();
  astraHead = slottedHead({ key: "astra", command: "claude-astra", port: astraPort, url: astra.url, big: "astra-large", small: "astra-small" });
  borealisHead = slottedHead({ key: "borealis", command: "claude-borealis", port: await freePort(), url: borealis.url, big: "borealis-large", small: "borealis-small" });
  corvusHead = clientHead({ key: "corvus", command: "claude-corvus", port: await freePort(), url: corvus.url });
  s = await Splice.boot(astraHead + borealisHead + corvusHead);
  d = turnOn(s);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  astra?.stop();
  borealis?.stop();
  corvus?.stop();
  cygnus?.stop();
});

/** The selectors `splice desktop` lists. */
const selectors = () => desktop(s).models.map((m) => m.model);

describe("desktop-lineup", () => {
  test("splice desktop lists one model for each model of each head in the door, in splice.toml's order", () => {
    expect(desktop(s).models).toEqual([
      { model: "claude-astra--astra-large", label: "astra-large", head: "astra", command: "claude-astra" },
      { model: "claude-astra--astra-small", label: "astra-small", head: "astra", command: "claude-astra" },
      { model: "claude-borealis--borealis-large", label: "borealis-large", head: "borealis", command: "claude-borealis" },
      { model: "claude-borealis--borealis-small", label: "borealis-small", head: "borealis", command: "claude-borealis" },
    ]);
  });

  test("nothing of the lineup is written into the terminal's settings file", () => {
    expect(readSettings(s).modelPicker).toBeUndefined();
    expect(readSettings(s).model).toBeUndefined();
  });

  test("a model's selector is the ID its head's own /v1/models lists", async () => {
    const key = sessionKey(await launch(s, "claude-astra"));
    const r = await fetch(`http://127.0.0.1:${astraPort}/v1/models`, { headers: { "x-api-key": key, "anthropic-version": "2023-06-01" } });
    expect(r.status).toBe(200);
    const ids = ((await r.json()) as { data: { id: string }[] }).data.map((m) => m.id);
    for (const m of desktop(s).models.filter((x) => x.head === "astra")) expect(ids).toContain(m.model);
  });

  test("a head that forwards the caller's own Claude login has no model, and splice desktop names it with the reason", () => {
    expect(selectors().filter((m) => m.startsWith("claude-corvus--"))).toEqual([]);
    const out = desktop(s).left_out;
    expect(out.map((h) => [h.head, h.command])).toEqual([["corvus", "claude-corvus"]]);
    expect(out[0]!.reason).toMatch(/login/i);
    const said = s.cli(["desktop"]);
    expect(said.code).toBe(0);
    expect(said.out).toContain("claude-corvus");
  });

  test("a head added to splice is in the door with no command run, and its models run through the same door", async () => {
    const cygnusHead = slottedHead({ key: "cygnus", command: "claude-cygnus", port: await freePort(), url: cygnus.url, big: "cygnus-large", small: "cygnus-small" });
    s.writeConfig(astraHead + borealisHead + corvusHead + cygnusHead);
    await s.restart();
    await until("the door lists the new head's models", () => selectors().includes("claude-cygnus--cygnus-large"));
    expect(selectors()).toEqual([
      "claude-astra--astra-large",
      "claude-astra--astra-small",
      "claude-borealis--borealis-large",
      "claude-borealis--borealis-small",
      "claude-cygnus--cygnus-large",
      "claude-cygnus--cygnus-small",
    ]);
    // the door and its key are the ones set in Desktop before
    const again = turnOn(s);
    expect(again.url).toBe(d.url);
    expect(again.key).toBe(d.key);
    const t = await doorTurn(d.url, { key: d.key, model: "claude-cygnus--cygnus-small", session: crypto.randomUUID() });
    expect(t.status).toBe(200);
    expect(t.reply).toBe(cygnus.reply());
  }, 120_000);

  test("a head taken out of splice leaves the door, and its models are refused", async () => {
    s.writeConfig(astraHead + corvusHead);
    await s.restart();
    await until("the door has dropped the removed heads", () => !selectors().some((m) => m.startsWith("claude-borealis--")));
    expect(selectors()).toEqual(["claude-astra--astra-large", "claude-astra--astra-small"]);
    const before = borealis.completions().length;
    const t = await doorTurn(d.url, { key: d.key, model: "claude-borealis--borealis-large", session: crypto.randomUUID() });
    expect(t.status).toBe(400);
    expect(t.error?.type).toBe("invalid_request_error");
    expect(borealis.completions().length).toBe(before);
  }, 120_000);
});
