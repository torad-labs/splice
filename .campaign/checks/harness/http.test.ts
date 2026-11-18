// capability-http: a capability declared in splice.toml with an HTTP worker is called on the turn its rule names. The
// worker is posted the fields its declaration names and nothing else, the turn stays open while it works, and its
// text reaches the provider in the first user message, after the client's own blocks. A request the rule does not
// name, or one on a head the capability is not switched on for, goes to the provider as it came.
// (docs/specs/harness.md, decisions 1 to 5.)

import { afterAll, afterEach, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { Splice, freePort } from "../server/lib/harness.ts";
import { type Launched, launch, sessionKey } from "../speed/lib/session.ts";
import { KEYS, type Message, PRINT, Provider, type SendOptions, Worker, blocks, capability, first, head, opening, send } from "./lib/capability.ts";

// No test here is a claim about speed: a busy machine must not fail one on bun's default five seconds.
setDefaultTimeout(120_000);

let astra: Provider;
let borealis: Provider;
let worker: Worker;
let s: Splice;
let on: Launched;
let off: Launched;

beforeAll(async () => {
  astra = new Provider("astra").start();
  borealis = new Provider("borealis").start();
  worker = new Worker().start();
  s = await Splice.boot(
    head({ key: "astra", command: "claude-astra", port: await freePort(), url: astra.url }) +
      head({ key: "borealis", command: "claude-borealis", port: await freePort(), url: borealis.url }) +
      capability("brief", { heads: ["astra"], url: worker.url, send: ["first_user_message", "model"], authEnv: "WORKER_KEY" }),
    KEYS,
  );
  on = await launch(s, "claude-astra");
  off = await launch(s, "claude-borealis");
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  astra?.stop();
  borealis?.stop();
  worker?.stop();
});

afterEach(() => {
  worker.work = { waitMs: 0, progress: [], gapMs: 0, end: { text: "" }, status: null };
});

/** What the provider behind a head with no capability is sent for `messages`: the request as it is today. */
async function asToday(messages: Message[], o: SendOptions = {}): Promise<Message[]> {
  const session = crypto.randomUUID();
  await send(off, session, messages, o);
  return borealis.of(session).at(-1)!.body.messages;
}

describe("capability-http", () => {
  test("the worker is asked once, with the fields its declaration names and nothing else", async () => {
    const { session, sent } = await first(on, "Add a --json flag to the status command.");
    expect(sent.status).toBe(200);
    expect(sent.body).toContain(astra.reply());

    const asked = worker.of(session);
    expect(asked.length).toBe(1);
    const a = asked[0]!;
    expect(a.body.capability).toBe("brief");
    expect(String(a.body.turn ?? "")).not.toBe("");
    expect(Object.keys(a.body).sort()).toEqual(["capability", "fields", "session", "turn"]);
    expect(Object.keys(a.body.fields ?? {}).sort()).toEqual(["first_user_message", "model"]);
    expect(String(a.body.fields!.first_user_message)).toContain("Add a --json flag to the status command.");
    expect(a.body.fields!.model).toBe("astra-large");
    // the worker's own key, and none of the session's or the provider's
    expect(a.headers.authorization).toBe(`Bearer ${KEYS.WORKER_KEY}`);
    const whole = JSON.stringify(a);
    expect(whole).not.toContain(KEYS.MOCK_UPSTREAM_KEY);
    expect(whole).not.toContain(sessionKey(on));
  });

  test("its text reaches the provider in the first user message, after the client's own blocks", async () => {
    const { session, mine } = await first(on, "Rename the --tail flag of the logs command to --last.");
    const got = astra.of(session);
    expect(got.length).toBe(1);
    expect(got[0]!.body.messages.length).toBe(1);

    const today = blocks((await asToday([{ role: "user", content: mine }]))[0]);
    const content = blocks(got[0]!.body.messages[0]);
    expect(content.length).toBe(today.length + 1);
    expect(content.slice(0, today.length)).toEqual(today);
    const added = content.at(-1)!;
    expect(added.type).toBe("text");
    expect(added.text ?? "").toContain(worker.text(worker.of(session)[0]!));
    expect(added.text ?? "").not.toMatch(/splice/i);
  });

  test("the turn is open while the worker works, and goes to the provider only when it is done", async () => {
    worker.work = { ...worker.work, waitMs: 4000 };
    const { session, sent } = await first(on, "Why does the daemon's log rotate at midnight?");
    expect(sent.status).toBe(200);
    expect(sent.body).toContain(astra.reply());
    const asked = worker.of(session)[0]!;
    const sentUp = astra.of(session)[0]!;
    // Claude Code has a live response before the worker has answered
    expect(sent.sentAt + sent.arrivals[0]!.ms).toBeLessThan(sentUp.at);
    expect(sentUp.at - asked.at).toBeGreaterThanOrEqual(3900);
  }, 60_000);

  test("a request the rule does not name goes to the provider as it came, and the worker is not asked", async () => {
    const typed = "Explain the retry ladder.";
    const before = worker.asked.length;
    const cases: { what: string; messages: Message[]; o: SendOptions }[] = [
      { what: "a subagent's first turn", messages: [{ role: "user", content: opening(typed) }], o: { agent: "agent-a1b2c3" } },
      { what: "a print-mode run", messages: [{ role: "user", content: opening(typed) }], o: { userAgent: PRINT } },
      {
        what: "a session first seen mid-conversation",
        messages: [
          { role: "user", content: opening(typed) },
          { role: "assistant", content: [{ type: "text", text: "It doubles." }] },
          { role: "user", content: [{ type: "text", text: "And after that?" }] },
        ],
        o: {},
      },
    ];
    for (const c of cases) {
      const session = crypto.randomUUID();
      const sent = await send(on, session, c.messages, c.o);
      expect(sent.status, c.what).toBe(200);
      expect(astra.of(session).at(-1)!.body.messages, c.what).toEqual(await asToday(c.messages, c.o));
    }
    expect(worker.asked.length).toBe(before);
  });

  test("a side request without tools decides nothing: the session's first turn after it is still the first", async () => {
    const session = crypto.randomUUID();
    const side: Message[] = [{ role: "user", content: [{ type: "text", text: "Write a title for this conversation." }] }];
    expect((await send(on, session, side, { tools: false })).status).toBe(200);
    expect(worker.of(session).length).toBe(0);
    expect(astra.of(session).at(-1)!.body.messages).toEqual(await asToday(side, { tools: false }));

    const mine = opening("Add a --since flag to the logs command.");
    expect((await send(on, session, [{ role: "user", content: mine }])).status).toBe(200);
    expect(worker.of(session).length).toBe(1);
    expect(blocks(astra.of(session).at(-1)!.body.messages[0]).at(-1)!.text ?? "").toContain(worker.text(worker.of(session)[0]!));
  });

  test("on a head the capability is not switched on for, nothing is asked and nothing is added", async () => {
    const before = worker.asked.length;
    const { session, mine, sent } = await first(off, "Add a --json flag to the status command.");
    expect(sent.status).toBe(200);
    expect(worker.asked.length).toBe(before);
    const content = blocks(borealis.of(session)[0]!.body.messages[0]);
    expect(content.map((b) => b.text)).toEqual(mine.map((b) => b.text));
  });
});
