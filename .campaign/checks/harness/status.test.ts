// held-says: while a capability holds a turn, the session's status line leads with what the held turn says, in its
// colour, and how long ago it said it; each progress line the worker sends becomes what it says. With two turns of the
// session held, the line says how many. When the turn goes on, the line is what it was, and no other session's line
// ever changes. (docs/specs/harness.md, decision 7.)

import { afterAll, afterEach, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { Splice, freePort, until } from "../server/lib/harness.ts";
import { type Launched, launch } from "../speed/lib/session.ts";
import { IDLE, KEYS, Provider, Worker, capability, head, opening, send } from "./lib/capability.ts";
import { age, colour, drawn, line } from "./lib/held.ts";

// No test here is a claim about speed: a busy machine must not fail one on bun's default five seconds.
setDefaultTimeout(120_000);

const READING = "Reading the request";
const CHECKING = "Checking the request";

let up: Provider;
let brief: Worker;
let audit: Worker;
let s: Splice;
let astra: Launched;
let cirrus: Launched;

beforeAll(async () => {
  up = new Provider("the provider").start();
  brief = new Worker().start();
  audit = new Worker().start();
  s = await Splice.boot(
    head({ key: "astra", command: "claude-astra", port: await freePort(), url: up.url }) +
      head({ key: "cirrus", command: "claude-cirrus", port: await freePort(), url: up.url }) +
      capability("brief", { heads: ["astra"], url: brief.url, message: READING, color: "cyan", when: '{ thread = "any" }' }) +
      capability("audit", { heads: ["cirrus"], url: audit.url, message: CHECKING, color: "yellow" }),
    KEYS,
  );
  astra = await launch(s, "claude-astra");
  cirrus = await launch(s, "claude-cirrus");
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  for (const each of [up, brief, audit]) each?.stop();
});

afterEach(() => {
  brief.work = IDLE;
  audit.work = IDLE;
});

const typed = (text: string) => [{ role: "user", content: opening(text) }];

describe("held-says", () => {
  test("a held turn's message leads the status line with how long ago it came, and the line is what it was afterwards", async () => {
    const session = crypto.randomUUID();
    const other = crypto.randomUUID();
    expect(line(astra, session)).toContain("astra-large");
    expect(line(astra, session)).not.toContain(READING);

    brief.work = { ...IDLE, waitMs: 12_000 };
    const sending = send(astra, session, typed("Add a --json flag to the status command."));
    await until("the worker is asked", () => brief.of(session).length === 1);
    await Bun.sleep(1200);
    const first = line(astra, session);
    const one = age(first, READING);
    expect(one).not.toBeNull();
    expect(one!).toBeLessThanOrEqual(5);
    // the message leads, and the model's name follows it as it does on a limit hold today
    expect(first.indexOf(READING)).toBeLessThan(first.indexOf("astra-large"));
    expect(line(astra, other)).not.toContain(READING);

    await Bun.sleep(3000);
    const two = age(line(astra, session), READING);
    expect(two).not.toBeNull();
    expect(two!).toBeGreaterThanOrEqual(one! + 1);

    expect((await sending).status).toBe(200);
    const after = line(astra, session);
    expect(after).not.toContain(READING);
    expect(after).toContain("astra-large");
  }, 300_000);

  test("each progress line the worker sends becomes what the held turn says, and its age starts again", async () => {
    brief.work = { ...IDLE, waitMs: 6000, progress: ["Opening src/main.ts", "Reading the tests"], gapMs: 6000 };
    const session = crypto.randomUUID();
    const sending = send(astra, session, typed("Why does the daemon's log rotate at midnight?"));
    await until("the worker is asked", () => brief.of(session).length === 1);
    const from = Date.now();
    const at = async (ms: number) => {
      await Bun.sleep(Math.max(0, from + ms - Date.now()));
      return line(astra, session);
    };
    expect(age(await at(3000), READING)).not.toBeNull();
    const opened = await at(9000);
    expect(opened).not.toContain(READING);
    expect(age(opened, "Opening src/main.ts")).not.toBeNull();
    expect(age(opened, "Opening src/main.ts")!).toBeLessThanOrEqual(5);
    const read = await at(15_000);
    expect(read).not.toContain("Opening src/main.ts");
    expect(age(read, "Reading the tests")!).toBeLessThanOrEqual(5);
    expect((await sending).status).toBe(200);
    expect(line(astra, session)).not.toContain("Reading the tests");
  }, 300_000);

  test("the message is drawn in the colour its capability declares", async () => {
    brief.work = { ...IDLE, waitMs: 8000 };
    audit.work = { ...IDLE, waitMs: 8000 };
    const [a, c] = [crypto.randomUUID(), crypto.randomUUID()];
    const sending = [send(astra, a, typed("Add a --since flag to the logs command.")), send(cirrus, c, typed("Add a --since flag to the logs command."))];
    await until("both workers are asked", () => brief.of(a).length === 1 && audit.of(c).length === 1);
    await Bun.sleep(500);
    const cyan = colour(drawn(astra, a), READING);
    const yellow = colour(drawn(cirrus, c), CHECKING);
    expect(cyan).not.toBeNull();
    expect(yellow).not.toBeNull();
    expect(cyan).not.toBe(yellow);
    for (const sent of await Promise.all(sending)) expect(sent.status).toBe(200);
  }, 300_000);

  test("with two turns of the session held the line says how many, and a turn that goes on leaves the other's message", async () => {
    brief.work = { ...IDLE, waitMs: 12_000 };
    const session = crypto.randomUUID();
    const main = send(astra, session, typed("Refactor the retry ladder."));
    await until("the worker is asked for the main thread", () => brief.of(session).length === 1);
    await Bun.sleep(6000);
    const sub = send(astra, session, typed("Find every caller of the retry ladder."), { agent: "agent-a1b2c3" });
    await until("the worker is asked for the subagent", () => brief.of(session).length === 2);
    await Bun.sleep(700);
    const both = line(astra, session);
    expect(both).toContain(READING);
    expect(both).toMatch(/\b2 turns\b/);

    expect((await main).status).toBe(200);
    await Bun.sleep(300);
    const one = line(astra, session);
    expect(one).toContain(READING);
    expect(one).not.toMatch(/\b\d+ turns\b/);

    expect((await sub).status).toBe(200);
    expect(line(astra, session)).not.toContain(READING);
  }, 300_000);
});
