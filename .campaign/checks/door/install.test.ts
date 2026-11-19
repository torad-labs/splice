// door-install-default: with no choice made, `claude` is on the door on a machine whose splice.toml has no head for
// it, and on its head on a machine that has one. A choice is kept across restarts. `splice claude head` goes back to
// the head, retires the terminal's key and says heads are going away, and refuses when there is no head to go back to.
// (docs/specs/door-default.md, decisions 1, 3 and 8.)

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { clientHead, slottedHead } from "../desktop/lib/desktop.ts";
import { Splice, freePort } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { ask, lane, onDoor, session, toDoor } from "./lib/claude.ts";

// No test here is a claim about speed: a busy machine must not fail one on bun's default five seconds.
setDefaultTimeout(120_000);

const ASTRA_BIG = "claude-astra--astra-large";

let astra: Upstream;
let anthropic: Upstream;
let s: Splice;
let astraHead = "";
let claudeHead = "";

beforeAll(async () => {
  astra = new Upstream("astra", "astra-large").start();
  anthropic = new Upstream("anthropic", "claude-opus-5-5").start();
  astraHead = slottedHead({ key: "astra", command: "claude-astra", port: await freePort(), url: astra.url, big: "astra-large", small: "astra-small" });
  claudeHead = clientHead({ key: "claude", command: "claude", port: await freePort(), url: anthropic.url });
  s = await Splice.boot(astraHead);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  astra?.stop();
  anthropic?.stop();
});

describe("door-install-default", () => {
  test("on a machine with no head for claude, claude is on the door with no command run", async () => {
    expect(lane(s).lane).toBe("door");
    const x = await session(s);
    expect(onDoor(x, lane(s).door!)).toBe(true);
    const t = await ask(x, { model: ASTRA_BIG });
    expect(t.status).toBe(200);
    expect(t.reply).toBe(astra.reply());
  });

  test("splice claude head refuses when there is no head to go back to, and changes nothing", () => {
    const r = s.cli(["claude", "head"]);
    expect(r.code).not.toBe(0);
    expect(r.out + r.err).toMatch(/head/i);
    expect(lane(s).lane).toBe("door");
  });

  test("on a machine that has a head for claude and has made no choice, claude stays on its head", async () => {
    s.writeConfig(astraHead + claudeHead);
    await s.restart();
    expect(lane(s).lane).toBe("head");
    const text = s.cli(["claude"]);
    expect(text.code).toBe(0);
    expect(text.out).toContain("splice claude door");
  }, 120_000);

  test("splice claude door moves it, and the choice is kept across a restart", async () => {
    toDoor(s);
    expect(lane(s).lane).toBe("door");
    const x = await session(s);
    await s.restart();
    expect(lane(s).lane).toBe("door");
    expect((await session(s)).key).toBe(x.key);
    expect((await ask(x, { model: ASTRA_BIG })).status).toBe(200);
  }, 120_000);

  test("splice claude head goes back, says heads are going away, and retires the terminal's key", async () => {
    const x = await session(s);
    const r = s.cli(["claude", "head"]);
    expect(r.code).toBe(0);
    expect(r.out + r.err).toMatch(/deprecated/i);
    expect(lane(s).lane).toBe("head");

    const before = astra.completions().length;
    const t = await ask(x, { model: ASTRA_BIG });
    expect([401, 403]).toContain(t.status);
    expect(t.error).not.toBeNull();
    expect(astra.completions().length).toBe(before);

    await s.restart();
    expect(lane(s).lane).toBe("head");
  }, 120_000);
});
