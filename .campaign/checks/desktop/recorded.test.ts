// desktop-recorded: a turn that came through the door is recorded as its head's turn and says which door it came
// through, and `splice desktop` names the last one, so a person can tell the Code tab is running on splice.

import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { Splice, freePort, until } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { ask, launch } from "../speed/lib/session.ts";
import { type Door, type PerfRow, desktop, doorTurn, rows, slottedHead, turnOn } from "./lib/desktop.ts";

let astra: Upstream;
let borealis: Upstream;
let s: Splice;
let d: Door;

beforeAll(async () => {
  astra = new Upstream("astra", "astra-large").start();
  borealis = new Upstream("borealis", "borealis-large").start();
  s = await Splice.boot(
    slottedHead({ key: "astra", command: "claude-astra", port: await freePort(), url: astra.url, big: "astra-large", small: "astra-small" }) +
      slottedHead({ key: "borealis", command: "claude-borealis", port: await freePort(), url: borealis.url, big: "borealis-large", small: "borealis-small" }),
  );
  d = turnOn(s, "--model", "claude-astra--astra-large");
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  astra?.stop();
  borealis?.stop();
});

/** The one row `session` has on head `key`, once it is recorded. */
async function row(key: string, session: string): Promise<PerfRow> {
  let found: PerfRow[] = [];
  await until(`a row of the session on ${key}`, async () => (found = await rows(s, key, session)).length > 0);
  expect(found.length).toBe(1);
  return found[0]!;
}

describe("desktop-recorded", () => {
  test("before any turn has come through the door, splice desktop names none", () => {
    expect(desktop(s).last_turn).toBeNull();
  });

  test("a turn through the door is its head's row, with the model that ran, and says it came through the door", async () => {
    const session = crypto.randomUUID();
    expect((await doorTurn(d.url, { key: d.key, model: "claude-borealis--borealis-small", session })).status).toBe(200);
    const r = await row("borealis", session);
    expect(r.door).toBe("desktop");
    expect(String(r.model)).toContain("borealis-small");
    expect(await rows(s, "astra", session)).toEqual([]);
  });

  test("a turn of a session launched on the head carries no door", async () => {
    const l = await launch(s, "claude-astra");
    const session = crypto.randomUUID();
    expect((await ask(l, session, "from the terminal")).status).toBe(200);
    const r = await row("astra", session);
    expect(r.door ?? null).toBeNull();
  });

  test("splice desktop names the last turn through the door: the session, the head, the model asked for and the one that ran", async () => {
    const session = crypto.randomUUID();
    const asked = "anthropic.claude-haiku-5-5";
    const sent = Date.now();
    expect((await doorTurn(d.url, { key: d.key, model: asked, session })).status).toBe(200);
    await row("astra", session);
    const last = desktop(s).last_turn;
    expect(last?.session_id).toBe(session);
    expect(last?.head).toBe("astra");
    expect(last?.model_asked).toBe(asked);
    expect(String(last?.model)).toContain("astra-small");
    const at = Date.parse(String(last?.at));
    expect(at).toBeGreaterThanOrEqual(sent - 2000);
    expect(at).toBeLessThanOrEqual(Date.now() + 2000);
  });
});
