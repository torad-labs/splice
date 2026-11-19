// df-app-on: `splice desktop on --app` puts all of Claude Desktop on splice, as its gateway provider, by writing one
// configuration into Desktop's own local configuration library and applying it. Nothing is pasted: the credential is
// a helper splice names, and no key is written. It says first what the person gives up and that Desktop must be quit
// and reopened, and goes on only when they agree (`--yes`). `splice desktop off` puts the library back as it was.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { existsSync, readFileSync, statSync } from "node:fs";
import { isAbsolute, join } from "node:path";
import { Splice, freePort } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { doorTurn, slottedHead, turnOn } from "../desktop/lib/desktop.ts";
import { appOn, appOnYes, applied, base, deskJson, gatewayTurn, library, libraryFiles, meta, runHelper, theirConfig } from "./lib/full.ts";

setDefaultTimeout(180_000);

const ASTRA_BIG = "claude-astra--astra-large";
const COSTS = [/chat/i, /cowork/i, /(history|conversations).*(this (computer|device)|locally)|(stored|kept) (on|locally)/i, /remote control/i, /cloud/i, /SSH/];

let astra: Upstream;
let s: Splice;

beforeAll(async () => {
  astra = new Upstream("astra", "astra-large").start();
  s = await Splice.boot(slottedHead({ key: "astra", command: "claude-astra", port: await freePort(), url: astra.url, big: "astra-large", small: "astra-small" }));
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  astra?.stop();
});

/** The desktop key the helper gives Desktop. */
function helperKey(): string {
  const h = runHelper(s);
  expect(h.code).toBe(0);
  return h.out;
}

describe("df-app-on", () => {
  test("without --yes and with no terminal to ask on, it says what Desktop gives up, changes nothing, and names --yes", () => {
    const r = appOn(s);
    const said = r.out + r.err;
    expect(r.code).not.toBe(0);
    for (const cost of COSTS) expect([String(cost), cost.test(said)]).toEqual([String(cost), true]);
    expect(said).toContain("--yes");
    expect(libraryFiles(s)).toEqual({});
  });

  test("with --yes it says the same, that Desktop must be quit and reopened, and applies splice's configuration", () => {
    const r = appOnYes(s);
    const said = r.out + r.err;
    for (const cost of COSTS) expect([String(cost), cost.test(said)]).toEqual([String(cost), true]);
    expect(said).toMatch(/quit and reopen/i);
    const m = meta(s)!;
    expect(m.appliedId).toMatch(/^[a-f0-9-]{36}$/);
    expect(m.entries.filter((e) => e.id === m.appliedId)).toEqual([expect.objectContaining({ id: m.appliedId, name: "splice" })]);
  });

  test("the configuration makes splice's door Desktop's gateway, with discovery on and no model list of its own", () => {
    const c = applied(s)!;
    expect(c.inferenceProvider).toBe("gateway");
    expect(base(s)).toMatch(/^http:\/\/127\.0\.0\.1:\d+\/desktop$/);
    expect(base(s)).toBe(deskJson(s).door);
    expect(c.modelDiscoveryEnabled).toBe(true);
    expect(c.inferenceModels).toBeUndefined();
    expect(c.autoModeEnabled).toBe(true);
    expect(c.inferenceGatewayAutoModeServerChecks).toBe(false);
  });

  test("the credential is a helper Desktop runs, an absolute path to an executable file, and no key is in the library", () => {
    const c = applied(s)!;
    expect(typeof c.inferenceCredentialHelper).toBe("string");
    const helper = c.inferenceCredentialHelper as string;
    expect(isAbsolute(helper)).toBe(true);
    expect(statSync(helper).isFile()).toBe(true);
    expect(statSync(helper).mode & 0o111).not.toBe(0);
    expect(c.inferenceGatewayApiKey).toBeUndefined();
    const key = helperKey();
    expect(key.length).toBeGreaterThan(16);
    for (const [name, text] of Object.entries(libraryFiles(s))) expect([name, text.includes(key)]).toEqual([name, false]);
  });

  test("a turn sent as Desktop's Code tab sends it, with the helper's key, runs on the head", async () => {
    for (const scheme of ["bearer", "x-api-key"] as const) {
      const t = await gatewayTurn(s, { key: helperKey(), scheme, model: ASTRA_BIG, session: crypto.randomUUID() });
      expect([scheme, t.status, t.reply]).toEqual([scheme, 200, astra.reply()]);
    }
  });

  test("splice desktop says Desktop is on, in app mode", () => {
    const j = deskJson(s);
    expect(j.on).toBe(true);
    expect(j.mode).toBe("app");
  });

  test("on --app again keeps one configuration of splice's", () => {
    appOnYes(s);
    const m = meta(s)!;
    expect(m.entries.filter((e) => e.name === "splice").length).toBe(1);
    expect(Object.keys(libraryFiles(s)).filter((n) => n !== "_meta.json").length).toBe(1);
  });

  test("off takes it out, leaves the library as it was before (none), closes the door and says to reopen Desktop", async () => {
    const key = helperKey();
    const r = s.cli(["desktop", "off"]);
    expect(r.code).toBe(0);
    expect(r.out + r.err).toMatch(/quit and reopen/i);
    expect(libraryFiles(s)).toEqual({});
    const t = await doorTurn(`${deskJson(s).door}`, { key, model: ASTRA_BIG, session: crypto.randomUUID() });
    expect(t.status).toBe(403);
    expect(deskJson(s).on).toBe(false);
  });

  test("someone else's applied configuration is named, kept byte for byte, and applied again by off", () => {
    const theirs = theirConfig(s);
    const r = appOnYes(s);
    expect(r.out + r.err).toContain("Work Bedrock");
    expect(readFileSync(join(library(s), `${theirs.id}.json`), "utf8")).toBe(theirs.text);
    expect(meta(s)!.entries.map((e) => e.id)).toContain(theirs.id);
    expect(meta(s)!.appliedId).not.toBe(theirs.id);
    expect(s.cli(["desktop", "off"]).code).toBe(0);
    expect(readFileSync(join(library(s), `${theirs.id}.json`), "utf8")).toBe(theirs.text);
    expect(readFileSync(join(library(s), "_meta.json"), "utf8")).toBe(theirs.meta);
    expect(Object.keys(libraryFiles(s)).sort()).toEqual([`${theirs.id}.json`, "_meta.json"].sort());
  });

  test("plain on after on --app takes splice's configuration out and is standard mode again", () => {
    const before = libraryFiles(s);
    appOnYes(s);
    turnOn(s, "--model", ASTRA_BIG);
    expect(libraryFiles(s)).toEqual(before);
    expect(deskJson(s).mode).toBe("standard");
    expect(existsSync(join(library(s), "_meta.json"))).toBe(true);
  });
});
