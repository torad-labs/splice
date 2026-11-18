// heads-deprecated: another provider's own command keeps working, and whoever starts a session through one is told on
// the launch itself that the command is deprecated. A launch of `claude` carries no such notice. What the notice
// tells the person to type, and what `splice status` and `splice doctor` say of the lane `claude` is on, are
// adds-says' since decision 9 (Nov 17, 2026): `claude` on its head is no longer deprecated.
// (docs/specs/door-default.md, decision 7.)

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { clientHead, recipe, slottedHead } from "../desktop/lib/desktop.ts";
import { Splice, freePort } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { ask, launch } from "../speed/lib/session.ts";
import { toDoor } from "./lib/claude.ts";

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
  test("the launch of another provider's command says the command is deprecated", async () => {
    expect(await notice("claude-astra")).toMatch(/deprecated/i);
  });

  test("the head still launches and answers", async () => {
    const l = await launch(s, "claude-astra");
    const before = astra.completions().length;
    expect((await ask(l, crypto.randomUUID(), "heads-deprecated")).status).toBe(200);
    expect(astra.completions().length).toBe(before + 1);
  });

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
