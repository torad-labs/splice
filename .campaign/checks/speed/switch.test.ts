// speed-switch: /fast and /ultrafast switch the speed of the session they are typed in, and no other. Run bare, a
// command turns its tier on, or back to standard when it is already on, as Codex's do; `on` and `off` set it whatever
// it was. The two replace each other, the setting outlives a daemon restart, and a tier the head's model doesn't offer
// is refused, naming what it does offer.

import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { Splice, freePort } from "../server/lib/harness.ts";
import { CodexBackend, FAST, FAST_ONLY, ULTRAFAST, gptEnv, gptHead } from "./lib/codex.ts";
import { ask, launch, prompt, type Launched } from "./lib/session.ts";

let backend: CodexBackend;
let s: Splice;
let astra: Launched;
let terra: Launched;

beforeAll(async () => {
  backend = CodexBackend.start([
    { slug: "gpt-6-astra", tiers: [FAST, ULTRAFAST] },
    { slug: "gpt-6.1-terra", tiers: [FAST_ONLY] },
  ]);
  const logins = `/tmp/claude-1000/speed-switch-${process.pid}`;
  const toml =
    gptHead({ key: "astra", command: "claude-astra", port: await freePort(), backend: backend.url, model: "gpt-6-astra", dir: logins }) +
    gptHead({ key: "terra", command: "claude-terra", port: await freePort(), backend: backend.url, model: "gpt-6.1-terra", dir: logins });
  s = await Splice.boot(toml, gptEnv(backend.url));
  astra = await launch(s, "claude-astra");
  terra = await launch(s, "claude-terra");
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  backend?.stop();
});

let n = 0;
/** The tier the backend was asked for on one new turn of `session`, or null for none. */
async function tierOf(l: Launched, session: string): Promise<string | null> {
  const marker = `SPEED-SWITCH-${++n}`;
  const r = await ask(l, session, marker);
  expect(r.status).toBe(200);
  const seen = backend.turnsWith(marker);
  expect(seen.length).toBe(1);
  const tier = seen[0]!.body?.service_tier;
  return typeof tier === "string" && tier !== "default" && tier !== "auto" ? tier : null;
}

describe("speed-switch", () => {
  test("/fast turns Fast on for its own session and no other, saying what it costs", async () => {
    const one = crypto.randomUUID();
    const other = crypto.randomUUID();
    const said = prompt(astra, one, "/fast");
    expect(said.blocked).toBe(true);
    expect(said.reason).toContain("Fast");
    expect(said.reason).toContain(FAST.description);
    expect(said.reason).toContain("2.5x");
    expect(await tierOf(astra, one)).toBe("priority");
    expect(await tierOf(astra, other)).toBeNull();
  });

  test("/fast again goes back to standard speed", async () => {
    const one = crypto.randomUUID();
    prompt(astra, one, "/fast");
    expect(await tierOf(astra, one)).toBe("priority");
    const said = prompt(astra, one, "/fast");
    expect(said.blocked).toBe(true);
    expect(said.reason.toLowerCase()).toContain("standard");
    expect(await tierOf(astra, one)).toBeNull();
  });

  test("on and off set the speed whatever it was", async () => {
    const one = crypto.randomUUID();
    prompt(astra, one, "/fast on");
    prompt(astra, one, "/fast on");
    expect(await tierOf(astra, one)).toBe("priority");
    prompt(astra, one, "/fast off");
    expect(await tierOf(astra, one)).toBeNull();
    prompt(astra, one, "/fast off");
    expect(await tierOf(astra, one)).toBeNull();
    prompt(astra, one, "/ultrafast on");
    expect(await tierOf(astra, one)).toBe("ultrafast");
    prompt(astra, one, "/ultrafast off");
    expect(await tierOf(astra, one)).toBeNull();
  });

  test("/ultrafast and /fast replace each other", async () => {
    const one = crypto.randomUUID();
    const said = prompt(astra, one, "/ultrafast");
    expect(said.blocked).toBe(true);
    expect(said.reason).toContain("Ultrafast");
    expect(said.reason).toContain(ULTRAFAST.description);
    expect(said.reason).toContain("8x");
    expect(await tierOf(astra, one)).toBe("ultrafast");
    prompt(astra, one, "/fast");
    expect(await tierOf(astra, one)).toBe("priority");
    prompt(astra, one, "/ultrafast");
    expect(await tierOf(astra, one)).toBe("ultrafast");
  });

  test("/ultrafast where the model offers only Fast is refused, naming what it offers", async () => {
    const one = crypto.randomUUID();
    const said = prompt(terra, one, "/ultrafast");
    expect(said.blocked).toBe(true);
    expect(said.reason).toContain("gpt-6.1-terra");
    expect(said.reason).toContain("Fast");
    expect(await tierOf(terra, one)).toBeNull();
    prompt(terra, one, "/fast");
    expect(await tierOf(terra, one)).toBe("priority");
  });

  test("a session's speed outlives a daemon restart", async () => {
    const fast = crypto.randomUUID();
    const ultra = crypto.randomUUID();
    prompt(astra, fast, "/fast");
    prompt(astra, ultra, "/ultrafast");
    await s.restart();
    expect(await tierOf(astra, fast)).toBe("priority");
    expect(await tierOf(astra, ultra)).toBe("ultrafast");
    expect(await tierOf(astra, crypto.randomUUID())).toBeNull();
  }, 300_000);
});
