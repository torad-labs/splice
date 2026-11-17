// capability-failure: what a turn does when its capability's work fails, refuses, or is cut short. A capability
// declared `on_failure = "open"` lets the turn go on with today's bytes; one declared `"closed"` refuses it. A worker
// that may refuse refuses the turn with its own reason, and nothing goes to the provider. The work is stopped when
// the client leaves, when the same thread sends again, and when the head stops; in none of them is anything placed.
// (docs/specs/harness.md, decisions 1, 3 and 6.)

import { afterAll, afterEach, beforeAll, describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { Splice, freePort, until } from "../server/lib/harness.ts";
import { type Launched, launch } from "../speed/lib/session.ts";
import { IDLE, KEYS, type Message, Provider, type SendOptions, type Work, Worker, blocks, capability, first, head, opening, second, send, today } from "./lib/capability.ts";

let up: Provider;
/** `brief` may add and fails open; `gate` may add or refuse and fails closed; `guard` places nothing and may refuse. */
let brief: Worker;
let gate: Worker;
let guard: Worker;
let s: Splice;
let open: Launched;
let closed: Launched;
let guarded: Launched;
let off: Launched;

const LIMIT_S = 4;

beforeAll(async () => {
  up = new Provider("the provider").start();
  brief = new Worker().start();
  gate = new Worker().start();
  guard = new Worker().start();
  const heads = ["astra", "cirrus", "draco", "borealis"];
  const tables = await Promise.all(heads.map(async (key) => head({ key, command: `claude-${key}`, port: await freePort(), url: up.url })));
  s = await Splice.boot(
    tables.join("") +
      capability("brief", { heads: ["astra"], url: brief.url, authEnv: "WORKER_KEY", timeout: LIMIT_S }) +
      capability("gate", { heads: ["cirrus"], url: gate.url, authEnv: "WORKER_KEY", timeout: LIMIT_S, may: "add-or-refuse", onFailure: "closed" }) +
      capability("guard", { heads: ["draco"], url: guard.url, authEnv: "WORKER_KEY", timeout: LIMIT_S, place: "none", may: "refuse" }),
    KEYS,
  );
  open = await launch(s, "claude-astra");
  closed = await launch(s, "claude-cirrus");
  guarded = await launch(s, "claude-draco");
  off = await launch(s, "claude-borealis");
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  for (const each of [up, brief, gate, guard]) each?.stop();
});

afterEach(() => {
  for (const each of [brief, gate, guard]) each.work = IDLE;
});

const asToday = (messages: Message[], o: SendOptions = {}) => today(off, up, messages, o);

describe("capability-failure", () => {
  test("open: a worker that fails leaves the turn as it is today, and the session is not asked for again", async () => {
    const ways: { what: string; work: Partial<Work> }[] = [
      { what: "answers 500", work: { status: 500 } },
      { what: "sends a line that is none of the three", work: { end: { line: '{"type":"answer","text":"x"}' } } },
      { what: "sends a line that is not JSON", work: { end: { line: "not json at all" } } },
      { what: "ends with no result", work: { progress: ["Reading the request"], end: "nothing" } },
      { what: "refuses, where it may only add", work: { end: { refuse: "No." } } },
    ];
    for (const w of ways) {
      brief.work = { ...IDLE, ...w.work };
      const { session, mine, sent } = await first(open, `Explain the retry ladder, when the worker ${w.what}.`);
      expect(sent.status, w.what).toBe(200);
      expect(sent.error, w.what).toBeNull();
      expect(sent.body, w.what).toContain(up.reply());
      expect(blocks(up.of(session)[0]!.body.messages[0]), w.what).toEqual(mine);

      brief.work = IDLE;
      expect((await send(open, session, second(mine))).status, w.what).toBe(200);
      expect(up.of(session).at(-1)!.body.messages, w.what).toEqual(await asToday(second(mine)));
      expect(brief.of(session).length, w.what).toBe(1);
    }
  }, 120_000);

  test("open: past its time limit the work is given up, and the turn goes on as today", async () => {
    brief.work = { ...IDLE, waitMs: 9000 };
    const { session, mine, sent } = await first(open, "Add a --json flag to the status command.");
    expect(sent.status).toBe(200);
    expect(sent.body).toContain(up.reply());
    const waited = up.of(session)[0]!.at - sent.sentAt;
    expect(waited).toBeGreaterThanOrEqual(LIMIT_S * 1000 - 100);
    expect(waited).toBeLessThan(LIMIT_S * 1000 + 3000);
    expect(blocks(up.of(session)[0]!.body.messages[0])).toEqual(mine);
  }, 60_000);

  test("closed: a failure refuses the turn, naming the capability, and nothing goes to the provider", async () => {
    const ways: { what: string; work: Partial<Work> }[] = [
      { what: "answers 500", work: { status: 500 } },
      { what: "ends with no result", work: { end: "nothing" } },
      { what: "passes its time limit", work: { waitMs: 9000 } },
    ];
    for (const w of ways) {
      gate.work = { ...IDLE, ...w.work };
      const { session, sent } = await first(closed, `Explain the retry ladder, when the worker ${w.what}.`);
      expect(sent.error, w.what).not.toBeNull();
      expect(sent.error!.message, w.what).toContain("gate");
      expect(sent.body, w.what).not.toContain(up.reply());
      expect(sent.body, w.what).not.toContain(KEYS.WORKER_KEY);
      expect(up.of(session).length, w.what).toBe(0);
    }
  }, 120_000);

  test("a refusal refuses the turn with the worker's reason, and the session's next first message is asked for again", async () => {
    gate.work = { ...IDLE, end: { refuse: "This request names a customer." } };
    const session = crypto.randomUUID();
    const refused = await send(closed, session, [{ role: "user", content: opening("Mail the list to the customer Acme.") }]);
    expect(refused.error).not.toBeNull();
    expect(refused.error!.message).toContain("gate");
    expect(refused.error!.message).toContain("This request names a customer.");
    expect(refused.body).not.toContain(up.reply());
    expect(up.of(session).length).toBe(0);

    // a refused turn was not the session's first turn: what the person sends next is checked like the first
    gate.work = IDLE;
    const again = await send(closed, session, [{ role: "user", content: opening("Mail the list to the team.") }]);
    expect(again.status).toBe(200);
    expect(again.body).toContain(up.reply());
    expect(gate.of(session).length).toBe(2);
    expect(blocks(up.of(session)[0]!.body.messages[0]).at(-1)!.text ?? "").toContain(gate.text(gate.of(session)[1]!));
  });

  test("a capability that places nothing lets a turn pass as it came, or refuses it", async () => {
    const passed = await first(guarded, "Add a --json flag to the status command.");
    expect(passed.sent.status).toBe(200);
    expect(passed.sent.body).toContain(up.reply());
    expect(guard.of(passed.session).length).toBe(1);
    expect(blocks(up.of(passed.session)[0]!.body.messages[0])).toEqual(passed.mine);

    guard.work = { ...IDLE, end: { refuse: "Not on this machine." } };
    const refused = await first(guarded, "Delete the release branch.");
    expect(refused.sent.error).not.toBeNull();
    expect(refused.sent.error!.message).toContain("guard");
    expect(refused.sent.error!.message).toContain("Not on this machine.");
    expect(up.of(refused.session).length).toBe(0);
  });

  test("the client leaves while the turn waits: the work is stopped, and nothing is sent to the provider", async () => {
    brief.work = { ...IDLE, waitMs: 3000 };
    const session = crypto.randomUUID();
    const gone = new AbortController();
    const sending = send(open, session, [{ role: "user", content: opening("Why does the log rotate at midnight?") }], { signal: gone.signal }).catch(() => null);
    await until("the worker is asked", () => brief.of(session).length === 1);
    await Bun.sleep(300);
    gone.abort();
    await sending;
    await until("the worker's request is dropped", () => brief.dropped.includes(session), 2000);
    await Bun.sleep(3500);
    expect(up.of(session).length).toBe(0);
  }, 60_000);

  test("a second request of the thread while the first waits ends the wait: both go on as today", async () => {
    brief.work = { ...IDLE, waitMs: 3500 };
    const session = crypto.randomUUID();
    const mine = opening("Rename the --tail flag of the logs command to --last.");
    const one = send(open, session, [{ role: "user", content: mine }]);
    await until("the worker is asked", () => brief.of(session).length === 1);
    const from = Date.now();
    const two = await send(open, session, [{ role: "user", content: mine }]);
    const held = await one;
    for (const sent of [held, two]) {
      expect(sent.status).toBe(200);
      expect(sent.error).toBeNull();
      expect(sent.body).toContain(up.reply());
    }
    const got = up.of(session);
    expect(got.length).toBe(2);
    for (const g of got) {
      expect(blocks(g.body.messages[0])).toEqual(mine);
      // before the worker would have answered
      expect(g.at - from).toBeLessThan(2500);
    }

    brief.work = IDLE;
    expect((await send(open, session, second(mine))).status).toBe(200);
    expect(up.of(session).at(-1)!.body.messages).toEqual(await asToday(second(mine)));
    expect(brief.of(session).length).toBe(1);
  }, 60_000);

  test("the log says which capability failed, and never holds the worker's key", async () => {
    brief.work = { ...IDLE, status: 500 };
    await first(open, "Add a --since flag to the logs command.");
    const log = readFileSync(s.log, "utf8");
    expect(log).toContain("brief");
    expect(log).not.toContain(KEYS.WORKER_KEY);
  });

  test("open: a worker that cannot be reached leaves the turn as it is today", async () => {
    guard.stop();
    const { session, mine, sent } = await first(guarded, "Add a --json flag to the doctor command.");
    expect(sent.status).toBe(200);
    expect(sent.error).toBeNull();
    expect(sent.body).toContain(up.reply());
    expect(blocks(up.of(session)[0]!.body.messages[0])).toEqual(mine);
  });

  test("the head stops while a turn waits: the work is stopped, and the turn goes to the provider as it is today", async () => {
    brief.work = { ...IDLE, waitMs: 3500 };
    const session = crypto.randomUUID();
    const mine = opening("Why is the control port 3096?");
    const sending = send(open, session, [{ role: "user", content: mine }]);
    await until("the worker is asked", () => brief.of(session).length === 1);
    const from = Date.now();
    const stopping = s.stop();
    const sent = await sending;
    await stopping;
    expect(sent.status).toBe(200);
    expect(sent.error).toBeNull();
    expect(sent.body).toContain(up.reply());
    const got = up.of(session);
    expect(got.length).toBe(1);
    expect(blocks(got[0]!.body.messages[0])).toEqual(mine);
    expect(got[0]!.at - from).toBeLessThan(2500);
  }, 60_000);
});
