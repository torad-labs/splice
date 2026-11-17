// door-claude-lane: `splice claude door` puts the terminal's `claude` on the door. Its launch points Claude Code at
// the door with a key of the terminal's own, over the person's own Claude folder, where splice changes nothing of theirs. A model's
// selector runs it on its head, a fresh session runs the model the lane says it starts on, and Desktop's switch and
// key leave the terminal as it was. (docs/specs/door-default.md, decisions 1 to 4.)

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { join } from "node:path";
import { doorTurn, readSettings, rotateKey, rows, slottedHead, turnOn, writeSettings } from "../desktop/lib/desktop.ts";
import { Splice, freePort, until } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { DOOR_VARS, ELSEWHERE, SPLICE_LOGIN, ask, lane, session, toDoor, under } from "./lib/claude.ts";

// No test here is a claim about speed: a busy machine must not fail one on bun's default five seconds.
setDefaultTimeout(120_000);

const ASTRA_BIG = "claude-astra--astra-large";
const BOREALIS_BIG = "claude-borealis--borealis-large";
const BOREALIS_SMALL = "claude-borealis--borealis-small";

/** What a person has in their settings file. */
const MINE = { env: { EDITOR: "vim" }, permissions: { allow: ["Bash(ls:*)"] }, model: "opus" };

let astra: Upstream;
let borealis: Upstream;
let s: Splice;
let said = "";

beforeAll(async () => {
  astra = new Upstream("astra", "astra-large").start();
  borealis = new Upstream("borealis", "borealis-large").start();
  s = await Splice.boot(
    slottedHead({ key: "astra", command: "claude-astra", port: await freePort(), url: astra.url, big: "astra-large", small: "astra-small" }) +
      slottedHead({ key: "borealis", command: "claude-borealis", port: await freePort(), url: borealis.url, big: "borealis-large", small: "borealis-small" }),
  );
  writeSettings(s, MINE);
  said = toDoor(s);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  astra?.stop();
  borealis?.stop();
});

describe("door-claude-lane", () => {
  test("splice claude door puts claude on the door, and splice claude says so", () => {
    expect(said).toMatch(/door/i);
    const l = lane(s);
    expect(l.lane).toBe("door");
    expect(l.door ?? "").toMatch(/^http:\/\/127\.0\.0\.1:\d+\//);
    // with no account signed in and no --model, a session starts on the pinned model of the first head in the door
    expect(l.default_model).toBe(ASTRA_BIG);
    const text = s.cli(["claude"]);
    expect(text.code).toBe(0);
    expect(text.out).toMatch(/door/i);
    expect(text.out).toContain(ASTRA_BIG);
  });

  test("a launch of claude points Claude Code at the door and at nothing else", async () => {
    const x = await session(s);
    expect(x.sets.CLAUDE_CODE_USE_MANTLE).toBe("1");
    expect(x.url).toBe(lane(s).door!);
    expect(x.key.length).toBeGreaterThan(20);
    expect(Number(x.sets.CLAUDE_CODE_MAX_CONTEXT_TOKENS)).toBeGreaterThan(0);
    expect(x.sets.CLAUDE_CODE_AUTO_MODE_SERVER).toBe("0");
    // the session shows the model it runs
    expect(x.sets.ANTHROPIC_MODEL).toBe(ASTRA_BIG);
    for (const name of ELSEWHERE) expect([name, name in x.sets, x.recipe.unset.includes(name)]).toEqual([name, false, true]);
    for (const name of DOOR_VARS) expect([name, x.recipe.unset.includes(name)]).toEqual([name, false]);
  });

  test("the session runs over the person's own Claude folder, and splice changes nothing of theirs in it", async () => {
    const x = await session(s);
    expect(x.sets.CLAUDE_CONFIG_DIR).toBeUndefined();
    expect((await ask(x, { model: ASTRA_BIG })).status).toBe(200);
    expect(readSettings(s)).toEqual(MINE);
    // the one file splice may lay there is its own /login (door-login)
    expect(under(join(s.home, ".claude")).filter((f) => f !== SPLICE_LOGIN)).toEqual(["settings.json"]);
  });

  test("a model's selector runs it on its head, through the one address", async () => {
    const x = await session(s);
    const a = await ask(x, { model: ASTRA_BIG });
    expect(a.status).toBe(200);
    expect(a.reply).toBe(astra.reply());
    const b = await ask(x, { model: BOREALIS_SMALL });
    expect(b.status).toBe(200);
    expect(b.reply).toBe(borealis.reply());
  });

  test("a session that has picked nothing runs the model the lane says it starts on", async () => {
    const x = await session(s);
    // Anthropic's own Opus, as a session with no pick asks for it
    const t = await ask(x);
    expect(t.status).toBe(200);
    expect(t.reply).toBe(astra.reply());
  });

  test("a model that is no selector and none of Anthropic's is refused by name, with nothing sent on", async () => {
    const x = await session(s);
    const before = astra.completions().length + borealis.completions().length;
    const t = await ask(x, { model: "claude-nobody--nothing" });
    expect(t.status).toBe(400);
    expect(t.error?.type).toBe("invalid_request_error");
    expect(t.error?.message).toContain("claude-nobody--nothing");
    expect(astra.completions().length + borealis.completions().length).toBe(before);
  });

  test("a request without the terminal's key is refused, with nothing sent on", async () => {
    const x = await session(s);
    const before = astra.completions().length;
    for (const key of [undefined, "splice-desktop-0123456789abcdef0123456789abcdef"]) {
      const t = await doorTurn(x.url, { key, model: ASTRA_BIG, session: crypto.randomUUID() });
      expect([401, 403]).toContain(t.status);
      expect(t.error).not.toBeNull();
    }
    expect(astra.completions().length).toBe(before);
  });

  test("a turn through the terminal's key is recorded as a door turn of the head that ran it", async () => {
    const x = await session(s);
    const id = crypto.randomUUID();
    expect((await ask(x, { model: BOREALIS_BIG, session: id })).status).toBe(200);
    let found = await rows(s, "borealis", id);
    await until("the head's row of the session", async () => (found = await rows(s, "borealis", id)).length > 0);
    expect(found[0]!.door ?? null).not.toBeNull();
  });

  test("--model names the model a session starts on, it is kept, and one the door does not serve is refused", async () => {
    toDoor(s, "--model", BOREALIS_SMALL);
    expect(lane(s).default_model).toBe(BOREALIS_SMALL);
    toDoor(s);
    expect(lane(s).default_model).toBe(BOREALIS_SMALL);
    const x = await session(s);
    expect(x.sets.ANTHROPIC_MODEL).toBe(BOREALIS_SMALL);
    const t = await ask(x);
    expect(t.status).toBe(200);
    expect(t.reply).toBe(borealis.reply());

    const r = s.cli(["claude", "door", "--model", "claude-nobody--nothing"]);
    expect(r.code).not.toBe(0);
    expect(r.out + r.err).toContain("claude-nobody--nothing");
    expect(lane(s).default_model).toBe(BOREALIS_SMALL);
    toDoor(s, "--model", ASTRA_BIG);
    expect(lane(s).default_model).toBe(ASTRA_BIG);
  });

  test("Desktop's switch and Desktop's key leave the terminal as it was", async () => {
    const x = await session(s);
    const d = turnOn(s);
    expect(d.key).not.toBe(x.key);
    const rotated = rotateKey(s);
    expect(rotated.key).not.toBe(d.key);
    expect((await session(s)).key).toBe(x.key);
    expect((await ask(x, { model: ASTRA_BIG })).status).toBe(200);

    expect(s.cli(["desktop", "off"]).code).toBe(0);
    expect((await doorTurn(rotated.url, { key: rotated.key, model: ASTRA_BIG, session: crypto.randomUUID() })).status).toBe(403);
    const t = await ask(x, { model: ASTRA_BIG });
    expect(t.status).toBe(200);
    expect(t.reply).toBe(astra.reply());
    expect(lane(s).lane).toBe("door");
  });

  test("the lane, its address and its key are the same after a restart", async () => {
    const x = await session(s);
    const before = lane(s);
    await s.restart();
    const after = lane(s);
    expect(after.lane).toBe("door");
    expect(after.door).toBe(before.door!);
    expect(after.default_model).toBe(before.default_model!);
    expect((await session(s)).key).toBe(x.key);
    expect((await ask(x, { model: ASTRA_BIG })).status).toBe(200);
    expect(readSettings(s)).toEqual(MINE);
  }, 120_000);
});
