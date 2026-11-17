// getter-says: while the Context Getter holds a session's first turn, the session's status line says what it is doing:
// `Exploring files with <the fast model's label>` when it starts, then each step the gatherer reports (Claude Code's
// own tool-use summaries, from its stream) as it comes, and `Exploring stopped, continuing without it` when the
// gatherer stops early. When the turn has gone on, the line is what it was. (docs/specs/harness.md, decisions 7 and 9.)

import { afterAll, afterEach, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { Splice, freePort, until } from "../server/lib/harness.ts";
import { type Launched, launch } from "../speed/lib/session.ts";
import { KEYS, opening, send } from "./lib/capability.ts";
import { Backend, Fake, contextGetter, folder, labelledHead, register, sessionOf } from "./lib/gatherer.ts";
import { line } from "./lib/held.ts";

// No test here is a claim about speed: a busy machine must not fail one on bun's default five seconds.
setDefaultTimeout(120_000);

const STARTED = "Exploring files with Astra Small";
const STOPPED = "Exploring stopped, continuing without it";

let up: Backend;
let fake: Fake;
let s: Splice;
let astra: Launched;
let n = 0;

beforeAll(async () => {
  up = new Backend("astra").start();
  fake = new Fake();
  s = await Splice.boot(
    labelledHead({
      key: "astra",
      command: "claude-astra",
      port: await freePort(),
      url: up.url,
      large: { id: "astra-large", label: "Astra Large" },
      small: { id: "astra-small", label: "Astra Small" },
    }) + contextGetter(["astra"]),
    { ...KEYS, PATH: fake.path },
  );
  astra = await launch(s, "claude-astra");
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  up?.stop();
  fake?.dispose();
});

afterEach(() => {
  fake.reset();
  up.slow = () => 0;
});

/** The first turn of a new session in a folder Claude Code registered, sent and not awaited. */
function started(typed: string) {
  const session = crypto.randomUUID();
  register(astra.dir, session, folder(s, `p${++n}`), typed);
  return { session, sending: send(astra, session, [{ role: "user", content: opening(typed) }]) };
}

describe("getter-says", () => {
  test("the held turn says it is exploring with the fast model's label, then each step the gatherer reports, as it comes", async () => {
    const typed = "Add a --json flag to the status command.";
    fake.when(typed, { delayMs: 5000, summaries: ["Read src/main.ts", "Searched for test files"], gapMs: 5000 });
    const { session, sending } = started(typed);
    await until("the gatherer starts", () => fake.of(typed).length === 1, 30_000);
    await until("the line says it is exploring", () => line(astra, session).includes(STARTED), 4000);
    // the model's name still follows the held turn's part
    expect(line(astra, session)).toContain("astra");

    await until("the gatherer reports its first step", () => fake.of(typed)[0]!.emitted.length >= 1, 15_000);
    await until("the line says the first step", () => line(astra, session).includes("Read src/main.ts"), 4000);
    expect(line(astra, session)).not.toContain(STARTED);

    await until("the gatherer reports its second step", () => fake.of(typed)[0]!.emitted.length >= 2, 15_000);
    await until("the line says the second step", () => line(astra, session).includes("Searched for test files"), 4000);
    expect(line(astra, session)).not.toContain("Read src/main.ts");

    expect((await sending).status).toBe(200);
    const after = line(astra, session);
    expect(after).not.toContain("Searched for test files");
    expect(after).not.toContain(STARTED);
  }, 300_000);

  test("a gatherer that stops early is said to have stopped, and the turn goes on without it", async () => {
    const typed = "Rename the --tail flag of the logs command to --last.";
    fake.when(typed, { mode: "fail", delayMs: 2000, summaries: ["Read src/main.ts"], gapMs: 2000 });
    const { session, sending } = started(typed);
    // the provider is slow for this session's turn, so a person has time to read the line once the turn goes on
    up.slow = (body) => (sessionOf(body) === session ? 5000 : 0);
    let done = false;
    const seen: string[] = [];
    const finished = sending.then((sent) => {
      done = true;
      return sent;
    });
    while (!done) {
      seen.push(line(astra, session));
      await Bun.sleep(100);
    }
    const sent = await finished;
    expect(sent.status).toBe(200);
    expect(sent.body).toContain(up.reply());
    expect(fake.of(typed).length).toBe(1);
    expect(seen.some((l) => l.includes(STARTED))).toBe(true);
    expect(seen.some((l) => l.includes(STOPPED))).toBe(true);
    // what it said last before it stopped came before the stop
    const step = seen.findIndex((l) => l.includes("Read src/main.ts"));
    expect(step).toBeGreaterThanOrEqual(0);
    expect(step).toBeLessThan(seen.findIndex((l) => l.includes(STOPPED)));
    expect(line(astra, session)).not.toContain(STOPPED);
  }, 300_000);
});
