// getter-limits: splice owns the gatherer's process. It is ended at the time limit, when the client leaves, when the
// head stops and when the daemon shuts down, each recorded with its reason, and it runs under a deadline of its own,
// so a daemon killed outright leaves no gatherer behind. The default time limit comes from gathers measured on real
// work, and the spec's table says which. (docs/specs/harness.md, decisions 6 and 9.)

import { afterAll, afterEach, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { ROOT, Splice, freePort, until } from "../server/lib/harness.ts";
import { type Launched, launch } from "../speed/lib/session.ts";
import { KEYS, type Sent, blocks, opening, send } from "./lib/capability.ts";
import { Backend, Fake, type Run, alive, contextGetter, daemonPid, folder, labelledHead, recorded, register, running } from "./lib/gatherer.ts";

// No test here is a claim about speed: a busy machine must not fail one on bun's default five seconds.
setDefaultTimeout(120_000);

/** The time limit declared here, in seconds: long enough that ending a gatherer for any other reason comes well
 *  before it. */
const LIMIT_S = 12;

let up: Backend;
let fake: Fake;
let s: Splice;
const at: Record<string, Launched> = {};
let n = 0;

const HEADS = ["astra", "cirrus", "draco"];

beforeAll(async () => {
  up = new Backend("the provider").start();
  fake = new Fake();
  const tables = await Promise.all(
    HEADS.map(async (key) =>
      labelledHead({
        key,
        command: `claude-${key}`,
        port: await freePort(),
        url: up.url,
        large: { id: `${key}-large`, label: `${key} large` },
        small: { id: `${key}-small`, label: `${key} small` },
      }),
    ),
  );
  s = await Splice.boot(tables.join("") + contextGetter(HEADS, `timeout_s = ${LIMIT_S}`), { ...KEYS, PATH: fake.path });
  for (const key of HEADS) at[key] = await launch(s, `claude-${key}`);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  up?.stop();
  fake?.dispose();
});

afterEach(() => fake.reset());

interface Held {
  session: string;
  typed: string;
  mine: ReturnType<typeof opening>;
  run: Run;
  sending: Promise<Sent | null>;
  from: number;
}

/** A first turn of a new session on head `key` whose gatherer never ends on its own, held while it runs. */
async function held(key: string, typed: string, signal?: AbortSignal): Promise<Held> {
  fake.when(typed, { mode: "hang" });
  const from = s.logTail(Number.MAX_SAFE_INTEGER).length;
  const session = crypto.randomUUID();
  register(at[key]!.dir, session, folder(s, `p${++n}`), typed);
  const mine = opening(typed);
  const sending = send(at[key]!, session, [{ role: "user", content: mine }], { signal }).catch(() => null);
  await until("the gatherer starts", () => fake.of(typed).length === 1, 30_000);
  const run = fake.of(typed)[0]!;
  expect(alive(run)).toBe(true);
  return { session, typed, mine, run, sending, from };
}

describe("getter-limits", () => {
  test("at its time limit the gatherer is ended, the turn goes on as today, and the reason is recorded", async () => {
    const h = await held("astra", "Add a --json flag to the status command.");
    const sent = await h.sending;
    expect(sent?.status).toBe(200);
    expect(sent?.body).toContain(up.reply());
    const got = up.of(h.session);
    expect(got.length).toBe(1);
    expect(blocks(got[0]!.body.messages[0]).map((b) => b.text)).toEqual(h.mine.map((b) => b.text));
    const waited = got[0]!.at - sent!.sentAt;
    expect(waited).toBeGreaterThanOrEqual(LIMIT_S * 1000 - 200);
    expect(waited).toBeLessThan(LIMIT_S * 1000 + 8000);
    await until("the gatherer is ended", () => !alive(h.run), 3000);
    await until("the reason is recorded", async () => (await recorded(s, { key: "astra", session: h.session, reason: /time|limit|deadline/i, from: h.from })) !== null);
  }, 120_000);

  test("when the client leaves, the gatherer is ended, nothing goes upstream, and the reason is recorded", async () => {
    const gone = new AbortController();
    const h = await held("cirrus", "Why does the daemon's log rotate at midnight?", gone.signal);
    await Bun.sleep(1000);
    gone.abort();
    await h.sending;
    await until("the gatherer is ended", () => !alive(h.run), 5000);
    await Bun.sleep(2000);
    expect(up.of(h.session).length).toBe(0);
    await until("the reason is recorded", async () => (await recorded(s, { key: "cirrus", session: h.session, reason: /client|left|leav|gone|cancel|abort/i, from: h.from })) !== null);
  }, 120_000);

  test("when the head stops, the gatherer is ended, the turn goes on as today, and the reason is recorded", async () => {
    const h = await held("draco", "Rename the --tail flag of the logs command to --last.");
    await Bun.sleep(1000);
    const stopped = await s.api("/api/heads/draco/stop", { method: "POST" });
    expect(stopped.status).toBe(200);
    await until("the gatherer is ended", () => !alive(h.run), 5000);
    const sent = await h.sending;
    expect(sent?.status).toBe(200);
    expect(sent?.body).toContain(up.reply());
    expect(blocks(up.of(h.session)[0]!.body.messages[0]).map((b) => b.text)).toEqual(h.mine.map((b) => b.text));
    await until("the reason is recorded", async () => (await recorded(s, { key: "draco", session: h.session, reason: /stop|drain/i, from: h.from })) !== null);
  }, 120_000);

  test("when the daemon shuts down, the gatherer is ended and the reason is recorded", async () => {
    const h = await held("astra", "Explain the retry ladder.");
    await Bun.sleep(1000);
    const pid = daemonPid(s);
    expect(pid).not.toBeNull();
    // the daemon alone is told to stop, as a service manager tells it: nothing else in its process group is
    process.kill(pid!, "SIGTERM");
    await until("the gatherer is ended", () => !alive(h.run), 10_000);
    await until("the daemon is down", () => !running(pid!), 90_000);
    await h.sending;
    await until("the reason is recorded", async () => (await recorded(s, { key: "astra", session: h.session, reason: /shut|stop|daemon/i, from: h.from })) !== null);

    await s.start();
    for (const key of HEADS) at[key] = await launch(s, `claude-${key}`);
  }, 300_000);

  test("a daemon killed outright leaves no gatherer behind: it runs under a deadline of its own", async () => {
    const h = await held("cirrus", "Add a --since flag to the logs command.");
    const pid = daemonPid(s);
    expect(pid).not.toBeNull();
    // only the daemon: a process group kill would end the gatherer for it
    process.kill(pid!, "SIGKILL");
    await until("the daemon is gone", () => !running(pid!), 10_000);
    await until("the gatherer is gone", () => !alive(h.run), (LIMIT_S + 10) * 1000);
  }, 120_000);

  test("the default time limit is one the spec's table of what was seen gives, from gathers measured on real work", () => {
    const spec = readFileSync(join(ROOT, "docs/specs/harness.md"), "utf8");
    const table = spec.slice(spec.indexOf("## Seen with Claude Code"));
    const rowsOf = table
      .split("\n")
      .filter((l) => l.startsWith("|") && !/^\|\s*-/.test(l))
      .map((l) => l.split("|").map((c) => c.trim()));
    const row = rowsOf.find((cells) => /time limit/i.test(cells[1] ?? "") && /gather|context getter/i.test(cells[1] ?? ""));
    expect(row, "a row of the table asks what the Context Getter's default time limit is").toBeDefined();
    // the row says what was measured, and the limit it gives is a finite number of seconds or minutes
    expect(row![2] ?? "").toMatch(/\d+\s*(s|seconds?|m|min|minutes?)\b/);
  });
});
