// gnh-status: `splice status` lists each provider that no head names and that the Splice gateway serves, with how many
// models it serves and the selector of its first; `splice doctor` names a provider the gateway can list no model of,
// and says how to give it one. A splice.toml whose providers the gateway serves is set up, with no head at all: neither
// command tells its owner to connect a plan.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { Splice, freePort, mockHead } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { PEBBLE, SLATE, provider, selector } from "./lib/providers.ts";

setDefaultTimeout(120_000);

let pebble: Upstream;
let slate: Upstream;
let s: Splice;
let headless: Splice;

/** A line of `text` with ANSI colour taken out and runs of spaces made one. */
function lines(text: string): string[] {
  return text
    .replace(/\u001b\[[0-9;]*m/g, "")
    .split("\n")
    .map((l) => l.replace(/\s+/g, " ").trim());
}

beforeAll(async () => {
  pebble = new Upstream("pebble", PEBBLE).start();
  slate = new Upstream("slate", SLATE).start();
  const pebbleToml = provider({ key: "pebble", url: pebble.url, model: PEBBLE, window: 65536, env: "PEBBLE_KEY" });
  const toml =
    pebbleToml +
    // a provider that declares no model and lets its endpoint add none: nothing to list
    `
[providers.hollow]
dialect = "openai-chat"
base_url = "https://hollow.example.invalid/v1"
auth = { kind = "api-key", env = "HOLLOW_KEY" }
discovery = { exclude = ["*"] }
` +
    // slate has a head of its own, and is in the head table already
    mockHead({ key: "slate", command: "claude-slate", port: await freePort(), url: slate.url, model: SLATE });
  s = await Splice.boot(toml, { PEBBLE_KEY: "sk-pebble-check", HOLLOW_KEY: "sk-hollow-check" });
  // the same provider and no head at all
  headless = await Splice.boot(pebbleToml, { PEBBLE_KEY: "sk-pebble-check" });
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  await headless?.dispose();
  pebble?.stop();
  slate?.stop();
});

describe("gnh-status", () => {
  test("splice status lists the provider with no head the gateway serves, its count and first selector", () => {
    const r = s.cli(["status"]);
    const out = lines(`${r.out}\n${r.err}`);
    const row = out.find((l) => l.startsWith("gateway pebble "));
    expect(row, out.join("\n")).toBeDefined();
    expect(row).toContain("1 model");
    expect(row).toContain(selector("pebble", PEBBLE));
    // a provider a head names is in the head table, not listed again
    expect(out.some((l) => l.startsWith("gateway slate "))).toBe(false);
  });

  test("splice doctor names the provider the gateway can list no model of, and what to do", () => {
    const r = s.cli(["doctor"]);
    const text = `${r.out}\n${r.err}`;
    const line = lines(text).find((l) => l.includes("providers.hollow"));
    expect(line, text).toBeDefined();
    expect(line).toContain("[[providers.hollow.models]]");
  });

  test("with providers and no head, splice is set up: status lists the provider and neither command asks for a plan", () => {
    const status = headless.cli(["status"]);
    const said = lines(`${status.out}\n${status.err}`);
    expect(said.some((l) => l.startsWith("gateway pebble ")), said.join("\n")).toBe(true);
    const doctor = headless.cli(["doctor"]);
    for (const text of [`${status.out}\n${status.err}`, `${doctor.out}\n${doctor.err}`]) {
      expect(text).not.toContain("not set up yet");
    }
  });
});
