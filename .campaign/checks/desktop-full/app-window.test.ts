// df-app-window: in app mode Desktop gives its Code tab a 200000-token window, and 1M for a model picked as its 1M
// variant. The door reports each turn's counts and each count_tokens answer as the same share of that window that
// they are of the model's own, so a session compacts at its model's own limit. A Code tab session in standard mode
// keeps the window `splice desktop on` told Desktop's environment.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { Splice, freePort, mockHead } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { type Door, doorTurn, reported, slottedHead, turnOn } from "../desktop/lib/desktop.ts";
import { appOnYes, base, customHeaders, gatewayTurn, runHelper } from "./lib/full.ts";

setDefaultTimeout(120_000);

const ASTRA = "claude-astra--astra-large";
const ORION = "claude-orion--gpt-orion";
const STORE_WINDOW = 131_072;

let astra: Upstream;
let orion: Upstream;
let s: Splice;
let d: Door;
let key: string;

beforeAll(async () => {
  astra = new Upstream("astra", "astra-large").start();
  orion = new Upstream("orion", "gpt-orion").start();
  s = await Splice.boot(
    slottedHead({ key: "astra", command: "claude-astra", port: await freePort(), url: astra.url, big: "astra-large", small: "astra-small", window: STORE_WINDOW }) +
      mockHead({ key: "orion", command: "claude-orion", port: await freePort(), url: orion.url, model: "gpt-orion", window: 1_048_576 }),
  );
  d = turnOn(s, "--model", ASTRA);
  appOnYes(s);
  key = runHelper(s).out;
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  astra?.stop();
  orion?.stop();
});

/** What a standard-mode turn on [model] reports, against the window Desktop's environment was told. */
async function storeReported(model: string): Promise<number> {
  const t = await doorTurn(d.url, { key: d.key, model, session: crypto.randomUUID() });
  expect(t.status).toBe(200);
  return reported(t);
}

/** What an app-mode turn on [model] reports. */
async function appReported(model: string, headers: Record<string, string> = {}): Promise<number> {
  const t = await gatewayTurn(s, { key, model, session: crypto.randomUUID(), headers });
  expect([model, t.status]).toEqual([model, 200]);
  return reported(t);
}

const near = (got: number, want: number) => expect(Math.abs(got - want)).toBeLessThanOrEqual(Math.max(2, want * 0.002));

describe("df-app-window", () => {
  test("standard mode keeps the window on told Desktop's environment", async () => {
    expect(d.window).toBe(STORE_WINDOW);
    expect(await storeReported(ASTRA)).toBeGreaterThan(0);
  });

  test("an app-mode turn is reported as a share of 200000", async () => {
    for (const model of [ASTRA, ORION]) near(await appReported(model), ((await storeReported(model)) * 200_000) / STORE_WINDOW);
  });

  test("a 1M variant, named with [1m] or asked for by Claude Code's 1M beta, is reported as a share of 1000000 and runs the model", async () => {
    const store = await storeReported(ORION);
    const before = orion.completions().length;
    near(await appReported(`${ORION}[1m]`), (store * 1_000_000) / STORE_WINDOW);
    expect(orion.completions()[before]?.body?.model).toBe("gpt-orion");
    near(await appReported(ORION, { "anthropic-beta": "claude-code-20250219,context-1m-2025-08-07" }), (store * 1_000_000) / STORE_WINDOW);
  });

  test("count_tokens in app mode is the same share of 200000", async () => {
    const count = async (headers: Record<string, string>, url: string, auth: string) => {
      const r = await fetch(`${url}/v1/messages/count_tokens?beta=true`, {
        method: "POST",
        headers: { "content-type": "application/json", "anthropic-version": "2023-06-01", authorization: `Bearer ${auth}`, ...headers },
        body: JSON.stringify({ model: ASTRA, messages: [{ role: "user", content: "Count these words for the window check, please." }] }),
      });
      expect(r.status).toBe(200);
      return Number(((await r.json()) as { input_tokens?: number }).input_tokens);
    };
    const store = await count({}, d.url, d.key);
    near(await count(customHeaders(s), base(s), key), (store * 200_000) / STORE_WINDOW);
  });
});
