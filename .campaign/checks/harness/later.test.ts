// capability-later-turns: the client never saw the block a capability added, so its history does not hold it. splice
// adds the same block to every request of the session whose first user message is the one it was added to: later
// turns and the request that compacts the conversation. After a compaction the first message is the summary, and
// nothing is added. What a session carries, and that it was seen, is on disk: a restart of the daemon, or switching
// the capability off, changes no live session's request. A turn's record says what it carried and how long it was
// held. (docs/specs/harness.md, decisions 1, 5 and 10.)

import { afterAll, afterEach, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { Splice, freePort } from "../server/lib/harness.ts";
import { type Launched, launch } from "../speed/lib/session.ts";
import { type Block, IDLE, KEYS, type Message, Provider, type SendOptions, Worker, blocks, capability, first, head, opening, rows, second, send, today } from "./lib/capability.ts";

// No test here is a claim about speed: a busy machine must not fail one on bun's default five seconds.
setDefaultTimeout(120_000);

let up: Provider;
let worker: Worker;
let s: Splice;
let on: Launched;
let off: Launched;
let ports: { astra: number; borealis: number };

/** The daemon's config, with the capability switched on for `heads`. */
function config(heads: string[]): string {
  return (
    head({ key: "astra", command: "claude-astra", port: ports.astra, url: up.url }) +
    head({ key: "borealis", command: "claude-borealis", port: ports.borealis, url: up.url }) +
    capability("brief", { heads, url: worker.url })
  );
}

beforeAll(async () => {
  up = new Provider("the provider").start();
  worker = new Worker().start();
  ports = { astra: await freePort(), borealis: await freePort() };
  s = await Splice.boot(config(["astra"]), KEYS);
  on = await launch(s, "claude-astra");
  off = await launch(s, "claude-borealis");
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  up?.stop();
  worker?.stop();
});

afterEach(() => {
  worker.work = IDLE;
});

const asToday = (messages: Message[], o: SendOptions = {}) => today(off, up, messages, o);

/** The block the capability added to `session`'s first turn: the last one of the first user message the provider got. */
function added(session: string): Block {
  const block = blocks(up.of(session)[0]!.body.messages[0]).at(-1)!;
  expect(block.text ?? "").toContain(worker.text(worker.of(session)[0]!));
  return block;
}

/** The first message after a compaction: the summary, where the session's first message used to be. */
const SUMMARY: Message[] = [
  { role: "user", content: [{ type: "text", text: "This session is being continued from a previous conversation. Summary: the flag was added.", cache_control: { type: "ephemeral", ttl: "1h" } }] },
];

describe("capability-later-turns", () => {
  test("a later turn carries the same block in the first user message, and the worker is not asked again", async () => {
    const { session, mine } = await first(on, "Add a --json flag to the status command.");
    const block = added(session);

    const later = second(mine);
    expect((await send(on, session, later)).status).toBe(200);
    const got = up.of(session).at(-1)!.body.messages;
    const plain = await asToday(later);
    expect(got.length).toBe(plain.length);
    expect(got.slice(1)).toEqual(plain.slice(1));
    expect(blocks(got[0]).slice(0, -1)).toEqual(blocks(plain[0]));
    expect(blocks(got[0]).at(-1)).toEqual(block);
    expect(worker.of(session).length).toBe(1);
  });

  test("the request that compacts the conversation carries it, and the first message after the compaction carries nothing", async () => {
    const { session, mine } = await first(on, "Rename the --tail flag of the logs command to --last.");
    const block = added(session);

    const compacting: Message[] = [...second(mine).slice(0, 2), { role: "user", content: [{ type: "text", text: "Summarize the conversation so far." }] }];
    expect((await send(on, session, compacting, { tools: false })).status).toBe(200);
    expect(blocks(up.of(session).at(-1)!.body.messages[0]).at(-1)).toEqual(block);

    expect((await send(on, session, SUMMARY)).status).toBe(200);
    expect(up.of(session).at(-1)!.body.messages).toEqual(await asToday(SUMMARY));
    expect(worker.of(session).length).toBe(1);
  });

  test("a subagent's request in the session goes as it came, and the main thread keeps its block", async () => {
    const { session, mine } = await first(on, "Why does the daemon's log rotate at midnight?");
    const block = added(session);

    const sub: Message[] = [{ role: "user", content: opening("Find where the log is rotated.") }];
    expect((await send(on, session, sub, { agent: "agent-a1b2c3" })).status).toBe(200);
    expect(up.of(session).at(-1)!.body.messages).toEqual(await asToday(sub, { agent: "agent-a1b2c3" }));

    expect((await send(on, session, second(mine))).status).toBe(200);
    expect(blocks(up.of(session).at(-1)!.body.messages[0]).at(-1)).toEqual(block);
    expect(worker.of(session).length).toBe(1);
  });

  test("a session first seen mid-conversation is never called, not even for the first message after a compaction", async () => {
    const session = crypto.randomUUID();
    expect((await send(on, session, second(opening("Explain the retry ladder.")))).status).toBe(200);
    expect((await send(on, session, SUMMARY)).status).toBe(200);
    expect(up.of(session).at(-1)!.body.messages).toEqual(await asToday(SUMMARY));
    expect(worker.of(session).length).toBe(0);
  });

  test("a turn's record says what the request carried and how long the turn was held", async () => {
    worker.work = { ...IDLE, waitMs: 1500 };
    const { session, mine } = await first(on, "Add a --since flag to the logs command.");
    const text = added(session).text ?? "";
    expect((await send(on, session, second(mine))).status).toBe(200);

    const [one, two] = await rows(s, "astra", session, 2);
    expect(one!.held_on).toBe("brief");
    expect(one!.held_ms as number).toBeGreaterThanOrEqual(1400);
    expect(one!.held_ms as number).toBeLessThan(10_000);
    expect(one!.capability_text_chars).toBe(text.length);
    // the later turn carried the text and was not held
    expect(two!.held_on).toBeNull();
    expect(two!.held_ms).toBe(0);
    expect(two!.capability_text_chars).toBe(text.length);

    const plain = await first(off, "Add a --since flag to the logs command.");
    const [none] = await rows(s, "borealis", plain.session);
    expect(none!.held_on).toBeNull();
    expect(none!.held_ms).toBe(0);
    expect(none!.capability_text_chars).toBe(0);
  }, 60_000);

  test("restarting the daemon changes no live session's request", async () => {
    const carried = await first(on, "Add a --json flag to the doctor command.");
    const block = added(carried.session);
    const midway = crypto.randomUUID();
    expect((await send(on, midway, second(opening("Explain the retry ladder.")))).status).toBe(200);

    await s.restart();

    expect((await send(on, carried.session, second(carried.mine))).status).toBe(200);
    expect(blocks(up.of(carried.session).at(-1)!.body.messages[0]).at(-1)).toEqual(block);
    expect(worker.of(carried.session).length).toBe(1);

    expect((await send(on, midway, SUMMARY)).status).toBe(200);
    expect(up.of(midway).at(-1)!.body.messages).toEqual(await asToday(SUMMARY));
    expect(worker.of(midway).length).toBe(0);
  }, 180_000);

  test("switched off, a session that carries a block keeps it, and a new session is not called", async () => {
    const carried = await first(on, "Add a --quiet flag to the restart command.");
    const block = added(carried.session);

    s.writeConfig(config([]));
    await s.restart();

    expect((await send(on, carried.session, second(carried.mine))).status).toBe(200);
    expect(blocks(up.of(carried.session).at(-1)!.body.messages[0]).at(-1)).toEqual(block);

    const fresh = await first(on, "Add a --quiet flag to the restart command.");
    expect(fresh.sent.status).toBe(200);
    expect(worker.of(fresh.session).length).toBe(0);
    expect(blocks(up.of(fresh.session)[0]!.body.messages[0])).toEqual(fresh.mine);
  }, 180_000);
});
