// adds-says: what splice tells a person once `claude` on its head carries the other accounts' models. The launch of
// another provider's own command says the command is deprecated and what to type in `claude` to run its model, and a
// launch of `claude` says nothing. `splice status` and `splice doctor` say which lane `claude` is on and call neither
// deprecated. `splice claude door` says what moving costs a person on a Claude plan, and `splice claude head` brings
// `claude` back. (docs/specs/door-default.md, decisions 7 and 9.)

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { recipe, slottedHead } from "../desktop/lib/desktop.ts";
import { Provider } from "../harness/lib/capability.ts";
import { Splice, freePort } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { claudeHead } from "./lib/adds.ts";
import { lane, toDoor } from "./lib/claude.ts";

// No test here is a claim about speed: a busy machine must not fail one on bun's default five seconds.
setDefaultTimeout(120_000);

const ASTRA_BIG = "claude-astra--astra-large";
const DOOR_FIRST = "splice claude door";
const BACK = "splice claude head";

let anthropic: Provider;
let astra: Upstream;
let alone: Upstream;
/** A machine whose `claude` is a head on the person's own Claude login. */
let s: Splice;
/** A machine with no head keyed `claude`, and `claude` not on the door. */
let bare: Splice;

beforeAll(async () => {
  anthropic = new Provider("anthropic").start();
  astra = new Upstream("astra", "astra-large").start();
  alone = new Upstream("astra", "astra-large").start();
  s = await Splice.boot(
    claudeHead({ port: await freePort(), url: anthropic.url }) +
      slottedHead({ key: "astra", command: "claude-astra", port: await freePort(), url: astra.url, big: "astra-large", small: "astra-small" }),
  );
  bare = await Splice.boot(
    slottedHead({ key: "astra", command: "claude-astra", port: await freePort(), url: alone.url, big: "astra-large", small: "astra-small" }),
  );
}, 600_000);

afterAll(async () => {
  await s?.dispose();
  await bare?.dispose();
  anthropic?.stop();
  astra?.stop();
  alone?.stop();
});

/** The notice a launch of `command` carries for the shim to print, or "" when it carries none. */
async function notice(on: Splice, command: string): Promise<string> {
  return String(((await recipe(on, command)) as unknown as { warning?: string }).warning ?? "");
}

describe("adds-says", () => {
  test("the launch of another provider's command says it is deprecated and what to type in claude for its model", async () => {
    const said = await notice(s, "claude-astra");
    expect(said).toMatch(/deprecated/i);
    expect(said).toContain("/model");
    expect(said).toContain(ASTRA_BIG);
    // claude already runs this head's models here: there is no move to make first
    expect(said).not.toContain(DOOR_FIRST);
  });

  test("with no claude head and claude not on the door, the same notice says splice claude door comes first", async () => {
    const said = await notice(bare, "claude-astra");
    expect(said).toMatch(/deprecated/i);
    expect(said).toContain("/model");
    expect(said).toContain(ASTRA_BIG);
    expect(said).toContain(DOOR_FIRST);
  });

  test("a launch of claude on its head carries no notice", async () => {
    expect(lane(s).lane).toBe("head");
    expect(await notice(s, "claude")).toBe("");
  });

  test("splice status and splice doctor say claude is on its head, and neither says deprecated", () => {
    for (const verb of ["status", "doctor"]) {
      const r = s.cli([verb]);
      const text = r.out + r.err;
      expect([verb, /\bclaude\b[^\n]*\bhead\b/i.test(text)]).toEqual([verb, true]);
      expect([verb, /deprecated/i.test(text)]).toEqual([verb, false]);
    }
  }, 240_000);

  test("splice claude says claude is on its head and runs the other accounts' models there", () => {
    const r = s.cli(["claude"]);
    expect(r.code).toBe(0);
    expect(r.out).toMatch(/\bhead\b/i);
    expect(r.out).toMatch(/model/i);
    expect(r.out + r.err).not.toMatch(/deprecated/i);
  });

  test("splice claude door moves claude and says Claude's models stop running in it until splice claude head", async () => {
    const said = toDoor(s);
    expect(lane(s).lane).toBe("door");
    expect(said).toMatch(/Claude/);
    expect(said).toContain(BACK);
    // on the door lane too, a launch of claude carries no notice
    expect(await notice(s, "claude")).toBe("");
  });

  test("splice claude head brings claude back to its head, and says nothing of heads going away", () => {
    const r = s.cli(["claude", "head"]);
    expect(r.code).toBe(0);
    expect(lane(s).lane).toBe("head");
    expect(r.out + r.err).not.toMatch(/deprecated|going away/i);
  });

  test("with no claude head, splice claude head refuses and changes nothing", () => {
    const before = lane(bare).lane;
    const r = bare.cli(["claude", "head"]);
    expect(r.code).not.toBe(0);
    expect(lane(bare).lane).toBe(before);
  });
});
