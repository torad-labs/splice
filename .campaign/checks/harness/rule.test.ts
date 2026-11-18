// capability-rule: `when` says which requests call a capability, from facts splice already reads from a request: the
// mode Claude Code runs in, the thread, the model, and whether the turn is the thread's first. Each thread's first
// turn is decided once. Where two capabilities' rules name the same request, they run in the order splice.toml
// declares them, one at a time. (docs/specs/harness.md, decision 2.)

import { afterAll, afterEach, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { Splice, freePort } from "../server/lib/harness.ts";
import { type Launched, launch } from "../speed/lib/session.ts";
import { IDLE, KEYS, type Message, PRINT, Provider, type SendOptions, Worker, blocks, capability, first, head, opening, second, send, today } from "./lib/capability.ts";

// No test here is a claim about speed: a busy machine must not fail one on bun's default five seconds.
setDefaultTimeout(120_000);

const HEADS = ["astra", "cirrus", "draco", "eos", "fen", "borealis"] as const;
type Head = (typeof HEADS)[number];
const NAMES = ["anymode", "sub", "small", "every", "one", "two"] as const;
type Name = (typeof NAMES)[number];

let up: Provider;
let s: Splice;
const w = {} as Record<Name, Worker>;
const on = {} as Record<Head, Launched>;

beforeAll(async () => {
  up = new Provider("the provider").start();
  for (const name of NAMES) {
    w[name] = new Worker().start();
    w[name].text = (a) => `What ${name} found for ${a.body.session}.`;
  }
  const tables = await Promise.all(
    HEADS.map(async (key) => head({ key, command: `claude-${key}`, port: await freePort(), url: up.url, small: key === "draco" ? "draco-small" : undefined })),
  );
  s = await Splice.boot(
    tables.join("") +
      capability("anymode", { heads: ["astra"], url: w.anymode.url, when: '{ mode = "any" }' }) +
      capability("sub", { heads: ["cirrus"], url: w.sub.url, when: '{ thread = "subagent" }' }) +
      capability("small", { heads: ["draco"], url: w.small.url, when: '{ models = ["draco-small"] }', send: ["model"] }) +
      capability("every", { heads: ["eos"], url: w.every.url, when: '{ turn = "any" }', place: "none", may: "refuse" }) +
      capability("one", { heads: ["fen"], url: w.one.url }) +
      capability("two", { heads: ["fen"], url: w.two.url }),
    KEYS,
  );
  for (const key of HEADS) on[key] = await launch(s, `claude-${key}`);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  up?.stop();
  for (const name of NAMES) w[name]?.stop();
});

afterEach(() => {
  for (const name of NAMES) w[name].work = IDLE;
});

const asToday = (messages: Message[], o: SendOptions = {}) => today(on.borealis, up, messages, o);

/** The text of the last block of the first user message the provider got on `session`'s latest request. */
const last = (session: string) => blocks(up.of(session).at(-1)!.body.messages[0]).at(-1)!.text ?? "";

describe("capability-rule", () => {
  test('mode = "any" calls it for a print-mode run as for a session in a terminal', async () => {
    const printed = await first(on.astra, "Add a --json flag to the status command.", { userAgent: PRINT });
    expect(printed.sent.status).toBe(200);
    expect(w.anymode.of(printed.session).length).toBe(1);
    expect(last(printed.session)).toContain(w.anymode.text(w.anymode.of(printed.session)[0]!));

    const typed = await first(on.astra, "Add a --json flag to the status command.");
    expect(w.anymode.of(typed.session).length).toBe(1);
    expect(last(typed.session)).toContain(w.anymode.text(w.anymode.of(typed.session)[0]!));
  });

  test('thread = "subagent" calls it for each subagent\'s first turn, and never for the main thread', async () => {
    const main = await first(on.cirrus, "Refactor the retry ladder.");
    expect(main.sent.status).toBe(200);
    expect(w.sub.of(main.session).length).toBe(0);
    expect(blocks(up.of(main.session)[0]!.body.messages[0])).toEqual(main.mine);

    const task = opening("Find every caller of the retry ladder.");
    expect((await send(on.cirrus, main.session, [{ role: "user", content: task }], { agent: "agent-a1b2c3" })).status).toBe(200);
    expect(w.sub.of(main.session).length).toBe(1);
    const block = blocks(up.of(main.session).at(-1)!.body.messages[0]).at(-1)!;
    expect(block.text ?? "").toContain(w.sub.text(w.sub.of(main.session)[0]!));

    // that subagent's next turn carries the block and asks nobody; another subagent is a thread of its own
    expect((await send(on.cirrus, main.session, second(task), { agent: "agent-a1b2c3" })).status).toBe(200);
    expect(blocks(up.of(main.session).at(-1)!.body.messages[0]).at(-1)).toEqual(block);
    expect(w.sub.of(main.session).length).toBe(1);
    expect((await send(on.cirrus, main.session, [{ role: "user", content: opening("List the tests of the retry ladder.") }], { agent: "agent-d4e5f6" })).status).toBe(200);
    expect(w.sub.of(main.session).length).toBe(2);
    const turns = w.sub.of(main.session).map((a) => a.body.turn);
    expect(turns[0]).not.toBe(turns[1]);
  });

  test("models names the models it is called for, as the request's model after splice resolved it", async () => {
    const large = await first(on.draco, "Add a --json flag to the status command.");
    expect(up.of(large.session)[0]!.body.model).toBe("draco-large");
    expect(w.small.of(large.session).length).toBe(0);
    expect(blocks(up.of(large.session)[0]!.body.messages[0])).toEqual(large.mine);

    const small = await first(on.draco, "Add a --json flag to the status command.", { model: "claude-draco--draco-small" });
    expect(up.of(small.session)[0]!.body.model).toBe("draco-small");
    expect(w.small.of(small.session).length).toBe(1);
    expect(w.small.of(small.session)[0]!.body.fields).toEqual({ model: "draco-small" });
    expect(last(small.session)).toContain(w.small.text(w.small.of(small.session)[0]!));
  });

  test('turn = "any" calls it for every turn, a session first seen mid-conversation included, and each turn can be refused', async () => {
    const { session, mine } = await first(on.eos, "Add a --json flag to the status command.");
    expect(w.every.of(session).length).toBe(1);
    expect((await send(on.eos, session, second(mine))).status).toBe(200);
    expect(w.every.of(session).length).toBe(2);
    expect(up.of(session).at(-1)!.body.messages).toEqual(await asToday(second(mine)));
    const turns = w.every.of(session).map((a) => a.body.turn);
    expect(turns[0]).not.toBe(turns[1]);

    const midway = crypto.randomUUID();
    expect((await send(on.eos, midway, second(opening("Explain the retry ladder.")))).status).toBe(200);
    expect(w.every.of(midway).length).toBe(1);

    w.every.work = { ...IDLE, end: { refuse: "Not after six." } };
    const sent = await send(on.eos, session, second(mine, "Now delete the old flag."));
    expect(sent.error).not.toBeNull();
    expect(sent.error!.message).toContain("every");
    expect(sent.error!.message).toContain("Not after six.");
    expect(up.of(session).length).toBe(2);
  });

  test("two capabilities on one request run in the order they are declared, one at a time, and both blocks stay", async () => {
    w.one.work = { ...IDLE, waitMs: 1200 };
    const { session, mine, sent } = await first(on.fen, "Add a --json flag to the status command.");
    expect(sent.status).toBe(200);
    const [a, b] = [w.one.of(session), w.two.of(session)];
    expect(a.length).toBe(1);
    expect(b.length).toBe(1);
    expect(b[0]!.at - a[0]!.at).toBeGreaterThanOrEqual(1100);

    const content = blocks(up.of(session)[0]!.body.messages[0]);
    expect(content.length).toBe(mine.length + 2);
    expect(content.slice(0, mine.length)).toEqual(mine);
    expect(content.at(-2)!.text ?? "").toContain(w.one.text(a[0]!));
    expect(content.at(-1)!.text ?? "").toContain(w.two.text(b[0]!));

    expect((await send(on.fen, session, second(mine))).status).toBe(200);
    expect(blocks(up.of(session).at(-1)!.body.messages[0]).slice(-2)).toEqual(content.slice(-2));
    expect(w.one.of(session).length + w.two.of(session).length).toBe(2);
  }, 60_000);
});
