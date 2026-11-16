// desktop-lineup: the Code tab's picker lists every model splice serves from every head it holds a credential for,
// one row a model, and follows splice while Desktop is on. A head that forwards the caller's own Claude login is
// left out and named with the reason.

import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { Splice, freePort, until } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { launch, sessionKey } from "../speed/lib/session.ts";
import { type Door, clientHead, desktop, door, doorTurn, lineup, readSettings, slottedHead, turnOn } from "./lib/desktop.ts";

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

describe("desktop-lineup", () => {
  test("the picker shows only splice's rows: one for each model of each head in the door, in splice.toml's order", () => {
    expect((readSettings(s).modelPicker as { replaceBuiltInOptions?: boolean }).replaceBuiltInOptions).toBe(true);
    expect(lineup(s)).toEqual([
      { model: "claude-astra--astra-large", label: "astra-large", description: "splice · claude-astra" },
      { model: "claude-astra--astra-small", label: "astra-small", description: "splice · claude-astra" },
      { model: "claude-borealis--borealis-large", label: "borealis-large", description: "splice · claude-borealis" },
      { model: "claude-borealis--borealis-small", label: "borealis-small", description: "splice · claude-borealis" },
    ]);
  });

  test("a row's model is the ID its head's own /v1/models lists", async () => {
    const key = sessionKey(await launch(s, "claude-astra"));
    const r = await fetch(`http://127.0.0.1:${astraPort}/v1/models`, { headers: { "x-api-key": key, "anthropic-version": "2023-06-01" } });
    expect(r.status).toBe(200);
    const ids = ((await r.json()) as { data: { id: string }[] }).data.map((m) => m.id);
    for (const row of lineup(s).filter((x) => x.description === "splice · claude-astra")) expect(ids).toContain(row.model);
  });

  test("splice desktop lists the same models, each with its head and its command", () => {
    expect(desktop(s).models).toEqual([
      { model: "claude-astra--astra-large", label: "astra-large", head: "astra", command: "claude-astra" },
      { model: "claude-astra--astra-small", label: "astra-small", head: "astra", command: "claude-astra" },
      { model: "claude-borealis--borealis-large", label: "borealis-large", head: "borealis", command: "claude-borealis" },
      { model: "claude-borealis--borealis-small", label: "borealis-small", head: "borealis", command: "claude-borealis" },
    ]);
  });

  test("a head that forwards the caller's own Claude login has no row, and splice desktop names it with the reason", () => {
    expect(lineup(s).filter((r) => r.model.startsWith("claude-corvus--"))).toEqual([]);
    const out = desktop(s).left_out;
    expect(out.map((h) => [h.head, h.command])).toEqual([["corvus", "claude-corvus"]]);
    expect(out[0]!.reason).toMatch(/login/i);
    const said = s.cli(["desktop"]);
    expect(said.code).toBe(0);
    expect(said.out).toContain("claude-corvus");
  });

  test("a head added to splice is in the picker with no command run, and its models run through the same door", async () => {
    const cygnusHead = slottedHead({ key: "cygnus", command: "claude-cygnus", port: await freePort(), url: cygnus.url, big: "cygnus-large", small: "cygnus-small" });
    s.writeConfig(astraHead + borealisHead + corvusHead + cygnusHead);
    await s.restart();
    await until("the picker lists the new head's models", () => lineup(s).some((r) => r.model === "claude-cygnus--cygnus-large"));
    expect(lineup(s).map((r) => r.model)).toEqual([
      "claude-astra--astra-large",
      "claude-astra--astra-small",
      "claude-borealis--borealis-large",
      "claude-borealis--borealis-small",
      "claude-cygnus--cygnus-large",
      "claude-cygnus--cygnus-small",
    ]);
    // the door and its key are the ones the file held before
    expect(door(s).url).toBe(d.url);
    expect(door(s).key).toBe(d.key);
    const t = await doorTurn(d.url, { key: d.key, model: "claude-cygnus--cygnus-small", session: crypto.randomUUID() });
    expect(t.status).toBe(200);
    expect(t.reply).toBe(cygnus.reply());
  });

  test("a head taken out of splice leaves the picker, and its models are refused", async () => {
    s.writeConfig(astraHead + corvusHead);
    await s.restart();
    await until("the picker has dropped the removed heads", () => !lineup(s).some((r) => r.model.startsWith("claude-borealis--")));
    expect(lineup(s).map((r) => r.model)).toEqual(["claude-astra--astra-large", "claude-astra--astra-small"]);
    const before = borealis.completions().length;
    const t = await doorTurn(d.url, { key: d.key, model: "claude-borealis--borealis-large", session: crypto.randomUUID() });
    expect(t.status).toBe(400);
    expect(t.error?.type).toBe("invalid_request_error");
    expect(borealis.completions().length).toBe(before);
  });
});
