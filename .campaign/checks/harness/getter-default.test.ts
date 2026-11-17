// getter-limits, its default: the Context Getter's default time limit is the one the spec's table of what was seen
// gives, set from gathers measured on real work, and it is the limit the daemon applies to a Context Getter declared
// with no timeout_s. A gather as slow as the slowest measured on this repo (101 s on Haiku at its own level,
// November 17) finishes and is placed; a gatherer that never ends is ended at the table's limit, and the turn goes
// on. (docs/specs/harness.md, decision 9, Limits.)

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { ROOT, Splice, freePort, until } from "../server/lib/harness.ts";
import { type Launched, launch } from "../speed/lib/session.ts";
import { KEYS, blocks, opening, send } from "./lib/capability.ts";
import { Backend, Fake, alive, contextGetter, folder, labelledHead, register } from "./lib/gatherer.ts";

setDefaultTimeout(120_000);

/** The slowest gather measured on real work (docs/specs/harness.md, the plan's measurements): 101 s. */
const SLOWEST_MS = 101_000;

/** The default time limit the spec's table gives, in seconds, or null when no row gives one. */
function tableLimit(): number | null {
  const spec = readFileSync(join(ROOT, "docs/specs/harness.md"), "utf8");
  const table = spec.slice(spec.indexOf("## Seen with Claude Code"));
  const row = table
    .split("\n")
    .filter((l) => l.startsWith("|") && !/^\|\s*-/.test(l))
    .map((l) => l.split("|").map((c) => c.trim()))
    .find((cells) => /time limit/i.test(cells[1] ?? "") && /gather|context getter/i.test(cells[1] ?? ""));
  const said = (row?.[2] ?? "").match(/default[^.|]*?(\d+)\s*(s|seconds?|m|min|minutes?)\b/i) ?? (row?.[2] ?? "").match(/(\d+)\s*(s|seconds?|m|min|minutes?)\b/);
  if (!said) return null;
  return Number(said[1]) * (/^m/i.test(said[2]!) ? 60 : 1);
}

let up: Backend;
let fake: Fake;
let s: Splice;
let astra: Launched;

beforeAll(async () => {
  up = new Backend("the provider").start();
  fake = new Fake();
  const table = labelledHead({
    key: "astra",
    command: "claude-astra",
    port: await freePort(),
    url: up.url,
    large: { id: "astra-large", label: "astra large" },
    small: { id: "astra-small", label: "astra small" },
  });
  // switched on with every default of its own: no timeout_s
  s = await Splice.boot(table + contextGetter(["astra"]), { ...KEYS, PATH: fake.path });
  astra = await launch(s, "claude-astra");
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  up?.stop();
  fake?.dispose();
});

describe("getter-limits: the default time limit", () => {
  test("the spec's table gives the default, from gathers measured on real work, and it is no shorter than the slowest", () => {
    const limit = tableLimit();
    expect(limit, "a row of the table gives the Context Getter's default time limit in seconds or minutes").not.toBeNull();
    expect(limit! * 1000).toBeGreaterThan(SLOWEST_MS);
  });

  test("with no timeout_s, a gather as slow as the slowest measured is placed, and one that never ends is ended at the table's limit", async () => {
    const limit = tableLimit();
    expect(limit).not.toBeNull();

    // the slow gather and the endless one run side by side, so the check costs the longer of the two
    const slow = "Map the retry ladder for a dropped stream.";
    const endless = "Map where a drain counts a held turn.";
    fake.when(slow, { mode: "ok", delayMs: SLOWEST_MS + 4_000 });
    fake.when(endless, { mode: "hang" });

    const start = (typed: string) => {
      const session = crypto.randomUUID();
      register(astra.dir, session, folder(s, `d-${session.slice(0, 8)}`), typed);
      const mine = opening(typed);
      return { session, mine, sending: send(astra, session, [{ role: "user", content: mine }]) };
    };
    const a = start(slow);
    const b = start(endless);
    await until("both gatherers start", () => fake.of(slow).length === 1 && fake.of(endless).length === 1, 30_000);
    const began = Date.now();

    const sentA = await a.sending;
    expect(sentA.status).toBe(200);
    expect(sentA.body).toContain(up.reply());
    const placed = up.of(a.session);
    expect(placed.length).toBe(1);
    expect(blocks(placed[0]!.body.messages[0]).length, "the slow gather's result is in the first user message").toBe(a.mine.length + 1);

    const sentB = await b.sending;
    const tookMs = Date.now() - began;
    expect(sentB.status).toBe(200);
    expect(sentB.body).toContain(up.reply());
    // ended at the default, not before it and not long after
    expect(tookMs).toBeGreaterThan(limit! * 1000 - 5_000);
    expect(tookMs).toBeLessThan(limit! * 1000 + 30_000);
    await until("the endless gatherer is gone", () => !alive(fake.of(endless)[0]!), 15_000);
    // the turn went on as today: nothing placed
    const asked = up.of(b.session);
    expect(asked.length).toBe(1);
    expect(blocks(asked[0]!.body.messages[0]).length).toBe(b.mine.length);
  }, 840_000);
});
