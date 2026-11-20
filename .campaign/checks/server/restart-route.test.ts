// restart-at-once, daemon-restart-route: a restart the daemon takes on (POST /api/daemon/restart: the console's
// Restart, a save that needs one) on a daemon run the way splice's systemd unit runs it, with the sockets held and the
// launcher supervising. It hands over at once while an answer and a compaction are arriving on a command that hands
// over: both finish whole for their sessions, a request sent during the hand-over is answered, and the next daemon
// serves the next request. A compaction on a command that stops whole (a local runtime that pins conversations to
// slots) is the one kind it still waits for by default. `?wait=1` waits for every compaction, as before; `?now=1` and
// `?wait=1` together are refused. A config the next daemon would refuse is refused before anything is asked of the
// launcher, and the running daemon keeps serving.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { readFileSync, writeFileSync } from "node:fs";
import { type Splice, freePort, mockHead, until } from "./lib/harness.ts";
import { Upstream } from "./lib/upstream.ts";
import { bootUnderLauncher, health, nextDaemon, servingPid, whole } from "./lib/handover.ts";
import { ask, launch, type Launched } from "../speed/lib/session.ts";

setDefaultTimeout(400_000);

let open: Upstream;
let pinnedUp: Upstream;
let s: Splice;
let mock: Launched;
let pinned: Launched;

beforeAll(async () => {
  open = new Upstream("open", "open-model").start();
  pinnedUp = new Upstream("pinned", "pinned-model").start();
  const ports = { open: await freePort(), pinned: await freePort() };
  s = await bootUnderLauncher(
    mockHead({ key: "open", command: "claude-open", port: ports.open, url: open.url, model: "open-model" }) +
      mockHead({
        key: "pinned",
        command: "claude-pinned",
        port: ports.pinned,
        url: pinnedUp.url,
        model: "pinned-model",
        provider: "quirks = { slot_affinity = true }",
      }),
    ports,
  );
  mock = await launch(s, "claude-open");
  pinned = await launch(s, "claude-pinned");
}, 900_000);

afterAll(async () => {
  await s?.dispose();
  open?.stop();
  pinnedUp?.stop();
}, 120_000);

const mark = () => `RESTART-ROUTE-${crypto.randomUUID().slice(0, 8)}`;

interface Read {
  status: number;
  body: string;
  endedAt: number;
}

function send(l: Launched, compaction = false): Promise<Read> {
  return ask(l, crypto.randomUUID(), mark(), { compaction }).then((r) => ({ ...r, endedAt: Date.now() }));
}

async function restart(query = ""): Promise<{ status: number; json: Record<string, unknown> }> {
  const r = await s.api(`/api/daemon/restart${query}`, { method: "POST" });
  return { status: r.status, json: (await r.json().catch(() => ({}))) as Record<string, unknown> };
}

// The behaviour object is replaced, never changed in place: a stream already running reads the one it started with.
function slow(up: Upstream, words: number): void {
  up.behaviour = { ...up.behaviour, words, gapMs: 250 };
}

function quick(up: Upstream): void {
  up.behaviour = { ...up.behaviour, words: 5, gapMs: 20 };
}

describe("daemon-restart-route", () => {
  test("the daemon says it hands over at a restart", async () => {
    expect((await health(s))?.launcher).toBeTruthy();
    const r = await s.api("/api/daemon/restart");
    expect(((await r.json()) as { hands_over?: unknown }).hands_over).toBe(true);
  });

  test("with an answer and a compaction arriving, the restart hands over at once and both finish whole", async () => {
    slow(open, 240); // 60 s of answer each
    try {
      const from = await servingPid(s);
      const answer = send(mock);
      const comp = send(mock, true);
      await until("the answer and the compaction both answering", () => open.inflight >= 2, 30_000);
      await Bun.sleep(1_000);
      const asked = Date.now();
      const taken = await restart();
      expect(taken.status).toBe(202);
      expect(taken.json.status).not.toBe("waiting");
      expect(Date.now() - asked).toBeLessThan(5_000);
      quick(open);
      // a request sent while the hand-over is under way is answered
      const during = send(mock);
      const nextAt = await nextDaemon(s, from);
      const [a, c, d] = await Promise.all([answer, comp, during]);
      for (const r of [a, c, d]) {
        expect(r.status).toBe(200);
        expect(whole(r.body)).toBe(true);
      }
      expect(a.body).toContain("w239");
      expect(c.body).toContain("w239");
      // the next daemon took over while both were still arriving: what kept them whole was the hand-over
      expect(nextAt).toBeLessThan(Math.min(a.endedAt, c.endedAt));
      const after = await send(mock);
      expect(after.status).toBe(200);
      expect(whole(after.body)).toBe(true);
    } finally {
      quick(open);
    }
  });

  test("a compaction on a command that stops whole is waited for first, then the restart hands over", async () => {
    slow(pinnedUp, 80); // 20 s
    try {
      const from = await servingPid(s);
      const comp = send(pinned, true);
      await until("the compaction answering", () => pinnedUp.inflight >= 1, 30_000);
      await Bun.sleep(1_000);
      const taken = await restart();
      expect(taken.status).toBe(202);
      expect(taken.json.status).toBe("waiting");
      expect(taken.json.compactions).toBe(1);
      await Bun.sleep(5_000);
      expect(await servingPid(s)).toBe(from);
      const c = await comp;
      expect(c.status).toBe(200);
      expect(whole(c.body)).toBe(true);
      expect(c.body).toContain("w79");
      await nextDaemon(s, from);
    } finally {
      quick(pinnedUp);
    }
  });

  test("?wait=1 waits for a compaction on a command that hands over, as before", async () => {
    slow(open, 60); // 15 s
    try {
      const from = await servingPid(s);
      const comp = send(mock, true);
      await until("the compaction answering", () => open.inflight >= 1, 30_000);
      await Bun.sleep(1_000);
      const taken = await restart("?wait=1");
      expect(taken.status).toBe(202);
      expect(taken.json.status).toBe("waiting");
      const c = await comp;
      expect(whole(c.body)).toBe(true);
      await nextDaemon(s, from);
    } finally {
      quick(open);
    }
  });

  test("?now=1 and ?wait=1 together are refused, and nothing restarts", async () => {
    const from = await servingPid(s);
    const taken = await restart("?now=1&wait=1");
    expect(taken.status).toBe(400);
    await Bun.sleep(5_000);
    expect(await servingPid(s)).toBe(from);
  });

  test("a config the next daemon would refuse is refused before the launcher is asked, and the daemon keeps serving", async () => {
    const from = await servingPid(s);
    const good = readFileSync(s.config, "utf8");
    try {
      writeFileSync(s.config, `${good}\n[heads.ghost]\nprovider = "nobody"\nport = ${await freePort()}\n`);
      const taken = await restart();
      expect(taken.status).toBe(422);
      expect(String(taken.json.error ?? "")).toMatch(/ghost|nobody/);
      await Bun.sleep(20_000);
      expect(await servingPid(s)).toBe(from);
      const r = await send(mock);
      expect(r.status).toBe(200);
      expect(whole(r.body)).toBe(true);
    } finally {
      writeFileSync(s.config, good);
    }
  });
});
