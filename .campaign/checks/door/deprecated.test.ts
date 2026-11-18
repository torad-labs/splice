// heads-deprecated: a head's command keeps working, and whoever starts a session through one is told it is going
// away and what replaces it: on the launch itself, and in `splice status` and `splice doctor` while `claude` is on its
// head. A `claude` on the door carries no such notice. (docs/specs/door-default.md, decision 7.)

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { clientHead, recipe, slottedHead } from "../desktop/lib/desktop.ts";
import { Splice, freePort } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { ask, launch } from "../speed/lib/session.ts";
import { lane, toDoor } from "./lib/claude.ts";

// No test here is a claim about speed: a busy machine must not fail one on bun's default five seconds.
setDefaultTimeout(120_000);

const MOVE = "splice claude door";

let astra: Upstream;
let anthropic: Upstream;
let s: Splice;

beforeAll(async () => {
  astra = new Upstream("astra", "astra-large").start();
  anthropic = new Upstream("anthropic", "claude-opus-5-5").start();
  s = await Splice.boot(
    slottedHead({ key: "astra", command: "claude-astra", port: await freePort(), url: astra.url, big: "astra-large", small: "astra-small" }) +
      clientHead({ key: "claude", command: "claude", port: await freePort(), url: anthropic.url }),
  );
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  astra?.stop();
  anthropic?.stop();
});

/** The notice a launch of `command` carries for the shim to print, or "" when it carries none. */
async function notice(command: string): Promise<string> {
  return String((await recipe(s, command) as unknown as { warning?: string }).warning ?? "");
}

describe("heads-deprecated", () => {
  test("the launch of a head's command says head commands are deprecated and how to move to the door", async () => {
    const said = await notice("claude-astra");
    expect(said).toMatch(/deprecated/i);
    expect(said).toMatch(/door/i);
    expect(said).toContain(MOVE);
  });

  test("the head still launches and answers", async () => {
    const l = await launch(s, "claude-astra");
    const before = astra.completions().length;
    expect((await ask(l, crypto.randomUUID(), "heads-deprecated")).status).toBe(200);
    expect(astra.completions().length).toBe(before + 1);
  });

  test("while claude is on its head, splice status and splice doctor say the lane is deprecated and how to move", () => {
    expect(lane(s).lane).toBe("head");
    for (const verb of ["status", "doctor"]) {
      const r = s.cli([verb]);
      const text = r.out + r.err;
      expect([verb, /deprecated/i.test(text)]).toEqual([verb, true]);
      expect([verb, text.includes(MOVE)]).toEqual([verb, true]);
    }
  }, 240_000);

  test("on the door, splice status says claude is on the door, and its launch carries no such notice", async () => {
    toDoor(s);
    const r = s.cli(["status"]);
    expect(r.out + r.err).toMatch(/claude\b.*\bdoor/i);
    expect(r.out + r.err).not.toContain(MOVE);
    expect(await notice("claude")).not.toMatch(/deprecated/i);
    // a head's own command still says it
    expect(await notice("claude-astra")).toMatch(/deprecated/i);
  }, 120_000);
});
