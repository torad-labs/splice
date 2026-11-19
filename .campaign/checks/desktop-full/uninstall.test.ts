// df-uninstall: `splice uninstall` of the whole of splice puts Claude Desktop back, as it already takes away the units
// and logins splice laid. In app mode it takes splice's configuration out of Desktop's library as `splice desktop off`
// does; in standard mode, whose store splice can't write, it names the variables to remove. Uninstalling one head
// leaves Desktop alone.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { Splice, freePort } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { slottedHead, turnOn } from "../desktop/lib/desktop.ts";
import { appOnYes, applied, library, libraryFiles, theirConfig } from "./lib/full.ts";

setDefaultTimeout(180_000);

const ASTRA_BIG = "claude-astra--astra-large";
const STORE_NAMES = [
  "CLAUDE_CODE_USE_MANTLE",
  "ANTHROPIC_BEDROCK_MANTLE_BASE_URL",
  "AWS_BEARER_TOKEN_BEDROCK",
  "CLAUDE_CODE_MAX_CONTEXT_TOKENS",
  "CLAUDE_CODE_AUTO_MODE_SERVER",
];

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

describe("df-uninstall", () => {
  test("uninstalling one head leaves splice's configuration applied in Desktop", () => {
    appOnYes(s);
    const before = libraryFiles(s);
    s.cli(["uninstall", "claude-astra"]);
    expect(libraryFiles(s)).toEqual(before);
    expect(applied(s)?.inferenceProvider).toBe("gateway");
  });

  test("uninstalling splice in app mode puts Desktop's library back as it was and says to reopen Desktop", () => {
    s.cli(["desktop", "off"]);
    const theirs = theirConfig(s);
    appOnYes(s);
    expect(applied(s)?.inferenceProvider).toBe("gateway");
    const r = s.cli(["uninstall"]);
    const said = r.out + r.err;
    expect(said).toMatch(/Claude Desktop/);
    expect(said).toMatch(/quit and reopen/i);
    expect(said).toMatch(/claude\.ai/);
    expect(readFileSync(join(library(s), "_meta.json"), "utf8")).toBe(theirs.meta);
    expect(readFileSync(join(library(s), `${theirs.id}.json`), "utf8")).toBe(theirs.text);
    expect(Object.keys(libraryFiles(s)).sort()).toEqual([`${theirs.id}.json`, "_meta.json"].sort());
  });

  test("uninstalling splice in standard mode names each variable to remove from Desktop's environment store", () => {
    turnOn(s, "--model", ASTRA_BIG);
    const r = s.cli(["uninstall"]);
    const said = r.out + r.err;
    expect(said).toContain("Environment variables");
    for (const name of STORE_NAMES) expect(said).toContain(name);
  });
});
