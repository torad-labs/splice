// restart-at-once, restart-verb: `splice restart` on a daemon that does not run under splice's systemd unit, the way a
// default install and macOS run it. That restart stops the daemon whole, so a compaction it would cut is the one kind
// it still waits for by default, and it says which kind and why before it waits. `--now` restarts without waiting and
// says, once, that it is no longer needed. `--wait` is taken. `--now --wait` is refused. A config the next daemon would
// refuse stops the restart before anything is stopped, and the running daemon keeps serving.
// (The restart under the unit, which hands over at once, is driven by RestartAtOnceTest against a stand-in unit.)

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { readFileSync, writeFileSync } from "node:fs";
import { Splice, freePort, mockHead, until } from "./lib/harness.ts";
import { Upstream } from "./lib/upstream.ts";
import { cliAsync, occurrences, servingPid, whole } from "./lib/handover.ts";
import { ask, launch, type Launched } from "../speed/lib/session.ts";

setDefaultTimeout(300_000);

let up: Upstream;
let s: Splice;
let l: Launched;

beforeAll(async () => {
  up = new Upstream("mock", "mock-model").start();
  s = await Splice.boot(mockHead({ key: "mock", command: "claude-mock", port: await freePort(), url: up.url, model: "mock-model" }));
  l = await launch(s, "claude-mock");
}, 900_000);

afterAll(async () => {
  await s?.dispose();
  up?.stop();
}, 60_000);

const mark = () => `RESTART-VERB-${crypto.randomUUID().slice(0, 8)}`;

/** A compaction started now, read to its end, with when it ended. */
function compaction(): Promise<{ status: number; body: string; endedAt: number }> {
  return ask(l, crypto.randomUUID(), mark(), { compaction: true }).then((r) => ({ ...r, endedAt: Date.now() }));
}

async function served(): Promise<void> {
  const r = await ask(l, crypto.randomUUID(), mark());
  expect(r.status).toBe(200);
  expect(whole(r.body)).toBe(true);
}

describe("restart-verb", () => {
  test("--wait is taken, and the daemon comes back", async () => {
    const from = await servingPid(s);
    const r = await cliAsync(s, ["restart", "--wait"]);
    expect(r.code).toBe(0);
    expect(await servingPid(s)).not.toBe(from);
    await served();
  });

  test("--now and --wait together are refused, and nothing restarts", async () => {
    const from = await servingPid(s);
    const r = await cliAsync(s, ["restart", "--now", "--wait"]);
    expect(r.code).not.toBe(0);
    expect(`${r.out}${r.err}`).toContain("restart");
    expect(await servingPid(s)).toBe(from);
  });

  test("a config the next daemon would refuse stops the restart, and the running daemon keeps serving", async () => {
    const from = await servingPid(s);
    const good = readFileSync(s.config, "utf8");
    try {
      writeFileSync(s.config, `${good}\n[heads.ghost]\nprovider = "nobody"\nport = ${await freePort()}\n`);
      const r = await cliAsync(s, ["restart"]);
      expect(r.code).not.toBe(0);
      expect(await servingPid(s)).toBe(from);
      await served();
    } finally {
      writeFileSync(s.config, good);
    }
  });

  test("a compaction answering is waited for first, the output says which kind and why, and it finishes whole", async () => {
    up.behaviour = { ...up.behaviour, words: 80, gapMs: 250 };
    try {
      const from = await servingPid(s);
      const c = compaction();
      await until("the compaction answering", () => up.inflight >= 1, 30_000);
      await Bun.sleep(1_000);
      const r = await cliAsync(s, ["restart"]);
      expect(r.code).toBe(0);
      expect(r.out).toContain("waiting first for the compactions running on every command");
      expect(r.out).toContain("does not run under splice's systemd unit");
      expect(r.out).toContain("--now restarts without waiting");
      const done = await c;
      expect(done.status).toBe(200);
      expect(whole(done.body)).toBe(true);
      expect(done.body).toContain("w79");
      expect(done.endedAt).toBeLessThanOrEqual(r.endedAt);
      expect(await servingPid(s)).not.toBe(from);
      await served();
    } finally {
      up.behaviour = { ...up.behaviour, words: 5, gapMs: 20 };
    }
  });

  test("--now restarts without waiting for the compaction, and says once that it is no longer needed", async () => {
    up.behaviour = { ...up.behaviour, words: 400, gapMs: 250 };
    try {
      const from = await servingPid(s);
      const startedAt = Date.now();
      const c = compaction();
      await until("the compaction answering", () => up.inflight >= 1, 30_000);
      await Bun.sleep(1_000);
      const r = await cliAsync(s, ["restart", "--now"]);
      expect(r.code).toBe(0);
      expect(occurrences(r.out, "--now is no longer needed")).toBe(1);
      expect(r.out).not.toContain("waiting first for the compactions");
      // the compaction streams for 100 s; the restart did not wait for it
      expect(r.endedAt - startedAt).toBeLessThan(90_000);
      expect(await servingPid(s)).not.toBe(from);
      await c.catch(() => null);
      await served();
    } finally {
      up.behaviour = { ...up.behaviour, words: 5, gapMs: 20 };
    }
  });
});
